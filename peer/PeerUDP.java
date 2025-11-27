package peer;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

public class PeerUDP{
    private static java.util.Map<String, String> expectedStoreReqs = new java.util.concurrent.ConcurrentHashMap<>(); // "fileName:chunkId" -> "ownerName"
    private static java.util.Map<String, PeerData> knownPeers = new java.util.HashMap<>();

    // RQ# generator: 01..99 then wraps
    private static final AtomicInteger rqCounter = new AtomicInteger(0);
    private static int nextRq() { return (rqCounter.getAndIncrement() % 99) + 1; }

    public static void main(String[] args) throws IOException{
        Scanner sc = new Scanner(System.in);
        DatagramSocket ds = new DatagramSocket();
        InetAddress ip = InetAddress.getLocalHost();
        byte buf[] = null;
        int serverPort = 1234;
        String serverHost = ip.getHostAddress(); // Assuming server is on localhost
        HeartbeatService heartbeatService = null;

        ExecutorService workers = Executors.newFixedThreadPool(6);

        //Create TCP server socket with random port 0 means it will auto assign an available port
        ServerSocket tcpServerSocket = new ServerSocket(0);
        int tcpPort = tcpServerSocket.getLocalPort();
        System.out.println("TCP server will use port: " + tcpPort);

        //Send registration message to server
        System.out.println("Write your name:");
        String name = sc.nextLine().trim();
        
        System.out.println("\nSelect role:");
        System.out.println("1. OWNER (can backup/restore/store:full functionality)");
        System.out.println("2. STORAGE (storage only: cannot backup/restore)");
        System.out.print("Enter choice (1 or 2): ");
        String roleChoice = sc.nextLine().trim();
        
        String role;
        if (roleChoice.equals("1")) {
            role = "OWNER";
        } else if (roleChoice.equals("2")) {
            role = "STORAGE";
        } else {
            System.out.println("Invalid choice, defaulting to OWNER");
            role = "OWNER";
        }
        
        sendRegistration(ds, ip, serverPort, name, role, tcpPort, 1024);

        // Block for initial registration response BEFORE starting async listener
        byte[] receiveBuffer = new byte[65535];
        DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
        ds.receive(receivePacket);
        String response = new String(receivePacket.getData(), 0, receivePacket.getLength()).trim();
        System.out.println("Server response: " + response);

        //Responses you can get from the server
        if (response.startsWith("REGISTER-DENIED")){
            System.out.println("Registration denied by server. Exiting.");
            ds.close();
            sc.close();
            return;
        } else if (response.startsWith("REGISTERED") || response.startsWith("REGISTER-ACCEPTED")) {
            System.out.println("Registration accepted by server.");
        }

        Thread udpListener = new Thread(() -> {
            try {
                byte[] asyncBuf = new byte[65535];
                DatagramPacket asyncPkt = new DatagramPacket(asyncBuf, asyncBuf.length);
                while (!Thread.currentThread().isInterrupted()) {
                    ds.receive(asyncPkt);
                    //Dispatch processing to worker pool to keep listener responsive
                    workers.submit(() -> {
                        String msg = new String(asyncPkt.getData(), 0, asyncPkt.getLength()).trim();
                        // Only print received message for debugging, but skip RESTORE_PLAN to avoid clutter
                        if (msg.startsWith("RESTORE_PLAN ")) {
                            // Print only the RESTORE_PLAN header and file name, not the peer array
                            String[] parts = msg.split(" ", 4);
                            if (parts.length >= 3) {
                                System.out.printf("Peer received: 'RESTORE_PLAN %s %s' from %s:%d%n", parts[1], parts[2],
                                        asyncPkt.getAddress().getHostAddress(), asyncPkt.getPort());
                            } else {
                                System.out.printf("Peer received: 'RESTORE_PLAN' from %s:%d%n",
                                        asyncPkt.getAddress().getHostAddress(), asyncPkt.getPort());
                            }
                        } else {
                            if (!msg.startsWith("CHUNK_OK")) {
                                System.out.printf("Peer received: '%s' from %s:%d%n", msg,
                                        asyncPkt.getAddress().getHostAddress(), asyncPkt.getPort());
                            }
                        }
                        try {
                            if (msg.startsWith("STORE_REQ")) {
                                // STORE_REQ RQ# fileName chunkId ownerName
                                String[] parts = msg.split("\\s+");
                                if (parts.length >= 5) {
                                    String fileName = parts[2];
                                    String chunkId = parts[3];
                                    expectedStoreReqs.put(fileName + ":" + chunkId, parts[4]);
                                    System.out.printf("STORE_REQ recorded for %s:%s (owner=%s)%n", fileName, chunkId, parts[4]);
                                }
                            } else if (msg.startsWith("BACKUP_PLAN")) {
                                ResponseInbox.deposit("BACKUP_PLAN", msg);
                            } else if (msg.startsWith("CHUNK_OK")) {
                                ResponseInbox.deposit("CHUNK_OK", msg);
                            } else if (msg.startsWith("CHUNK_ERROR")) {
                                ResponseInbox.deposit("CHUNK_ERROR", msg);
                            } else if (msg.startsWith("STORE_ACK")) {
                                System.out.println("Server forwarded: " + msg);
                            } else if (msg.startsWith("PEERS ")) {
                                ResponseInbox.deposit("PEERS", msg);
                            } else if (msg.startsWith("RESTORE_PLAN") || msg.startsWith("RESTORE_FAIL")) {
                                ResponseInbox.deposit("RESTORE_PLAN", msg);
                            } else if (msg.startsWith("REPLICATE_REQ")) {
                                // REPLICATE_REQ RQ# fileName chunkId targetName targetIP targetTCP
                                String[] p = msg.split("\\s+");
                                workers.submit(() -> {
                                    try {
                                        String fileName = p[2];
                                        String chunkId = p[3];
                                        String targetName = p[4];
                                        String targetIP = p[5];
                                        int targetTCP = Integer.parseInt(p[6]);
                                        
                                        // Search for chunk in peer-specific storage directory
                                        File chunk = null;
                                        File storageDir = new File("storage_" + name);
                                        
                                        // Try root directory first (old format)
                                        chunk = new File(storageDir, fileName + "." + chunkId + ".part");
                                        
                                        if (!chunk.exists()) {
                                            // Search in owner subdirectories
                                            File[] dirs = storageDir.listFiles(File::isDirectory);
                                            if (dirs != null) {
                                                for (File dir : dirs) {
                                                    // Try old format
                                                    chunk = new File(dir, fileName + "." + chunkId + ".part");
                                                    if (chunk.exists()) break;
                                                    
                                                    // Try new format: O_*_S_*_fileName.chunkId.part
                                                    File[] matches = dir.listFiles((d, n) -> 
                                                        n.endsWith("_" + fileName + "." + chunkId + ".part"));
                                                    if (matches != null && matches.length > 0) {
                                                        chunk = matches[0];
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                        
                                        if (chunk == null || !chunk.exists()) {
                                            System.err.printf("Replication failed: chunk not found %s.%s%n", fileName, chunkId);
                                            return;
                                        }
                                        
                                        byte[] data = java.nio.file.Files.readAllBytes(chunk.toPath());
                                        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                                        crc.update(data);
                                        
                                        // Send chunk via TCP (uses RQ# 99 which bypasses STORE_REQ check)
                                        try (java.net.Socket s = new java.net.Socket(targetIP, targetTCP)) {
                                            s.getOutputStream().write(
                                                String.format("SEND_CHUNK 99 %s %s %d %d\n", fileName, chunkId, data.length, crc.getValue()).getBytes());
                                            s.getOutputStream().write(data);
                                            System.out.printf("✓ Replicated chunk %s:%s to %s%n", fileName, chunkId, targetName);
                                        }
                                    } catch (Exception e) {
                                        System.err.println("Replication error: " + e.getMessage());
                                    }
                                });
                            }
                        } catch (Exception ex) {
                            System.err.println("Peer message handle error: " + ex.getMessage());
                        }
                    });
                }
            } catch (IOException ioe) {
                System.out.println("UDP listener stopped: " + ioe.getMessage());
            }
        }, "peer-udp-listener");
        udpListener.setDaemon(true);
        udpListener.start();

        //heartbeat integration
        IntSupplier chunkCountSupplier = () -> {
            File storageDir = new File("storage_" + name);
            if (storageDir.exists() && storageDir.isDirectory()) {
                String[] files = storageDir.list();
                return files != null ? files.length : 0;
            }
            return 0; 
        };
        int heartbeatInterval = 30; // sending heartbeat every 10 secs
        heartbeatService = new HeartbeatService(name, ds, serverHost, serverPort, heartbeatInterval, rqCounter, chunkCountSupplier);
        heartbeatService.start();
        System.out.println("Heartbeat service started.");
        System.out.println("Write messages to send to server (type 'bye' to exit):");
        System.out.println("Type 'de' to deregister.");
        System.out.println("Type 'list' to see registered peers.");
        System.out.println("Type 'backup filename' to request backup plan and send chunk.");
        System.out.println("Type 'restore filename' to restore a file.");

        //Start TCP chunk server to receive SEND_CHUNK frames if this peer is chosen as storage
        TcpChunkServer.startTcpChunkServer(tcpServerSocket, ds, ip, serverPort, name, expectedStoreReqs);

        while(true){
            if (!sc.hasNextLine()) {
                System.out.println("Input stream closed. Exiting.");
                break;
            }
            String inp = sc.nextLine();

            if (inp.equalsIgnoreCase("de")){
                //send de-registration and exit
                sendDeregistration(ds, ip, serverPort, name);
                break;
            }

            if (inp.equalsIgnoreCase("list")) {
                PeerBackupRestore.handleListRequest(ds, ip, serverPort, knownPeers);
                continue;
            }

            if(inp.toLowerCase().startsWith("backup")){
                if ("STORAGE".equals(role)) {
                    System.out.println("ERROR: Storage-only nodes cannot backup files. Choose OWNER role.");
                    continue;
                }
                PeerBackupRestore.handleBackupRequest(inp, ds, ip, serverPort, knownPeers);
                continue;
            }

            if (inp.toLowerCase().startsWith("restore")) {
                if ("STORAGE".equals(role)) {
                    System.out.println("ERROR: Storage-only nodes cannot restore files. Choose OWNER role.");
                    continue;
                }
                // Set peer-specific restore directory
                System.setProperty("peer.restore.dir", "restored_" + name);
                PeerBackupRestore.handleRestoreRequest(inp, ds, ip, serverPort, knownPeers);
                continue;
            }

            if (inp.equalsIgnoreCase("bye")) break;

            buf = inp.getBytes();
            DatagramPacket DpSend = new DatagramPacket(buf, buf.length, ip, serverPort);
            ds.send(DpSend);
        }

        sc.close();
        ds.close();
        if (heartbeatService != null) {
            heartbeatService.close();
            System.out.println("Heartbeat service stopped.");
        }
    }

    public static String formatRegistration(String name, String role, InetAddress ipAddress, int udpPort, int tcpPort,
                                            long storageCapacity) {
        return String.format("REGISTER %d %s %s %s %d %d %dMB",
                nextRq(), name, role, ipAddress.getHostAddress(),
                udpPort, tcpPort, storageCapacity);
    }

    public static String formatDeregistration(String name) {
        return String.format("DE-REGISTER %d %s", nextRq(), name);
    }

    public static void sendRegistration(DatagramSocket socket, InetAddress serverAddr, int serverPort,
                                        String name, String role, int tcpPort, long storageCapacity) throws IOException {

        int udpPort = socket.getLocalPort();
        String msg = formatRegistration(name, role, InetAddress.getLocalHost(), udpPort, tcpPort, storageCapacity);
        byte[] data = msg.getBytes();
        DatagramPacket packet = new DatagramPacket(data, data.length, serverAddr, serverPort);
        socket.send(packet);
        System.out.println("Sent: " + msg );
    }

    public static void sendDeregistration(DatagramSocket socket, InetAddress serverAddr, int serverPort,
                                          String name) throws IOException {
        String msg = formatDeregistration(name);
        byte[] data = msg.getBytes();
        DatagramPacket packet = new DatagramPacket(data, data.length, serverAddr, serverPort);
        socket.send(packet);
        System.out.println("Sent: " + msg );

        byte[] buf = new byte[1024];
        DatagramPacket resp = new DatagramPacket(buf, buf.length);
        socket.receive(resp);
        String r = new String(resp.getData(), 0, resp.getLength()).trim();
        System.out.println("Server response: " + r);
    }
}
