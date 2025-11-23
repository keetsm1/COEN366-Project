package p2p.net;

import p2p.protocol.MessageCodec;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;

public class UdpControlChannel implements AutoCloseable {
    private final DatagramSocket socket;
    private final Thread listenerThread;
    private volatile boolean running = true;

    private final BlockingQueue<String> backupPlanQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> backupDeniedQueue = new LinkedBlockingQueue<>();

    private final ConcurrentMap<String, CompletableFuture<AckResult>> ackFutures = new ConcurrentHashMap<>();

    public UdpControlChannel(int localUdpPort) throws SocketException {
        this.socket = new DatagramSocket(localUdpPort);
        this.listenerThread = new Thread(this::listenLoop, "udp-control-listener");
        this.listenerThread.setDaemon(true);
        this.listenerThread.start();
    }

    public void send(String message, InetSocketAddress target) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        DatagramPacket pkt = new DatagramPacket(bytes, bytes.length, target.getAddress(), target.getPort());
        socket.send(pkt);
    }

    private void listenLoop() {
        byte[] buf = new byte[8192];
        while (running) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                socket.receive(pkt);
                String msg = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(), StandardCharsets.UTF_8).trim();
                handleMessage(msg);
            } catch (SocketException se) {
                if (running) se.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    private void handleMessage(String msg) {
        if (msg.isEmpty()) return;
        String[] parts = msg.split(" ");
        String type = parts[0];
        switch (type) {
            case "BACKUP_PLAN":
                backupPlanQueue.offer(msg);
                break;
            case "BACKUP-DENIED":
            case "BACKUP_DENIED":
                backupDeniedQueue.offer(msg);
                break;
            case "CHUNK_OK": {
                // Format: CHUNK_OK RQ# File_Name Chunk_ID
                if (parts.length >= 4) {
                    String key = ackKey(parts[1], parts[2], parts[3]);
                    Optional.ofNullable(ackFutures.get(key))
                            .ifPresent(f -> f.complete(new AckResult(true, msg)));
                }
                break;
            }
            case "CHUNK_ERROR": {
                // Format: CHUNK_ERROR RQ# File_Name Chunk_ID Reason
                if (parts.length >= 4) {
                    String key = ackKey(parts[1], parts[2], parts[3]);
                    Optional.ofNullable(ackFutures.get(key))
                            .ifPresent(f -> f.complete(new AckResult(false, msg)));
                }
                break;
            }
            case "STORE_ACK": {
                // Server confirmation; owner may ignore for now
                break;
            }
            default:
                // ignore or log
                break;
        }
    }

    public MessageCodec.ParsedBackupPlan awaitBackupPlan(Duration timeout) throws InterruptedException {
        String raw = backupPlanQueue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (raw == null) return null;
        return MessageCodec.parseBackupPlan(raw);
    }

    public String awaitBackupDenied(Duration timeout) throws InterruptedException {
        return backupDeniedQueue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public AckWaiter registerAckWaiter(long rq, String fileName, int chunkId) {
        String key = ackKey(Long.toString(rq), fileName, Integer.toString(chunkId));
        CompletableFuture<AckResult> future = new CompletableFuture<>();
        ackFutures.put(key, future);
        return new AckWaiter(key, future, ackFutures);
    }

    private static String ackKey(String rq, String fileName, String chunkId) {
        return rq + "|" + fileName + "|" + chunkId;
    }

    @Override
    public void close() {
        running = false;
        socket.close();
    }

    public static class AckResult {
        public final boolean ok;
        public final String rawMessage;
        public AckResult(boolean ok, String rawMessage) {
            this.ok = ok;
            this.rawMessage = rawMessage;
        }
    }

    public static class AckWaiter {
        private final String key;
        private final CompletableFuture<AckResult> future;
        private final ConcurrentMap<String, CompletableFuture<AckResult>> registry;

        AckWaiter(String key, CompletableFuture<AckResult> future, ConcurrentMap<String, CompletableFuture<AckResult>> registry) {
            this.key = key;
            this.future = future;
            this.registry = registry;
        }

        public AckResult await(Duration timeout) throws TimeoutException, ExecutionException, InterruptedException {
            try {
                AckResult res = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return Objects.requireNonNullElse(res, new AckResult(false, "timeout"));
            } finally {
                registry.remove(key);
            }
        }

        public void cancel() {
            future.cancel(true);
            registry.remove(key);
        }
    }
}