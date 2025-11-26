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
                            
                            //wait up to 3000ms for STORE_REQ to arrive
                            if (ownerName == null) {
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

                            //Store under storage/<ownerName>/
                            File baseDir = new File("storage");
                            File ownerDir = new File(baseDir, ownerName);
                            ownerDir.mkdirs();
                            File outFile = new File(ownerDir, fileName + "." + chunkId + ".part");

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
                                String storeAck = String.format("STORE_ACK %02d %s %d", rqStore, fileName, chunkId);
                                byte[] storeAckData = storeAck.getBytes();
                                udpSocket.send(new DatagramPacket(storeAckData, storeAckData.length, serverAddr, serverPort));
                                expectedStoreReqs.remove(storeKey);
                            }

                        } else if ("GET_CHUNK".equals(cmd)) {
                            if (h.length < 4) {
                                System.out.println("Invalid GET_CHUNK header: " + header);
                                continue;
                            }

                            int rq = safeInt(h[1]);
                            String fileName = h[2];
                            int chunkId  = safeInt(h[3]);

                            // Locate chunk in storage root or any owner subfolder
                            File inFile = new File("storage", fileName + "." + chunkId + ".part");
                            if (!inFile.exists()) {
                                File baseDir = new File("storage");
                                File[] owners = baseDir.listFiles(File::isDirectory);
                                if (owners != null) {
                                    for (File od : owners) {
                                        File candidate = new File(od, fileName + "." + chunkId + ".part");
                                        if (candidate.exists()) { inFile = candidate; break; }
                                    }
                                }
                            }
                            if (!inFile.exists()) {
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
