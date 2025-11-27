package peer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.Map;
import java.util.zip.CRC32;

//TCP server to receive SEND_CHUNK frames
//this class accepts a tcp connection, reads the header, and extracts the following info
//fileName, chunkId, chunkSize, checksum
public class TcpChunkServer {

    public static void startTcpChunkServer(ServerSocket ss, DatagramSocket udpSocket,
                                           InetAddress serverAddr, int serverPort, String selfName,
                                           Map<String, String> expectedStoreReqs) {
        new Thread(() -> {
            try {
                // Create peer-specific storage directory
                String storageDir = "storage_" + selfName;
                File peerStorageRoot = new File(storageDir);
                if (!peerStorageRoot.exists()) {
                    peerStorageRoot.mkdirs();
                    System.out.println("Created peer-specific storage directory: " + storageDir);
                }
                
                System.out.println("TCP chunk server listening on port " + ss.getLocalPort());
                while (true) {
                    try (Socket s = ss.accept()) {
                        InputStream in = s.getInputStream();
                        OutputStream out = s.getOutputStream();

                        // read first line
                        StringBuilder line = new StringBuilder();
                        int c;
                        while ((c = in.read()) != -1 && c != '\n') line.append((char)c);
                        String header = line.toString().trim();
                        String[] h = header.split("\\s+");
                        if (h.length == 0) {
                            System.out.println("Empty TCP header");
                            continue;
                        }

                        String cmd = h[0].toUpperCase();

                        if ("SEND_CHUNK".equals(cmd)) {
                            if (h.length < 6) {
                                System.out.println("Invalid SEND_CHUNK header: " + header);
                                continue;
                            }

                            int rq       = safeInt(h[1]);
                            String fileName = h[2];
                            int chunkId  = safeInt(h[3]);
                            int chunkSize = safeInt(h[4]);
                            long checksum = Long.parseLong(h[5]);

                            //Determine owner for this chunk using expectedStoreReqs map
                            String storeKey = fileName + ":" + chunkId;
                            String ownerName = expectedStoreReqs.get(storeKey);
                            
                            //Skip STORE_REQ wait for replication requests (RQ# 99)
                            boolean isReplication = (rq == 99);
                            
                            //wait up to 5000ms for STORE_REQ to arrive (unless replication)
                            if (ownerName == null && !isReplication) {
                                long start = System.currentTimeMillis();
                                while (ownerName == null && (System.currentTimeMillis() - start) < 5000) {
                                    try { Thread.sleep(100); } catch (InterruptedException ie) { break; }
                                    ownerName = expectedStoreReqs.get(storeKey);
                                }
                                if (ownerName == null) {
                                    System.err.printf("REJECTED: No STORE_REQ received for %s after waiting. Discarding chunk.%n", storeKey);
                                    String errorMsg = String.format("CHUNK_ERROR %02d %s %d NoStoreReq", rq, fileName, chunkId);
                                    byte[] errData = errorMsg.getBytes();
                                    udpSocket.send(new DatagramPacket(errData, errData.length, serverAddr, serverPort));
                                    continue;
                                }
                            }
                            
                            //For replication, extract owner from existing chunk filename
                            if (isReplication && ownerName == null) {
                                // Try to find existing chunk to determine owner
                                if (peerStorageRoot.exists()) {
                                    File[] ownerDirs = peerStorageRoot.listFiles(File::isDirectory);
                                    if (ownerDirs != null) {
                                        for (File dir : ownerDirs) {
                                            File[] chunks = dir.listFiles((d, n) -> n.contains(fileName));
                                            if (chunks != null && chunks.length > 0) {
                                                ownerName = dir.getName();
                                                break;
                                            }
                                        }
                                    }
                                }
                                if (ownerName == null) {
                                    ownerName = "REPLICATED"; // Fallback for replicated chunks
                                }
                            }

                            //Store under storage_<peerName>/<ownerName>/
                            // Filename format: O_<ownerName>_S_<saverName>_<fileName>.<chunkId>.part
                            File ownerDir = new File(peerStorageRoot, ownerName);
                            ownerDir.mkdirs();
                            String chunkFileName = String.format("O_%s_S_%s_%s.%d.part", 
                                                                 ownerName, selfName, fileName, chunkId);
                            File outFile = new File(ownerDir, chunkFileName);

                            CRC32 crc = new CRC32();
                            int actualBytesReceived = 0;
                            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                int remaining = chunkSize;
                                byte[] bufLocal = new byte[8192];
                                while (remaining > 0) {
                                    int n = in.read(bufLocal, 0, Math.min(bufLocal.length, remaining));
                                    if (n == -1) break;
                                    fos.write(bufLocal, 0, n);
                                    crc.update(bufLocal, 0, n);
                                    remaining -= n;
                                    actualBytesReceived += n;
                                }
                            }

                            long calc = crc.getValue();
                            boolean ok = (calc == checksum);
                            
                            if (!ok) {
                                System.err.printf("CHECKSUM MISMATCH: file=%s chunk=%d expected=%d actual=%d bytesExpected=%d bytesReceived=%d%n",
                                    fileName, chunkId, checksum, calc, chunkSize, actualBytesReceived);
                            }
                            String ackMsg = ok
                                    ? String.format("CHUNK_OK %02d %s %d", rq, fileName, chunkId)
                                    : String.format("CHUNK_ERROR %02d %s %d ChecksumMismatch", rq, fileName, chunkId);
                            byte[] ackData = ackMsg.getBytes();
                            udpSocket.send(new DatagramPacket(ackData, ackData.length, serverAddr, serverPort));
                                System.out.printf("Stored chunk file=%s chunk=%d owner=%s path=%s size=%d checksumSent=%d checksumCalc=%d ok=%s%n",
                                    fileName, chunkId, ownerName, outFile.getAbsolutePath(), chunkSize, checksum, calc, ok);

                            if (ok) {
                                int rqStore = rq;
                                if (isReplication) {
                                    // Send REPLICATE_ACK to server for replicated chunk
                                    String replicateAck = String.format("REPLICATE_ACK %s %d %s", fileName, chunkId, selfName);
                                    byte[] replicateAckData = replicateAck.getBytes();
                                    udpSocket.send(new DatagramPacket(replicateAckData, replicateAckData.length, serverAddr, serverPort));
                                } else {
                                    String storeAck = String.format("STORE_ACK %02d %s %d", rqStore, fileName, chunkId);
                                    byte[] storeAckData = storeAck.getBytes();
                                    udpSocket.send(new DatagramPacket(storeAckData, storeAckData.length, serverAddr, serverPort));
                                    expectedStoreReqs.remove(storeKey);
                                }
                            }

                        } else if ("GET_CHUNK".equals(cmd)) {
                            if (h.length < 4) {
                                System.out.println("Invalid GET_CHUNK header: " + header);
                                continue;
                            }

                            int rq = safeInt(h[1]);
                            String fileName = h[2];
                            int chunkId  = safeInt(h[3]);

                            // Locate chunk in peer-specific storage or any owner subfolder
                            // Search for both old format and new format (O_*_S_*_fileName.chunkId.part)
                            File inFile = null;
                            
                            //First try old format
                            inFile = new File(peerStorageRoot, fileName + "." + chunkId + ".part");
                            
                            // Search in owner subdirectories
                            if (!inFile.exists()) {
                                File[] owners = peerStorageRoot.listFiles(File::isDirectory);
                                if (owners != null) {
                                    for (File od : owners) {
                                        // Try old format
                                        File candidate = new File(od, fileName + "." + chunkId + ".part");
                                        if (candidate.exists()) { 
                                            inFile = candidate; 
                                            break; 
                                        }
                                        
                                        // Try new format: O_*_S_*_fileName.chunkId.part
                                        File[] chunks = od.listFiles((dir, name) -> 
                                            name.endsWith("_" + fileName + "." + chunkId + ".part"));
                                        if (chunks != null && chunks.length > 0) {
                                            inFile = chunks[0];
                                            break;
                                        }
                                    }
                                }
                            }
                            
                            if (inFile == null || !inFile.exists()) {
                                System.out.println("Requested chunk not found anywhere: " + fileName + "." + chunkId + ".part");
                                continue;
                            }

                            long chunkSize = inFile.length();
                            CRC32 crc = new CRC32();
                            byte[] bufLocal = new byte[8192];

                            try (FileInputStream fis = new FileInputStream(inFile)) {
                                int n;
                                while ((n = fis.read(bufLocal)) != -1) {
                                    crc.update(bufLocal, 0, n);
                                }
                            }
                            long checksum = crc.getValue();

                            String dataHeader = String.format(
                                "CHUNK_DATA %02d %s %d %d %d\n",
                                rq, fileName, chunkId, chunkSize, checksum);
                            out.write(dataHeader.getBytes());
                            out.flush();

                            try (FileInputStream fis = new FileInputStream(inFile)) {
                                int n;
                                while ((n = fis.read(bufLocal)) != -1) {
                                    out.write(bufLocal, 0, n);
                                }
                            }
                            out.flush();
                                System.out.printf("Sent CHUNK_DATA file=%s chunk=%d size=%d checksum=%d path=%s%n",
                                    fileName, chunkId, chunkSize, checksum, inFile.getAbsolutePath());

                        } else {
                            System.out.println("Unknown TCP command: " + header);
                        }

                    } catch (IOException ex) {
                        System.err.println("TCP receive error: " + ex.getMessage());
                    }
                }
            } finally {
                try { ss.close(); } catch (IOException ignore) {}
            }
        }, "tcp-chunk-server").start();
    }

    private static int safeInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }
}
