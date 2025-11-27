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

        String listResp = null;
        try {
            listResp = ResponseInbox.waitFor("PEERS", 3000);
        } catch (InterruptedException e) {
            System.out.println("LIST request interrupted");
            return;
        }
        if (listResp == null) {
            System.out.println("No response from server for LIST request");
            return;
        }
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

        String plan = null;
        try {
            plan = ResponseInbox.waitFor("BACKUP_PLAN", 5000);
        } catch (InterruptedException e) {
            System.out.println("BACKUP_REQ interrupted");
            return;
        }
        if (plan == null) {
            System.out.println("No BACKUP_PLAN response from server");
            return;
        }
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

        // Populate all target peers from the list
        PeerData[] targetPeers = new PeerData[peerNames.length];
        for (int i = 0; i < peerNames.length; i++) {
            targetPeers[i] = knownPeers.get(peerNames[i]);
            if (targetPeers[i] == null) {
                // fallback: refresh LIST
                handleListRequest(ds, ip, serverPort, knownPeers);
                targetPeers[i] = knownPeers.get(peerNames[i]);
                if (targetPeers[i] == null) {
                    System.out.println("Peer " + peerNames[i] + " not found.");
                    return;
                }
            }
        }

        // Calculate number of chunks needed for the entire file
        int numChunks = (int) Math.ceil((double) size / chunkSize);
        System.out.printf("Backing up file to %d storage peers (fileSize=%d, chunkSize=%d, numChunks=%d)\n",
                targetPeers.length, size, chunkSize, numChunks);
        for (int i = 0; i < targetPeers.length; i++) {
            System.out.printf("  Peer %d: %s at %s:%d\n", i + 1, peerNames[i], 
                targetPeers[i].getIp().getHostAddress(), targetPeers[i].getTcpPort());
        }

        // Wait a moment for server to send STORE_REQ messages 
        System.out.printf("Waiting 5000ms for server to send %d STORE_REQ messages...%n", numChunks);
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
        }

        try (FileInputStream mainFis = new FileInputStream(f)) {
            for (int chunkId = 0; chunkId < numChunks; chunkId++) {
                //Round-robin: select target peer for this chunk
                PeerData targetPeer = targetPeers[chunkId % targetPeers.length];
                String targetIp = targetPeer.getIp().getHostAddress();
                int targetTcp = targetPeer.getTcpPort();
                
                //calculate this chunk's size
                long remainingFileBytes = size - ((long) chunkId * chunkSize);
                int thisChunkSize = (int) Math.min(remainingFileBytes, chunkSize);

                //read chunk data and calculate checksum
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

                //Calculate checksum for this chunk
                CRC32 chunkCrc = new CRC32();
                chunkCrc.update(chunkData, 0, totalRead);
                long chunkChecksum = chunkCrc.getValue();

                //Send this chunk via TCP 
                try (Socket sock = new Socket(targetIp, targetTcp);
                     OutputStream out = sock.getOutputStream()) {

                    int rqSend = nextRq();
                    String header = String.format("SEND_CHUNK %02d %s %d %d %d\n",
                            rqSend, fileName, chunkId, totalRead, chunkChecksum);
                    out.write(header.getBytes());
                    out.write(chunkData, 0, totalRead);
                    out.flush();
                    
                    System.out.printf("Chunk %d/%d sent to %s (size=%d bytes, checksum=%d)\n",
                            chunkId + 1, numChunks, peerNames[chunkId % targetPeers.length], totalRead, chunkChecksum);
                    // Small pacing to avoid outrunning STORE_REQ bursts from server
                    try { Thread.sleep(25); } catch (InterruptedException ie) {}
                } catch (IOException e) {
                    System.err.printf("Chunk %d send to %s failed: %s\n", chunkId, peerNames[chunkId % targetPeers.length], e.getMessage());
                    return;
                }

                //wait for CHUNK_OK or CHUNK_ERROR for this chunk (with retry)
                boolean chunkOk = false;
                int retryCount = 0;
                int maxRetries = 3;
                
                while (!chunkOk && retryCount < maxRetries) {
                    try {
                        long startTime = System.currentTimeMillis();
                        String ack = null;
                        
                        while (ack == null && (System.currentTimeMillis() - startTime) < 3000) {
                            ack = ResponseInbox.waitFor("CHUNK_OK", 100);
                            if (ack == null) {
                                ack = ResponseInbox.waitFor("CHUNK_ERROR", 100);
                            }
                        }
                        
                        if (ack != null) {
                            System.out.printf("Chunk %d response: %s\n", chunkId, ack);
                            // Parse the response to verify it's for THIS chunk
                            String[] ackParts = ack.split("\\s+");
                            if (ackParts.length >= 4) {
                                int ackChunkId = safeInt(ackParts[3]);
                                if (ackChunkId != chunkId) {
                                    continue; // Wrong chunk, keep waiting
                                }
                            }
                            if (ack.startsWith("CHUNK_OK")) {
                                chunkOk = true;
                                break;
                            } else if (ack.startsWith("CHUNK_ERROR") && ack.contains("NoStoreReq")) {
                                // NoStoreReq error - storage peer hasn't received STORE_REQ yet
                                retryCount++;
                                System.out.printf("Chunk %d rejected (NoStoreReq), waiting 1s and retrying... (attempt %d/%d)\n", 
                                    chunkId, retryCount, maxRetries);
                                Thread.sleep(1000);
                                //3 retries to send the chunk in case it didnt work and had chunkerror
                                if (retryCount < maxRetries) {
                                    PeerData retryPeer = targetPeers[chunkId % targetPeers.length];
                                    String retryIp = retryPeer.getIp().getHostAddress();
                                    int retryTcp = retryPeer.getTcpPort();
                                    
                                    try (Socket sock = new Socket(retryIp, retryTcp);
                                         OutputStream out = sock.getOutputStream()) {
                                        int rqRetry = nextRq();
                                        String header = String.format("SEND_CHUNK %02d %s %d %d %d\n",
                                                rqRetry, fileName, chunkId, totalRead, chunkChecksum);
                                        out.write(header.getBytes());
                                        out.write(chunkData, 0, totalRead);
                                        out.flush();
                                        System.out.printf("Chunk %d resent (attempt %d/%d)\n", chunkId, retryCount, maxRetries);
                                    }
                                }
                                continue;
                            } else {
                                System.err.printf("Chunk %d failed with error: %s\n", chunkId, ack);
                                break;
                            }
                        } else {
                            System.out.printf("No response received for chunk %d within timeout\n", chunkId);
                            retryCount++;
                        }
                    } catch (InterruptedException timeout) {
                        System.out.printf("Wait interrupted for chunk %d\n", chunkId);
                        break;
                    }
                }

                if (!chunkOk) {
                    System.err.printf("Backup failed: chunk %d was not acknowledged after %d attempts\n", chunkId, retryCount);
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

        String respMsg = null;
        try {
            respMsg = ResponseInbox.waitFor("RESTORE_PLAN", 5000);
        } catch (InterruptedException e) {
            System.out.println("RESTORE_REQ interrupted");
            return;
        }
        if (respMsg == null) {
            System.out.println("No response from server for RESTORE_REQ");
            return;
        }
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

        //build the peer array
        java.util.Set<String> uniquePeerNames = new java.util.LinkedHashSet<>();
        for (String pn : peerNames) {
            uniquePeerNames.add(pn);
        }
        
        PeerData[] storagePeers = new PeerData[uniquePeerNames.size()];
        int idx = 0;
        for (String peerName : uniquePeerNames) {
            PeerData pd = knownPeers.get(peerName);
            if (pd == null) {
                System.out.println("Unknown peer: " + peerName + ". Try 'list' first.");
                return;
            }
            storagePeers[idx++] = pd;
        }

        System.out.printf("Restoring from %d peers: %s\n", storagePeers.length, uniquePeerNames);

        File outDir = new File("restored");
        outDir.mkdirs();
        File outFile = new File(outDir, planFile);

        int chunkId = 0;
        boolean allChunksValid = true;

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            while (true) {
                //Round-robin: select peer for this chunk
                PeerData targetPeer = storagePeers[chunkId % storagePeers.length];
                
                try (Socket sock = new Socket(targetPeer.getIp(), targetPeer.getTcpPort())) {
                    InputStream in = sock.getInputStream();
                    OutputStream out = sock.getOutputStream();

                    int rqGet = nextRq();
                    String header = String.format("GET_CHUNK %02d %s %d\n", rqGet, planFile, chunkId);
                    out.write(header.getBytes());
                    out.flush();

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
                    System.out.printf("Chunk %d from %s: checksum match? %s (expected=%d actual=%d, size=%d)\n",
                            chunkId, uniquePeerNames.toArray()[chunkId % storagePeers.length], ok, checksum, calc, chunkSize);

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


    //local RQ generator for backup/restore
    private static int localCounter = 0;
    private static int nextRq() { return (localCounter++ % 99) + 1; }
}
