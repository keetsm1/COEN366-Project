package src.peer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.zip.CRC32;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;

public class PeerUDP{
    private static java.util.Map<String, String> expectedStoreReqs = new java.util.concurrent.ConcurrentHashMap<>(); // "fileName:chunkId" -> "ownerName"
    private static java.util.Map<String, PeerData> knownPeers = new java.util.HashMap<>();
    
    public static void main(String[] args) throws IOException{
        Scanner sc = new Scanner(System.in);
        DatagramSocket ds = new DatagramSocket();
        InetAddress ip = InetAddress.getLocalHost();
        byte buf[] = null;
        int serverPort = 1234;
        String serverHost = ip.getHostAddress(); // Assuming server is on localhost
        HeartbeatService heartbeatService = null;

        //Create TCP server socket with random port 0 means it will auto assign an available port
        ServerSocket tcpServerSocket = new ServerSocket(0);
        int tcpPort = tcpServerSocket.getLocalPort();
        System.out.println("TCP server will use port: " + tcpPort);

        //Send registration message to server
        System.out.println("Write your name:");
        String name = sc.nextLine().trim();
        sendRegistration(ds, ip, serverPort, name, "BOTH", tcpPort, 1024);
        
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
        startTcpChunkServer(tcpServerSocket, ds, ip, serverPort, name);

        while(true){
            String inp = sc.nextLine();

            if (inp.equalsIgnoreCase("de")){
                //send de-registration and exit
                sendDeregistration(ds, ip, serverPort, name);
                break;
            }

            if (inp.equalsIgnoreCase("list")) {
                byte[] data = "LIST".getBytes();
                DatagramPacket pkt = new DatagramPacket(data, data.length, ip, serverPort);
                ds.send(pkt);
                byte[] bufList = new byte[4096];
                DatagramPacket resp = new DatagramPacket(bufList, bufList.length);
                ds.receive(resp);
                String listResp = new String(resp.getData(), 0, resp.getLength()).trim();
                System.out.println("Server: " + listResp);
                
                //Parse and store peer info: PEERS count name ip udp tcp ...
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
                            knownPeers.put(pName, new PeerData(pName, "UNKNOWN", InetAddress.getByName(pIp), pUdp, pTcp, "0"));
                        } catch (Exception e) {}
                    }
                }
                continue;
            }

            if(inp.toLowerCase().startsWith("backup")){
                File f = new File(inp.substring(7).trim());
                if (!f.exists() || !f.isFile()) {
                    System.out.println("File does not exist.");
                    continue;
                }

                long size = f.length();
                long sum = crc32file(f);
                String req = formatBackupReq(f.getName(), size, sum);
                byte[] data = req.getBytes();
                ds.send(new DatagramPacket(data, data.length, ip, serverPort));

                byte[] b = new byte[2048];
                DatagramPacket resp = new DatagramPacket(b, b.length);
                ds.receive(resp);
                String plan = new String (resp.getData(), 0, resp.getLength()).trim();
                System.out.println("Server response: " + plan);

                if (!plan.startsWith("BACKUP_PLAN")) {
                    continue;
                }
                //Format: BACKUP_PLAN RQ# File_Name [PeerB, PeerC] Chunk_Size
                String[] p = plan.split("\\s+");

                if (p.length < 5) { System.out.println("Malformed BACKUP_PLAN"); continue; }

                String fileName = p[2];
                String peerListToken = p[3];
                int chunkSize = safeInt(p[4]);
                
                //peer list: [PeerB, PeerC] for example
                String peerListContent = peerListToken.substring(1, peerListToken.length()-1); // remove [ ]
                String[] peerNames = peerListContent.split(",\\s*");
                
                if (peerNames.length == 0) { System.out.println("No peers in BACKUP_PLAN"); continue; }
                
                String targetPeerName = peerNames[0];
                
                //look for peers info from knownPeers registry
                PeerData targetPeer = knownPeers.get(targetPeerName);
                if (targetPeer == null) {
                    byte[] listData = "LIST".getBytes();
                    ds.send(new DatagramPacket(listData, listData.length, ip, serverPort));
                    byte[] listBuf = new byte[4096];
                    DatagramPacket listResp = new DatagramPacket(listBuf, listBuf.length);
                    ds.receive(listResp);
                    String listResult = new String(listResp.getData(), 0, listResp.getLength()).trim();
                    
                    String[] listParts = listResult.split("\\s+");
                    if (listParts.length > 1 && "PEERS".equals(listParts[0])) {
                        int count = safeInt(listParts[1]);
                        int idx = 2;
                        for (int i = 0; i < count && idx + 3 < listParts.length; i++) {
                            String pName = listParts[idx++];
                            String pIp = listParts[idx++];
                            int pUdp = safeInt(listParts[idx++]);
                            int pTcp = safeInt(listParts[idx++]);
                            try {
                                knownPeers.put(pName, new PeerData(pName, "UNKNOWN", InetAddress.getByName(pIp), pUdp, pTcp, "0"));
                            } catch (Exception e) {}
                        }
                    }
                    
                    // Retry lookup
                    targetPeer = knownPeers.get(targetPeerName);
                    if (targetPeer == null) {
                        System.out.println("Peer " + targetPeerName + " still not found after fetching list.");
                        continue;
                    }
                }
                
                String targetIp = targetPeer.getIp().getHostAddress();
                int targetTcp = targetPeer.getTcpPort();

                int chunkTransferSize = (int)Math.min(size, chunkSize);
                System.out.printf("Sending chunk to storage peer %s at %s:%d (chunkSize=%d of fileSize=%d)\n", targetPeerName,
                 targetIp, targetTcp, chunkTransferSize, size);

                //Single chunk SEND_CHUNK
                try (Socket sock = new Socket(targetIp, targetTcp);
                     OutputStream out = sock.getOutputStream();
                     FileInputStream fis = new FileInputStream(f)) {
                    int rqSend = nextRq();
                    String header = String.format("SEND_CHUNK %02d %s %d %d %d\n", rqSend, fileName, 0, chunkTransferSize, sum);
                    out.write(header.getBytes());

                    //send only chunkTransferSize bytes
                    byte[] sendBuf = new byte[8192];
                    int remaining = chunkTransferSize;

                    while (remaining > 0) {
                        int n = fis.read(sendBuf, 0, Math.min(sendBuf.length, remaining));
                        if (n == -1) break;
                        out.write(sendBuf, 0, n);
                        remaining -= n;
                    }
                    out.flush();
                    System.out.println("Chunk sent. Waiting for CHUNK_OK");
                } catch (IOException e) {
                    System.err.println("Chunk send failed: " + e.getMessage());
                }
                //Wait short time for CHUNK_OK or CHUNK_ERROR 
                boolean chunkOk = false;
                try {
                    ds.setSoTimeout(2000);
                    byte[] ackBuf = new byte[512];
                    DatagramPacket ackPkt = new DatagramPacket(ackBuf, ackBuf.length);
                    ds.receive(ackPkt);
                    String ack = new String(ackPkt.getData(), 0, ackPkt.getLength()).trim();
                    System.out.println("Ack: " + ack);
                    if (ack.startsWith("CHUNK_OK")) {
                        chunkOk = true;
                    }
                } catch (Exception timeout) {
                    System.out.println("No CHUNK_OK received within timeout (continuing)." );
                } finally {
                    ds.setSoTimeout(0);
                }
                
                //If all chunks successful send a backup_done
                if (chunkOk) {
                    int rqDone = nextRq();
                    String backupDone = String.format("BACKUP_DONE %02d %s", rqDone, f.getName());
                    byte[] doneData = backupDone.getBytes();
                    ds.send(new DatagramPacket(doneData, doneData.length, ip, serverPort));
                    System.out.println("Sent BACKUP_DONE to server");
                }
                continue;
            }

            if (inp.toLowerCase().startsWith("restore")) {
            String fileName = inp.substring(7).trim();
            if (fileName.isEmpty()) {
                            System.out.println("Usage: restore <filename>");
                            continue;
                        }
                        // === SEND RESTORE_REQ ===
                        int rq = nextRq();
                        String req = String.format("RESTORE_REQ %02d %s", rq, fileName);
                        byte[] reqData = req.getBytes();
                        ds.send(new DatagramPacket(reqData, reqData.length, ip, serverPort));

                        // === WAIT FOR RESTORE_PLAN / RESTORE_FAIL ===
                        byte[] restorebuf = new byte[2048];
                        DatagramPacket resp = new DatagramPacket(restorebuf, restorebuf.length);
                        ds.receive(resp);
                        String respMsg = new String(resp.getData(), 0, resp.getLength()).trim();
                        System.out.println("Server: " + respMsg);

                        if (respMsg.startsWith("RESTORE_FAIL")) {
                            continue;
                        }
                        if (!respMsg.startsWith("RESTORE_PLAN")) {
                            System.out.println("Unexpected response from server.");
                            continue;
                        }

                        // === PARSE PLAN ===
                        String[] parts = respMsg.split("\\s+");
                        String planFile = parts[2];
                        String rawList = parts[3];        // ex: [PeerB]
                        String inner = rawList.substring(1, rawList.length() - 1);
                        String[] peerNames = inner.isEmpty() ? new String[0] : inner.split(",");

                        if (peerNames.length == 0) {
                            System.out.println("No storage peers found in plan.");
                            continue;
                        }

                        String storagePeer = peerNames[0];

                        // === GET CHUNK VIA TCP ===
                        PeerData target = knownPeers.get(storagePeer);
                        if (target == null) {
                            System.out.println("Cannot find storage peer. Try 'list'.");
                            continue;
                        }

                        try (Socket sock = new Socket(target.getIp(), target.getTcpPort())) {
                            InputStream in = sock.getInputStream();
                            OutputStream out = sock.getOutputStream();

                            int rqGet = nextRq();
                            String header = String.format("GET_CHUNK %02d %s %d\n", rqGet, planFile, 0);
                            out.write(header.getBytes());
                            out.flush();

                            // === READ CHUNK_DATA HEADER ===
                            StringBuilder line = new StringBuilder();
                            int c;
                            while ((c = in.read()) != -1 && c != '\n') line.append((char)c);
                            String h = line.toString().trim();
                            String[] hh = h.split("\\s+");
                            int chunkSize = Integer.parseInt(hh[4]);
                            long checksum = Long.parseLong(hh[5]);

                            File outDir = new File("restored");
                            outDir.mkdirs();
                            File outFile = new File(outDir, planFile);

                            CRC32 crc = new CRC32();
                            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                byte[] bb = new byte[8192];
                                int remaining = chunkSize;
                                while (remaining > 0) {
                                    int n = in.read(bb, 0, Math.min(bb.length, remaining));
                                    if (n == -1) break;
                                    fos.write(bb, 0, n);
                                    crc.update(bb, 0, n);
                                    remaining -= n;
                                }
                            }

                            long calc = crc.getValue();
                            boolean ok = (calc == checksum);
                            System.out.printf("Restore checksum match? %s (expected=%d actual=%d)%n", ok, checksum, calc);
                            if (ok) {
                                System.out.printf("RESTORE SUCCESS:", planFile);
                            } else {
                                System.out.printf("RESTORE FAILURE:", planFile);
                            }

                            // === REPORT TO SERVER ===
                            int rqReport = nextRq();
                            String rep = ok
                                    ? String.format("RESTORE_OK %02d %s", rqReport, planFile)
                                    : String.format("RESTORE_FAIL %02d %s ChecksumMismatch", rqReport, planFile);
                            ds.send(new DatagramPacket(rep.getBytes(), rep.length(), ip, serverPort));
                        }
                        catch (Exception e){
                            System.out.println("Restore error: " + e.getMessage());
                        }

                        continue;
                    }

            if (inp.equalsIgnoreCase("bye")) {
                break;
            }

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

	// RQ# generator: 01..99 then wraps
    private static final AtomicInteger rqCounter = new AtomicInteger(0);

    private static int nextRq() {
        return (rqCounter.getAndIncrement() % 99) + 1; // 1..99
    }

	public static String formatRegistration(String name, String role, InetAddress ipAddress, int udpPort, int tcpPort,
			long storageCapacity) {

        return String.format("REGISTER %d %s %s %s %d %d %dMB", nextRq(), name, role, ipAddress.getHostAddress(),
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
        // Format: DE-REGISTER RQ# Name
        String msg = formatDeregistration(name);
        byte[] data = msg.getBytes();
        DatagramPacket packet = new DatagramPacket(data, data.length, serverAddr, serverPort);
        socket.send(packet);
        System.out.println("Sent: " + msg );

        // Wait for server response
        byte[] buf = new byte[1024];
        DatagramPacket resp = new DatagramPacket(buf, buf.length);
        socket.receive(resp);
        String r = new String(resp.getData(), 0, resp.getLength()).trim();
        System.out.println("Server response: " + r);
    }

    public static String formatBackupReq(String fileName, long fileSize, long checksum) {
        return String.format("BACKUP_REQ %02d %s %d %d", nextRq(), fileName, fileSize, checksum);
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

    private static int safeInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    //TCP server to receive SEND_CHUNK frames
    //this method accepts a tcp connection, reads the header, and extracts the following info
    //fileName, chunkId, chunkSize, checksum

private static void startTcpChunkServer(ServerSocket ss, DatagramSocket udpSocket,
                                        InetAddress serverAddr, int serverPort, String selfName) {
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
                        long checksum = 0L;
                        try { checksum = Long.parseLong(h[5]); } catch (Exception ignore) {}

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
                            System.out.printf("Sent STORE_ACK to server: file=%s chunk=%d%n", fileName, chunkId);
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
                            // (you could send an error header here if you want)
                            continue;
                        }

                        long chunkSize = inFile.length();
                        CRC32 crc = new CRC32();
                        byte[] bufLocal = new byte[8192];

                        // compute checksum
                        try (FileInputStream fis = new FileInputStream(inFile)) {
                            int n;
                            while ((n = fis.read(bufLocal)) != -1) {
                                crc.update(bufLocal, 0, n);
                            }
                        }
                        long checksum = crc.getValue();

                        // send CHUNK_DATA header
                        String dataHeader = String.format("CHUNK_DATA %02d %s %d %d %d\n",
                                rq, fileName, chunkId, chunkSize, checksum);
                        out.write(dataHeader.getBytes());
                        out.flush();

                        // send file bytes
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
}

