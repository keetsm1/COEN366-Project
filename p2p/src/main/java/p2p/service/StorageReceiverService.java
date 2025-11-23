package p2p.service;

import p2p.config.PeerConfig;
import p2p.net.UdpControlChannel;
import p2p.util.Crc32Util;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StorageReceiverService implements AutoCloseable {
    private final PeerConfig cfg;
    private final UdpControlChannel udp;
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tcp-receiver-worker");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = false;
    private ServerSocket serverSocket;

    public StorageReceiverService(PeerConfig cfg, UdpControlChannel udp) {
        this.cfg = cfg;
        this.udp = udp;
    }

    public void start() throws IOException {
        if (running) return;
        running = true;
        serverSocket = new ServerSocket(cfg.peerTcpPort);
        Thread t = new Thread(this::acceptLoop, "tcp-receiver");
        t.setDaemon(true);
        t.start();
        System.out.println("[STORAGE] TCP receiver listening on port " + cfg.peerTcpPort);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                workers.submit(() -> handleClient(s));
            } catch (IOException e) {
                if (running) {
                    System.err.println("[STORAGE] accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        try (socket) {
            InputStream in = socket.getInputStream();
            BufferedInputStream bin = new BufferedInputStream(in);
            String header = readLine(bin);
            if (header == null || header.isEmpty()) {
                System.err.println("[STORAGE] empty header from " + socket.getRemoteSocketAddress());
                return;
            }
            // Expected: SEND_CHUNK rq fileName chunkId chunkSize checksum ownerName ownerUdpPort
            String[] parts = header.trim().split(" ");
            if (parts.length < 6 || !"SEND_CHUNK".equals(parts[0])) {
                System.err.println("[STORAGE] invalid header: " + header);
                return;
            }
            long rq = Long.parseLong(parts[1]);
            String fileName = parts[2];
            int chunkId = Integer.parseInt(parts[3]);
            int chunkSize = Integer.parseInt(parts[4]);
            long checksum = Long.parseLong(parts[5]);
            String ownerName = parts.length >= 7 ? parts[6] : "";
            int ownerUdpPort = parts.length >= 8 ? Integer.parseInt(parts[7]) : -1;

            byte[] buf = readExact(bin, chunkSize);
            if (buf == null) {
                System.err.println("[STORAGE] payload read failed for " + fileName + ":" + chunkId);
                sendAck(false, socket, rq, fileName, chunkId, ownerUdpPort);
                return;
            }

            long actualCrc = Crc32Util.crc32(buf);
            boolean ok = (actualCrc == checksum);
            if (ok) {
                saveChunk(fileName, chunkId, buf);
            } else {
                System.err.printf("[STORAGE] checksum mismatch for %s:%d (expected %d, got %d)\n",
                        fileName, chunkId, checksum, actualCrc);
            }
            sendAck(ok, socket, rq, fileName, chunkId, ownerUdpPort);
        } catch (Exception e) {
            System.err.println("[STORAGE] handleClient error: " + e.getMessage());
        }
    }

    private void saveChunk(String fileName, int chunkId, byte[] data) throws IOException {
        Path dir = Paths.get("storage", fileName);
        Files.createDirectories(dir);
        Path file = dir.resolve("chunk-" + chunkId + ".bin");
        Files.write(file, data);
        System.out.printf("[STORAGE] saved %s chunk %d (%d bytes)\n", fileName, chunkId, data.length);
    }

    private void sendAck(boolean ok, Socket socket, long rq, String fileName, int chunkId, int ownerUdpPort) throws IOException {
        InetSocketAddress ownerAddr;
        if (ownerUdpPort > 0) {
            ownerAddr = new InetSocketAddress(socket.getInetAddress(), ownerUdpPort);
        } else {
            // Fallback to server (less ideal); if used, server may ignore. Kept for robustness.
            ownerAddr = new InetSocketAddress(cfg.serverHost, cfg.serverUdpPort);
        }
        String msg = (ok ? "CHUNK_OK" : "CHUNK_ERROR") + " " + rq + " " + fileName + " " + chunkId;
        udp.send(msg, ownerAddr);
        System.out.printf("[STORAGE] ack %s -> %s:%d for rq=%d %s:%d\n",
                ok ? "OK" : "ERROR", ownerAddr.getHostString(), ownerAddr.getPort(), rq, fileName, chunkId);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            baos.write(b);
        }
        if (baos.size() == 0 && b == -1) return null;
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static byte[] readExact(InputStream in, int size) throws IOException {
        byte[] buf = new byte[size];
        int off = 0;
        while (off < size) {
            int n = in.read(buf, off, size - off);
            if (n < 0) return null;
            off += n;
        }
        return buf;
    }

    @Override
    public void close() throws Exception {
        running = false;
        if (serverSocket != null) serverSocket.close();
        workers.shutdownNow();
    }
}