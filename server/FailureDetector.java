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

        for(String key : backupTable.keySet()){
            java.util.List<String> entries = backupTable.get(key);
            for(String entry: new java.util.ArrayList<>(entries)){
                if (entry.startsWith(failed+":")){
                    entries.remove(entry);
                    String[] kp= key.split(":",2);
                    int chunkId= Integer.parseInt(entry.split(":")[1]);

                    System.out.printf("Lost: %s chunk %d. Initiating recovery.%n", kp[1], chunkId);
                    replicate(kp[0], kp[1], chunkId, socket, peers, entries);
                }

            }
        }
    }

    private static void replicate(String owner, String file, int chunkId,
                                  DatagramSocket socket, HashMap<String, PeerData> peers,
                                  java.util.List<String> existingChunks) {
        
        //Find source peer
        String source = existingChunks.isEmpty() ? null : 
                       existingChunks.get(0).split(":")[0];
        
        //find the new saver
        String target = null;
        for (String p : peers.keySet()) {
            if (!p.equals(owner) && !p.equals(source) && 
                !"OWNER".equals(peers.get(p).getRole())) {
                target = p;
                break;
            }
        }
        
        if (source == null || target == null) {
            System.out.printf("Cannot replicate: source=%s target=%s%n", source, target);
            return;
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
            System.out.printf("  → Replicating %s:%d from %s to %s%n", 
                             file, chunkId, source, target);
        } catch (Exception e) {
            System.err.println("Replication failed: " + e.getMessage());
        }
    }
}
