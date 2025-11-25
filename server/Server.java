package server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.HashMap;

import peer.PeerData;

public class Server {
    private static HashMap<String, PeerData> peers;
    private static HashMap<String, java.util.List<String>> backupTable; // {"owner:filename" -> ["peer:chunkID", ...]}
    // Heartbeat tracking: last received time (server clock) and reported chunk counts
    private static java.util.Map<String, Long> lastHeartbeat = new java.util.concurrent.ConcurrentHashMap<>();
    private static java.util.Map<String, Integer> heartbeatChunkCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long HEARTBEAT_TIMEOUT_MS = 60000; // 60s timeout

    public static void main(String[] args) throws IOException {
        peers = new HashMap<>();
        backupTable = new HashMap<>();

        try (DatagramSocket ds = new DatagramSocket(1234)) {
            System.out.println("UDP server listening on port 1234...");

            //start heartbeat monitor thread (simple polling loop)
            new Thread(() -> {
                while (true) {
                    try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }
                    long now = System.currentTimeMillis();
                    for (String name : peers.keySet()) {
                        Long last = lastHeartbeat.get(name);
                        if (last != null && now - last > HEARTBEAT_TIMEOUT_MS) {
                            System.out.printf("[HEARTBEAT] Peer '%s' timed out (last=%dms ago). Trigger recovery logic here.%n", name, (now - last));
                            // Placeholder: recovery / replication logic would be invoked here.
                        }
                    }
                }
            }, "heartbeat-monitor").start();

            while (true) {
                byte[] receive = new byte[65535];
                DatagramPacket dpReceive = new DatagramPacket(receive, receive.length);
                ds.receive(dpReceive);
                String msg = new String(dpReceive.getData(), 0, dpReceive.getLength()).trim();
                System.out.printf("Server received: '%s' from %s:%d%n", msg, dpReceive.getAddress().getHostAddress(), dpReceive.getPort());
                if ("bye".equalsIgnoreCase(msg)) {
                    System.out.println("Client sent bye.....EXITING");
                    break;
                } else {
                    // delegate parsing + protocol logic to handler class
                    ServerMessageHandler.handleMessage(
                            msg,
                            ds,
                            dpReceive,
                            peers,
                            backupTable,
                            lastHeartbeat,
                            heartbeatChunkCounts
                    );
                }
            }
            ds.close();
        } catch (SocketException e) {
            System.err.println("Socket error: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
        }
    }
}
