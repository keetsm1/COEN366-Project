package src.peer;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

public class HeartbeatService implements AutoCloseable {
    private final String name;
    private final DatagramSocket udpSocket;
    private final InetSocketAddress serverAddr;
    private final int heartbeatIntervalSeconds;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "heartbeat-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger rqCounter;
    private final IntSupplier chunkCountSupplier;

    public HeartbeatService(String name, DatagramSocket udpSocket, String serverHost, int serverUdpPort, int heartbeatIntervalSeconds, AtomicInteger rqCounter, IntSupplier chunkCountSupplier) {
        this.name = name;
        this.udpSocket = udpSocket;
        this.serverAddr = new InetSocketAddress(serverHost, serverUdpPort);
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        this.rqCounter = rqCounter;
        this.chunkCountSupplier = chunkCountSupplier;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::sendHeartbeatSafely, 0, heartbeatIntervalSeconds, TimeUnit.SECONDS);
    }

    private void sendHeartbeatSafely() {
        try {
            int rq = rqCounter.incrementAndGet();
            int numChunks = chunkCountSupplier.getAsInt();
            long ts = Instant.now().toEpochMilli();
            String hb = String.format("HEARTBEAT %d %s %d %d", rq, name, numChunks, ts);
            byte[] data = hb.getBytes();
            udpSocket.send(new DatagramPacket(data, data.length, serverAddr));
            System.out.println("[HEARTBEAT] sent: " + hb);
        } catch (IOException e) {
            System.err.println("[HEARTBEAT] failed: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}