package server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashMap;

import peer.PeerData;

public class ServerMessageHandler {

    private static int serverRqCounter = 0;
    private static int storageSelectionIndex = 0; // Round-robin counter for storage peer selection

    private static int nextServerRq() {
        serverRqCounter = (serverRqCounter % 99) + 1;
        return serverRqCounter;
    }

    public static void handleMessage(
            String msg,
            DatagramSocket ds,
            DatagramPacket dpReceive,
            HashMap<String, PeerData> peers,
            HashMap<String, java.util.List<String>> backupTable, 
            java.util.Map<String, Long> lastHeartbeat,
            java.util.Map<String, Integer> heartbeatChunkCounts
    ) throws IOException {

        //Read the message and add a new peer to the hashmap so that the server can *track* peers.
        String[] parts = msg.split("\\s+");

        if (parts.length == 0) return;
        String cmd = parts[0].toUpperCase();

        //query to see what's in the registry
        if ("LIST".equals(cmd)) {
            StringBuilder sb = new StringBuilder("PEERS ").append(peers.size());
            for (PeerData pd : peers.values()) {
                sb.append(' ') // name ip udp tcp
                        .append(pd.getName()).append(' ')
                        .append(pd.getIp().getHostAddress()).append(' ')
                        .append(pd.getUdpPort()).append(' ')
                        .append(pd.getTcpPort());
            }
            sendSimple(ds, dpReceive, sb.toString());
            return;
        }

        // heartbeat handling
        if ("HEARTBEAT".equals(cmd)) {
            // Expected: HEARTBEAT RQ# Name Number_Chunks Timestamp
            if (parts.length < 5) {
                System.out.println("Malformed HEARTBEAT frame: " + msg);
                return;
            }
            int rq = safeInt(parts[1]);
            String name = parts[2];
            int numChunks = safeInt(parts[3]);
            long tsClient = safeLong(parts[4]); // currently unused other than logging
            PeerData pd = peers.get(name);
            if (pd == null) {
                System.out.printf("Heartbeat from unknown peer '%s' (rq=%d) ignored.%n", name, rq);
                return;
            }
            long now = System.currentTimeMillis();
            lastHeartbeat.put(name, now);
            heartbeatChunkCounts.put(name, numChunks);
            System.out.printf("[HEARTBEAT] name=%s rq=%d chunks=%d clientTs=%d serverTs=%d%n", name, rq, numChunks, tsClient, now);
            return;
        }

        if("DE-REGISTER".equalsIgnoreCase(cmd)) {
            if (parts.length< 3){
                sendSimple(ds,dpReceive, "DE-REGISTER-DENIED 00 REASON: Malformed");
            }else{
                int rq= safeInt(parts[1]);
                String name= parts[2];
                PeerData removed = peers.remove(name);
                if (removed == null){
                    sendSimple(ds,dpReceive, "DE-REGISTER-DENIED "+rq+" REASON: NotRegistered");
                } else {
                    System.out.printf("Peer '%s' deregistered. (remaining=%d)%n", name, peers.size());
                    sendSimple(ds,dpReceive, "DE-REGISTERED "+rq);
                }
            }
            return;
        }

        if ("BACKUP_REQ".equalsIgnoreCase(cmd)){
            //validate msg length
            if (parts.length < 5) {
                int rq = parts.length > 1 ? safeInt(parts[1]) : 0;
                sendSimple(ds, dpReceive, String.format("BACKUP-DENIED %02d REASON: Malformed", rq));
                return;
            }
            int rq = safeInt(parts[1]);
            String fileName = parts[2];
            long fileSize = safeLong(parts[3]);
            long checksum = safeLong(parts[4]);
            // identify the owner
            String owner = null;
            for (PeerData pd : peers.values()) {
                if (pd.getIp().equals(dpReceive.getAddress()) && pd.getUdpPort() == dpReceive.getPort()) {
                    owner = pd.getName();
                    break;
                }
            }
            if (owner == null) {
                sendSimple(ds, dpReceive, String.format("BACKUP-DENIED %02d REASON: NotRegistered", rq));
                return;
            }
            //Select a storage peer using round-robin (not the owner)
            java.util.List<PeerData> eligiblePeers = new java.util.ArrayList<>();
            for (PeerData pd : peers.values()) {
                if (!pd.getName().equals(owner) && !"OWNER".equalsIgnoreCase(pd.getRole())) {
                	int storageMB = Integer.parseInt(pd.getStorage().replace("MB", ""));
                	long storageBytes = storageMB * 1024L * 1024L; // Convert MB to bytes
                	if(storageBytes >= fileSize) {
                		sendtoSpecificPeer(ds, pd, String.format("STORAGE_TASK %02d %s %d %s", rq, fileName, fileSize, owner));
                        eligiblePeers.add(pd);
                	}
                }
            }
            
            if (eligiblePeers.isEmpty()) {
                sendSimple(ds, dpReceive, String.format("BACKUP-DENIED %02d REASON: NoStoragePeer", rq));
                return;
            }
            
            int chunkSize = 4096; //fixed size for now
            int numChunks = (int) Math.ceil((double) fileSize / chunkSize);

            // Build peer list - distribute chunks round-robin across all eligible peers
            StringBuilder peerListBuilder = new StringBuilder("[");
            for (int i = 0; i < eligiblePeers.size(); i++) {
                if (i > 0) peerListBuilder.append(",");
                peerListBuilder.append(eligiblePeers.get(i).getName());
            }
            peerListBuilder.append("]");
            String peerList = peerListBuilder.toString();
            
            String plan = String.format("BACKUP_PLAN %02d %s %s %d", rq, fileName, peerList, chunkSize);
            System.out.printf("BACKUP_REQ(rq=%02d file=%s size=%d checksum=%d owner=%s numChunks=%d peers=%d) -> %s%n", 
                rq, fileName, fileSize, checksum, owner, numChunks, eligiblePeers.size(), plan);
            sendSimple(ds, dpReceive, plan);

            String backupKey = owner + ":" + fileName;
            backupTable.put(backupKey, new java.util.ArrayList<>());
            System.out.printf("Created backupTable entry: %s%n", backupKey);

            //the store req is now spread out over the peers using round robin in order for recovery to work
            for (int chunkId = 0; chunkId < numChunks; chunkId++) {
                PeerData targetPeer = eligiblePeers.get(storageSelectionIndex % eligiblePeers.size());
                storageSelectionIndex++;
                
                int serverRq = nextServerRq();
                String storeReq = String.format("STORE_REQ %02d %s %d %s", serverRq, fileName, chunkId, owner);
                System.out.printf("Sending STORE_REQ to %s: %s (chunk %d/%d)%n", 
                    targetPeer.getName(), storeReq, chunkId + 1, numChunks);
                
                byte[] d = storeReq.getBytes();
                ds.send(new DatagramPacket(d, d.length, targetPeer.getIp(), targetPeer.getUdpPort()));
                if (chunkId == 0) {
                    try { Thread.sleep(100); } catch (InterruptedException ignore) {}
                    ds.send(new DatagramPacket(d, d.length, targetPeer.getIp(), targetPeer.getUdpPort()));
                    try { Thread.sleep(150); } catch (InterruptedException ignore) {}
                } else {
                    try { Thread.sleep(50); } catch (InterruptedException ignore) {}
                    ds.send(new DatagramPacket(d, d.length, targetPeer.getIp(), targetPeer.getUdpPort()));
                }
            }

            return;
        }

        //CHUNK_OK / CHUNK_ERROR: Forward to owner peer
        if ("CHUNK_OK".equalsIgnoreCase(cmd) || "CHUNK_ERROR".equalsIgnoreCase(cmd)) {
            System.out.println(cmd + " received: " + msg);
            //Extract file name from message to find owner
            if (parts.length >= 3) {
                String fileNameAck = parts[2];
                System.out.printf("DEBUG: Looking for owner of file '%s' in backupTable (keys=%s)%n", fileNameAck, backupTable.keySet());
                //Find owner peer for this file
                PeerData ownerPeer = null;
                for (String key : backupTable.keySet()) {
                    System.out.printf("DEBUG: Checking key '%s' endsWith ':%s' = %b%n", key, fileNameAck, key.endsWith(":" + fileNameAck));
                    if (key.endsWith(":" + fileNameAck)) {
                        String ownerName = key.substring(0, key.indexOf(":"));
                        ownerPeer = peers.get(ownerName);
                        System.out.printf("DEBUG: Found owner '%s', peer=%s%n", ownerName, ownerPeer);
                        break;
                    }
                }
                //Forward message to owner
                if (ownerPeer != null) {
                    byte[] fwdData = msg.getBytes();
                    ds.send(new DatagramPacket(fwdData, fwdData.length, ownerPeer.getIp(), ownerPeer.getUdpPort()));
                    System.out.printf("Forwarded %s to owner %s%n", cmd, ownerPeer.getName());
                } else {
                    System.err.printf("ERROR: Could not find owner for file '%s' (backupTable has %d entries)%n", fileNameAck, backupTable.size());
                }
            }
            return;
        }

        //STORE_ACK RQ# File_Name Chunk_ID
        if ("STORE_ACK".equalsIgnoreCase(cmd)) {
            if (parts.length >= 4) {
                String fileNameAck = parts[2];
                int chunkIdAck = safeInt(parts[3]);
                //Identify which peer sent this
                String storagePeerName = null;
                for (PeerData pd : peers.values()) {
                    if (pd.getIp().equals(dpReceive.getAddress()) && pd.getUdpPort() == dpReceive.getPort()) {
                        storagePeerName = pd.getName();
                        break;
                    }
                }
                System.out.printf("STORE_ACK received: file=%s chunk=%d from peer=%s%n", fileNameAck, chunkIdAck, storagePeerName);
                //Update backup table: find matching owner:filename entry
                for (String key : backupTable.keySet()) {
                    if (key.endsWith(":" + fileNameAck) && storagePeerName != null) {
                        backupTable.get(key).add(storagePeerName + ":" + chunkIdAck);
                        break;
                    }
                }
            }
            return;
        }

        //BACKUP_DONE RQ# File_Name
        if ("BACKUP_DONE".equalsIgnoreCase(cmd)) {
            if (parts.length >= 3) {
                String fileNameDone = parts[2];
                String ownerName = null;
                for (PeerData pd : peers.values()) {
                    if (pd.getIp().equals(dpReceive.getAddress()) && pd.getUdpPort() == dpReceive.getPort()) {
                        ownerName = pd.getName();
                        break;
                    }
                }
                System.out.printf("BACKUP_DONE received: file=%s from owner=%s%n", fileNameDone, ownerName);

            }
            return;
        }

        //RESTORE_OK RQ# File_Name
        if ("RESTORE_OK".equalsIgnoreCase(cmd)) {
            if (parts.length >= 3) {
                int rq = safeInt(parts[1]);
                String fileName = parts[2];
                String ownerName = null;
                for (PeerData pd : peers.values()) {
                    if (pd.getIp().equals(dpReceive.getAddress()) && pd.getUdpPort() == dpReceive.getPort()) {
                        ownerName = pd.getName();
                        break;
                    }
                }
                System.out.printf("RESTORE_OK received: rq=%02d file=%s from owner=%s - Restoration successful%n", rq, fileName, ownerName);
            }
            return;
        }

        //RESTORE_FAIL RQ# File_Name Reason
        if ("RESTORE_FAIL".equalsIgnoreCase(cmd)) {
            if (parts.length >= 3) {
                int rq = safeInt(parts[1]);
                String fileName = parts[2];
                String reason = parts.length >= 4 ? parts[3] : "Unknown";
                String ownerName = null;
                for (PeerData pd : peers.values()) {
                    if (pd.getIp().equals(dpReceive.getAddress()) && pd.getUdpPort() == dpReceive.getPort()) {
                        ownerName = pd.getName();
                        break;
                    }
                }
                System.out.printf("RESTORE_FAIL received: rq=%02d file=%s from owner=%s - Reason: %s%n", rq, fileName, ownerName, reason);
            }
            return;
        }

        if ("RESTORE_REQ".equalsIgnoreCase(cmd)) {
            if (parts.length < 3) {
                int rq = parts.length > 1 ? safeInt(parts[1]) : 0;
                sendSimple(ds, dpReceive,
                        String.format("RESTORE_FAIL %02d %s Malformed", rq,
                                (parts.length > 2 ? parts[2] : "UNKNOWN")));
                return;
            }

            int rq = safeInt(parts[1]);
            String fileName = parts[2];

            // find which peer is asking (owner)
            String owner = null;
            for (PeerData pd : peers.values()) {
                if (pd.getIp().equals(dpReceive.getAddress())
                        && pd.getUdpPort() == dpReceive.getPort()) {
                    owner = pd.getName();
                    break;
                }
            }

            if (owner == null) {
                sendSimple(ds, dpReceive,
                        String.format("RESTORE_FAIL %02d %s NotRegistered", rq, fileName));
                return;
            }

            String key = owner + ":" + fileName;
            java.util.List<String> entries = backupTable.get(key);
            if (entries == null || entries.isEmpty()) {
                sendSimple(ds, dpReceive,
                        String.format("RESTORE_FAIL %02d %s NoBackupFound", rq, fileName));
                return;
            }

            // entries are like "peerName:chunkId"
            StringBuilder peerList = new StringBuilder("[");
            for (int i = 0; i < entries.size(); i++) {
                String entry = entries.get(i);
                String peerName = entry.split(":", 2)[0];
                if (i > 0) peerList.append(',');
                peerList.append(peerName);
            }
            peerList.append("]");

            String plan = String.format("RESTORE_PLAN %02d %s %s", rq, fileName, peerList);
            System.out.println("Sending: " + plan);
            sendSimple(ds, dpReceive, plan);
            return;
        }

        int rq = Integer.parseInt(parts[1]);
        String name = parts[2];
        String role = parts[3];

        InetAddress ip = InetAddress.getByName(parts[4]);

        int udpPort = Integer.parseInt(parts[5]);
        int tcpPort = Integer.parseInt(parts[6]);
        String storage = parts[7];

        if (!peers.containsKey(name)) {
            PeerData newPeer = new PeerData(name, role, ip, udpPort, tcpPort, storage);
            peers.put(name, newPeer);
            System.out.printf("Accepting registration: name=%s role=%s udpPort=%d tcpPort=%d storage=%s (total peers=%d)%n", name, role, udpPort, tcpPort, storage, peers.size());
            acceptRegistration(ds, dpReceive.getAddress(), dpReceive.getPort(), msg, 5678, 1024, rq);
            System.out.println("Current peers: " + peers.keySet());
        }
        //Hashmap already has this peer stored, don't add it to map and deny registration
        else {
            System.out.printf("Denying registration for existing peer name=%s (total peers=%d)%n", name, peers.size());
            denyRegistration(ds, dpReceive.getAddress(), dpReceive.getPort(), msg, 5678, 1024, rq,
                    "REASON: Peer registered in server");
        }
    }

    private static int safeInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }
    private static long safeLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0L; }
    }
    private static void sendSimple(DatagramSocket ds, DatagramPacket req, String text) throws IOException {
        byte[] d = text.getBytes();
        ds.send(new DatagramPacket(d, d.length, req.getAddress(), req.getPort()));
    }
    private static void sendtoSpecificPeer(DatagramSocket ds, PeerData peer, String text) throws IOException {
        byte[] d = text.getBytes();
        ds.send(new DatagramPacket(d, d.length, peer.getIp(), peer.getUdpPort()));
    }
    
    public static void acceptRegistration(DatagramSocket socket, InetAddress clientAddr, int serverPort, String name,
                                          int tcpPort, int storageCapacity, int rq) throws IOException {
        //int udpPort = socket.getLocalPort();
        String response = formatAcceptResponse(rq);
        byte[] sendData = response.getBytes();
        DatagramPacket dpSendResponse = new DatagramPacket(sendData, sendData.length, clientAddr, serverPort);
        socket.send(dpSendResponse);
    }

    public static void denyRegistration(DatagramSocket socket, InetAddress clientAddr, int serverPort, String name,
                                        int tcpPort, int storageCapacity, int rq, String reason) throws IOException {
        String response = formatDenyResponse(rq, reason);
        byte[] sendData = response.getBytes();
        DatagramPacket dpSendResponse = new DatagramPacket(sendData, sendData.length, clientAddr, serverPort);
        socket.send(dpSendResponse);
    }

    public static void acceptDeregistration(DatagramSocket socket, InetAddress clientAddr, int serverPort, String name,
                                            int tcpPort, int storageCapacity, int rq, String reason) throws IOException {
        String response = "Deregistration accepted.";
        byte[] sendData = response.getBytes();
        DatagramPacket dpSendResponse = new DatagramPacket(sendData, sendData.length, clientAddr, serverPort);
        socket.send(dpSendResponse);
    }

    public static String formatAcceptResponse(int rq) {
        //Response from server that project expects to be sent
        return String.format("REGISTERED %d", rq);
    }
    public static String formatDenyResponse(int rq, String reason) {
        //Response from server that project expects to be sent
        return String.format("REGISTER-DENIED %d %s", rq, reason);

    }
}
