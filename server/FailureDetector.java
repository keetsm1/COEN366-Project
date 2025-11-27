package server;
import java.net.DatagramSocket;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import peer.PeerData;

public class FailureDetector {
    private static final long HEARTBEAT_TIMEOUT_MS = 30000; // 30s timeout
    private static ScheduledExecutorService scheduler;

    public static void start(
        DatagramSocket socket, HashMap<String, PeerData> peers,
        HashMap<String, java.util.List<String>> backupTable,
        java.util.Map<String, Long> lastHeartbeat){
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(()-> {
                long now = System.currentTimeMillis();
                for (String name : peers.keySet()) {
                    Long last = lastHeartbeat.get(name);
                    if (last != null && now - last > HEARTBEAT_TIMEOUT_MS) {
                        System.out.printf("[HEARTBEAT] Peer '%s' timed out (last=%dms ago). Starting recovery.%n",
                                name, (now - last));
                        handleFailure(name, socket, peers,backupTable, lastHeartbeat);
                    }
                }
        }, 0, 5, TimeUnit.SECONDS);
    }

    private static void handleFailure(String failed, DatagramSocket socket,
        HashMap<String, PeerData> peers,
        HashMap<String, java.util.List<String>> backupTable,
        java.util.Map<String, Long> lastHeartbeat)
    {
        peers.remove(failed);
        lastHeartbeat.remove(failed);

        //find the lost chunks
        int totalLost = 0;
        int replicated = 0;
        int unrecoverable = 0;
        
        java.util.Map<String, java.util.List<Integer>> fileChunks = new java.util.HashMap<>();

        for(String key : backupTable.keySet()){
            java.util.List<String> entries = backupTable.get(key);
            for(String entry: new java.util.ArrayList<>(entries)){
                if (entry.startsWith(failed+":")){
                    entries.remove(entry);
                    String[] kp= key.split(":",2);
                    int chunkId= Integer.parseInt(entry.split(":")[1]);
                    
                    totalLost++;
                    fileChunks.computeIfAbsent(kp[1], k -> new java.util.ArrayList<>()).add(chunkId);
                    
                    boolean success = replicate(kp[0], kp[1], chunkId, socket, peers, entries);
                    if (success) {
                        replicated++;
                    } else {
                        unrecoverable++;
                    }
                }
            }
        }
        
        System.out.printf("\n=== RECOVERY SUMMARY ===%n");
        System.out.printf("Peer '%s' failed. Lost %d chunks across %d files%n", failed, totalLost, fileChunks.size());
        for (String file : fileChunks.keySet()) {
            System.out.printf("  - %s: %d chunks%n", file, fileChunks.get(file).size());
        }
        System.out.printf("Replicated: %d | Unrecoverable: %d%n", replicated, unrecoverable);
        System.out.printf("=======================%n%n");
    }

    private static boolean replicate(String owner, String file, int chunkId,
                                  DatagramSocket socket, HashMap<String, PeerData> peers,
                                  java.util.List<String> existingChunks) {
        
        //Find source peer (exclude owner from being source)
        String source = null;
        for (String chunk : existingChunks) {
            String peerName = chunk.split(":")[0];
            if (!peerName.equals(owner)) {
                source = peerName;
                break;
            }
        }
        
        //find the new saver (prefer STORAGE, but allow OWNER as fallback)
        String target = null;
        // First try: find another STORAGE peer
        for (String p : peers.keySet()) {
            if (!p.equals(owner) && !p.equals(source) && 
                "STORAGE".equals(peers.get(p).getRole())) {
                target = p;
                break;
            }
        }
        // Fallback: allow any peer except owner and source (including OWNER role peers)
        if (target == null) {
            for (String p : peers.keySet()) {
                if (!p.equals(owner) && !p.equals(source)) {
                    target = p;
                    break;
                }
            }
        }
        
        if (source == null || target == null) {
            System.out.printf("  ✗ Cannot replicate %s:%d (source=%s, target=%s)%n", 
                             file, chunkId, source, target);
            return false;
        }
        
        PeerData srcPeer = peers.get(source);
        PeerData tgtPeer = peers.get(target);
        
        try {
            String msg = String.format("REPLICATE_REQ 99 %s %d %s %s %d", 
                                      file, chunkId, target, 
                                      tgtPeer.getIp().getHostAddress(), 
                                      tgtPeer.getTcpPort());
            byte[] buf = msg.getBytes();
            socket.send(new java.net.DatagramPacket(buf, buf.length, 
                                                    srcPeer.getIp(), 
                                                    srcPeer.getUdpPort()));
            return true;
        } catch (Exception e) {
            System.err.printf("  ✗ Replication failed for %s:%d - %s%n", file, chunkId, e.getMessage());
            return false;
        }
    }
}
