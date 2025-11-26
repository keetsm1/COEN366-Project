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
        sendRegistration(ds, ip, serverPort, name, "BOTH", tcpPort, 1024);

        
        Thread udpListener = new Thread(() -> {
            try {
                byte[] receiveBuffer = new byte[65535];
                DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                while (!Thread.currentThread().isInterrupted()) {
                    ds.receive(receivePacket);
                    //Dispatch processing to worker pool to keep listener responsive
                    workers.submit(() -> {
                        String msg = new String(receivePacket.getData(), 0, receivePacket.getLength()).trim();
                        System.out.printf("Peer received: '%s' from %s:%d%n", msg,
                                receivePacket.getAddress().getHostAddress(), receivePacket.getPort());
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
                            } else if (msg.startsWith("CHUNK_OK") || msg.startsWith("CHUNK_ERROR") || msg.startsWith("STORE_ACK")) {
                                System.out.println("Server forwarded: " + msg);
                            } else if (msg.startsWith("PEERS ")) {
                                System.out.println("Peer list received");
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
        //Receive server response
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

        //heartbeat integration
        IntSupplier chunkCountSupplier = () -> {
            File storageDir = new File("storage");
            if (storageDir.exists() && storageDir.isDirectory()) {
                String[] files = storageDir.list();
                return files != null ? files.length : 0;
            }
            return 0;
        };
        int heartbeatInterval = 60; // sending heartbeat every 60 secs. used to be 10 but too much spam in console.
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
                PeerBackupRestore.handleBackupRequest(inp, ds, ip, serverPort, knownPeers);
                continue;
            }

            if (inp.toLowerCase().startsWith("restore")) {
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
