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

                            File outDir = new File("storage");
                            outDir.mkdirs();
                            File outFile = new File(outDir, fileName + "." + chunkId + ".part");

                            CRC32 crc = new CRC32();
                            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                int remaining = chunkSize;
                                byte[] bufLocal = new byte[8192];
                                while (remaining > 0) {
                                    int n = in.read(bufLocal, 0, Math.min(bufLocal.length, remaining));
                                    if (n == -1) break;
                                    fos.write(bufLocal, 0, n);
                                    crc.update(bufLocal, 0, n);
                                    remaining -= n;
                                }
                            }

                            long calc = crc.getValue();
                            boolean ok = (calc == checksum);
                            String ackMsg = ok
                                    ? String.format("CHUNK_OK %02d %s %d", rq, fileName, chunkId)
                                    : String.format("CHUNK_ERROR %02d %s %d ChecksumMismatch", rq, fileName, chunkId);
                            byte[] ackData = ackMsg.getBytes();
                            udpSocket.send(new DatagramPacket(ackData, ackData.length, serverAddr, serverPort));
                            System.out.printf("Stored chunk file=%s chunk=%d size=%d checksumSent=%d checksumCalc=%d ok=%s%n",
                                    fileName, chunkId, chunkSize, checksum, calc, ok);

                            if (ok) {
                                String storeKey = fileName + ":" + chunkId;
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

                            int rq       = safeInt(h[1]);
                            String fileName = h[2];
                            int chunkId  = safeInt(h[3]);

                            File inFile = new File("storage", fileName + "." + chunkId + ".part");
                            if (!inFile.exists()) {
                                System.out.println("Requested chunk not found: " + inFile.getAbsolutePath());
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
                            System.out.printf("Sent CHUNK_DATA file=%s chunk=%d size=%d checksum=%d%n",
                                    fileName, chunkId, chunkSize, checksum);

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
