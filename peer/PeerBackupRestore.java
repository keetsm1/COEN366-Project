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

public class PeerBackupRestore {

    private static int safeInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private static long crc32file(File f) throws IOException {
        CRC32 crc = new CRC32();
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) crc.update(buf, 0, n);
        }
        return crc.getValue();
    }

    // === LIST HANDLER ===
    public static void handleListRequest(DatagramSocket ds, InetAddress ip, int serverPort,
                                         Map<String, PeerData> knownPeers) throws IOException {

        byte[] data = "LIST".getBytes();
        DatagramPacket pkt = new DatagramPacket(data, data.length, ip, serverPort);
        ds.send(pkt);

        byte[] bufList = new byte[4096];
        DatagramPacket resp = new DatagramPacket(bufList, bufList.length);
        ds.receive(resp);
        String listResp = new String(resp.getData(), 0, resp.getLength()).trim();
        System.out.println("Server: " + listResp);

        String[] listParts = listResp.split("\\s+");
        if (listParts.length > 1 && "PEERS".equals(listParts[0])) {
            int count = safeInt(listParts[1]);
            int idx = 2;
            for (int i = 0; i < count && idx + 3 < listParts.length; i++) {
                String pName = listParts[idx++];
                String pIp = listParts[idx++];
                int pUdp = safeInt(listParts[idx++]);
                int pTcp = safeInt(listParts[idx++]);
                try {
                    knownPeers.put(pName, new PeerData(pName, "UNKNOWN",
                            InetAddress.getByName(pIp), pUdp, pTcp, "0"));
                } catch (Exception e) {}
            }
        }
    }

    // === BACKUP HANDLER ===
    public static void handleBackupRequest(String inp, DatagramSocket ds, InetAddress ip, int serverPort,
                                           Map<String, PeerData> knownPeers) throws IOException {

        File f = new File(inp.substring(7).trim());
        if (!f.exists() || !f.isFile()) {
            System.out.println("File does not exist.");
            return;
        }

        long size = f.length();
        long sum = crc32file(f);
        String req = String.format("BACKUP_REQ %02d %s %d %d", nextRq(), f.getName(), size, sum);
        byte[] data = req.getBytes();
        ds.send(new DatagramPacket(data, data.length, ip, serverPort));

        byte[] b = new byte[2048];
        DatagramPacket resp = new DatagramPacket(b, b.length);
        ds.receive(resp);
        String plan = new String(resp.getData(), 0, resp.getLength()).trim();
        System.out.println("Server response: " + plan);

        if (!plan.startsWith("BACKUP_PLAN")) return;

        String[] p = plan.split("\\s+");
        if (p.length < 5) { System.out.println("Malformed BACKUP_PLAN"); return; }

        String fileName = p[2];
        String peerListToken = p[3];
        int chunkSize = safeInt(p[4]);

        String peerListContent = peerListToken.substring(1, peerListToken.length()-1);
        String[] peerNames = peerListContent.split(",\\s*");
        if (peerNames.length == 0) { System.out.println("No peers in BACKUP_PLAN"); return; }

        String targetPeerName = peerNames[0];
        PeerData targetPeer = knownPeers.get(targetPeerName);

        // fallback: refresh LIST
        if (targetPeer == null) {
            handleListRequest(ds, ip, serverPort, knownPeers);
            targetPeer = knownPeers.get(targetPeerName);
            if (targetPeer == null) {
                System.out.println("Peer " + targetPeerName + " still not found.");
                return;
            }
        }

        String targetIp = targetPeer.getIp().getHostAddress();
        int targetTcp = targetPeer.getTcpPort();

        // Calculate number of chunks needed for the entire file
        int numChunks = (int) Math.ceil((double) size / chunkSize);
        System.out.printf("Backing up file to storage peer %s at %s:%d (fileSize=%d, chunkSize=%d, numChunks=%d)\n",
                targetPeerName, targetIp, targetTcp, size, chunkSize, numChunks);

        try (FileInputStream mainFis = new FileInputStream(f)) {
            for (int chunkId = 0; chunkId < numChunks; chunkId++) {
                // Calculate this chunk's size
                long remainingFileBytes = size - ((long) chunkId * chunkSize);
                int thisChunkSize = (int) Math.min(remainingFileBytes, chunkSize);

                // Read chunk data and calculate checksum
                byte[] chunkData = new byte[thisChunkSize];
                int totalRead = 0;
                while (totalRead < thisChunkSize) {
                    int n = mainFis.read(chunkData, totalRead, thisChunkSize - totalRead);
                    if (n == -1) break;
                    totalRead += n;
                }

                if (totalRead != thisChunkSize) {
                    System.err.printf("Warning: Could not read full chunk %d (expected %d, got %d)\n",
                            chunkId, thisChunkSize, totalRead);
                }

                // Calculate checksum for this chunk
                CRC32 chunkCrc = new CRC32();
                chunkCrc.update(chunkData, 0, totalRead);
                long chunkChecksum = chunkCrc.getValue();

                // Send this chunk via TCP
                try (Socket sock = new Socket(targetIp, targetTcp);
                     OutputStream out = sock.getOutputStream()) {

                    int rqSend = nextRq();
                    String header = String.format("SEND_CHUNK %02d %s %d %d %d\n",
                            rqSend, fileName, chunkId, totalRead, chunkChecksum);
                    out.write(header.getBytes());
                    out.write(chunkData, 0, totalRead);
                    out.flush();
                    
                    System.out.printf("Chunk %d/%d sent (size=%d bytes, checksum=%d)\n",
                            chunkId + 1, numChunks, totalRead, chunkChecksum);
                } catch (IOException e) {
                    System.err.printf("Chunk %d send failed: %s\n", chunkId, e.getMessage());
                    return; // Abort backup on failure
                }

                // Wait for CHUNK_OK for this chunk
                boolean chunkOk = false;
                try {
                    ds.setSoTimeout(3000);
                    byte[] ackBuf = new byte[512];
                    DatagramPacket ackPkt = new DatagramPacket(ackBuf, ackBuf.length);
                    ds.receive(ackPkt);
                    String ack = new String(ackPkt.getData(), 0, ackPkt.getLength()).trim();
                    System.out.printf("Chunk %d ack: %s\n", chunkId, ack);
                    if (ack.startsWith("CHUNK_OK")) chunkOk = true;
                } catch (Exception timeout) {
                    System.out.printf("No CHUNK_OK received for chunk %d within timeout\n", chunkId);
                } finally {
                    ds.setSoTimeout(0);
                }

                if (!chunkOk) {
                    System.err.printf("Backup failed: chunk %d was not acknowledged\n", chunkId);
                    return;
                }
            }
        } catch (IOException e) {
            System.err.println("Backup failed: " + e.getMessage());
            return;
        }

        System.out.println("All chunks sent successfully!");

        // Old single-chunk CHUNK_OK wait removed - now handled per chunk

        // Send BACKUP_DONE to server
        int rqDone = nextRq();
        String backupDone = String.format("BACKUP_DONE %02d %s", rqDone, f.getName());
        byte[] doneData = backupDone.getBytes();
        ds.send(new DatagramPacket(doneData, doneData.length, ip, serverPort));
        System.out.println("Sent BACKUP_DONE to server");
    }

    // === RESTORE HANDLER ===
    public static void handleRestoreRequest(String inp, DatagramSocket ds, InetAddress ip,
                                            int serverPort, Map<String, PeerData> knownPeers) throws IOException {

        String fileName = inp.substring(7).trim();
        if (fileName.isEmpty()) {
            System.out.println("Usage: restore <filename>");
            return;
        }

        int rq = nextRq();
        String req = String.format("RESTORE_REQ %02d %s", rq, fileName);
        byte[] reqData = req.getBytes();
        ds.send(new DatagramPacket(reqData, reqData.length, ip, serverPort));

        byte[] restorebuf = new byte[2048];
        DatagramPacket resp = new DatagramPacket(restorebuf, restorebuf.length);
        ds.receive(resp);
        String respMsg = new String(resp.getData(), 0, resp.getLength()).trim();
        System.out.println("Server: " + respMsg);

        if (respMsg.startsWith("RESTORE_FAIL")) return;
        if (!respMsg.startsWith("RESTORE_PLAN")) {
            System.out.println("Unexpected response from server.");
            return;
        }

        String[] parts = respMsg.split("\\s+");
        String planFile = parts[2];
        String rawList = parts[3];
        String inner = rawList.substring(1, rawList.length() - 1);
        String[] peerNames = inner.isEmpty() ? new String[0] : inner.split(",");

        if (peerNames.length == 0) {
            System.out.println("No storage peers found in plan.");
            return;
        }

        // Get unique storage peer names from the list
        java.util.Set<String> uniquePeers = new java.util.LinkedHashSet<>();
        for (String pn : peerNames) {
            uniquePeers.add(pn);
        }

        PeerData target = null;
        for (String storagePeerName : uniquePeers) {
            target = knownPeers.get(storagePeerName);
            if (target != null) break;
        }

        if (target == null) {
            System.out.println("Cannot find storage peer. Try 'list'.");
            return;
        }

        File outDir = new File("restored");
        outDir.mkdirs();
        File outFile = new File(outDir, planFile);

        // Determine how many chunks to retrieve by checking what exists
        int chunkId = 0;
        boolean allChunksValid = true;

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            while (true) {
                // Try to get next chunk
                try (Socket sock = new Socket(target.getIp(), target.getTcpPort())) {
                    InputStream in = sock.getInputStream();
                    OutputStream out = sock.getOutputStream();

                    int rqGet = nextRq();
                    String header = String.format("GET_CHUNK %02d %s %d\n", rqGet, planFile, chunkId);
                    out.write(header.getBytes());
                    out.flush();

                    // Read response header
                    StringBuilder line = new StringBuilder();
                    int c;
                    while ((c = in.read()) != -1 && c != '\n') line.append((char) c);
                    String h = line.toString().trim();

                    if (h.isEmpty()) {
                        // No more chunks available
                        break;
                    }

                    String[] hh = h.split("\\s+");
                    if (hh.length < 6 || !"CHUNK_DATA".equals(hh[0])) {
                        System.out.println("Invalid chunk header or no more chunks: " + h);
                        break;
                    }

                    int chunkSize = Integer.parseInt(hh[4]);
                    long checksum = Long.parseLong(hh[5]);

                    // Read chunk data
                    CRC32 crc = new CRC32();
                    byte[] bb = new byte[8192];
                    int remaining = chunkSize;
                    while (remaining > 0) {
                        int n = in.read(bb, 0, Math.min(bb.length, remaining));
                        if (n == -1) break;
                        fos.write(bb, 0, n);
                        crc.update(bb, 0, n);
                        remaining -= n;
                    }

                    long calc = crc.getValue();
                    boolean ok = (calc == checksum);
                    System.out.printf("Chunk %d: checksum match? %s (expected=%d actual=%d, size=%d)\n",
                            chunkId, ok, checksum, calc, chunkSize);

                    if (!ok) {
                        allChunksValid = false;
                        break;
                    }

                    chunkId++;

                } catch (Exception e) {
                    System.out.printf("Error retrieving chunk %d: %s\n", chunkId, e.getMessage());
                    break;
                }
            }
        } catch (Exception e) {
            System.out.println("Restore error: " + e.getMessage());
            allChunksValid = false;
        }

        // Send result to server
        int rqReport = nextRq();
        String rep = allChunksValid && chunkId > 0
                ? String.format("RESTORE_OK %02d %s", rqReport, planFile)
                : String.format("RESTORE_FAIL %02d %s ChecksumMismatch", rqReport, planFile);

        ds.send(new DatagramPacket(rep.getBytes(), rep.length(), ip, serverPort));

        if (allChunksValid && chunkId > 0) {
            System.out.printf("File restored successfully: %s (%d chunks)\n", outFile.getAbsolutePath(), chunkId);
        } else {
            System.out.println("Restore failed or incomplete.");
        }
    }


    // local RQ generator for backup/restore
    private static int localCounter = 0;
    private static int nextRq() { return (localCounter++ % 99) + 1; }
}
