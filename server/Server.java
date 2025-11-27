package server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import peer.PeerData;

public class Server {
    private static HashMap<String, PeerData> peers;
    private static HashMap<String, java.util.List<String>> backupTable; // {"owner:filename" -> ["peer:chunkID", ...]}
    // Heartbeat tracking: last received time (server clock) and reported chunk counts
    private static java.util.Map<String, Long> lastHeartbeat = new java.util.concurrent.ConcurrentHashMap<>();
    private static java.util.Map<String, Integer> heartbeatChunkCounts = new java.util.concurrent.ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        peers = new HashMap<>();
        backupTable = new HashMap<>();

        ExecutorService workers = Executors.newFixedThreadPool(8);

        try (DatagramSocket ds = new DatagramSocket(1234)) {
            System.out.println("UDP server listening on port 1234...");

            // start heartbeat monitor with failure detection
            FailureDetector.start(ds, peers, backupTable, lastHeartbeat);

            // main receive loop; hand off processing to worker threads
            while (true) {
                byte[] receive = new byte[65535];
                DatagramPacket dpReceive = new DatagramPacket(receive, receive.length);
                ds.receive(dpReceive);

                workers.submit(() -> {
                    String msg = new String(dpReceive.getData(), 0, dpReceive.getLength()).trim();
                    System.out.printf("Server received: '%s' from %s:%d%n", msg,
                            dpReceive.getAddress().getHostAddress(), dpReceive.getPort());
                    try {
                        if ("bye".equalsIgnoreCase(msg)) {
                            
                            System.out.println("Client sent bye. Closing connection.");
                            return;
                        }
                        ServerMessageHandler.handleMessage(
                                msg,
                                ds,
                                dpReceive,
                                peers,
                                backupTable,
                                lastHeartbeat,
                                heartbeatChunkCounts
                        );
                    } catch (Exception ex) {
                        System.err.println("Server worker error: " + ex.getMessage());
                    }
                });
            }
        } catch (SocketException e) {
            System.err.println("Socket error: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
        } finally {
            workers.shutdown();
        }
    }
}
