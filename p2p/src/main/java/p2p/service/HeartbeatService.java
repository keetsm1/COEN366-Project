package p2p.service;

import p2p.config.PeerConfig;
import p2p.protocol.MessageCodec;
import p2p.net.UdpControlChannel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

public class HeartbeatService implements AutoCloseable {
    private final PeerConfig cfg;
    private final UdpControlChannel udp;
    private final InetSocketAddress serverAddr;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "heartbeat-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong rqCounter;
    private final IntSupplier chunkCountSupplier;

    public HeartbeatService(PeerConfig cfg, UdpControlChannel udp, AtomicLong rqCounter, IntSupplier chunkCountSupplier) {
        this.cfg = cfg;
        this.udp = udp;
        this.serverAddr = new InetSocketAddress(cfg.serverHost, cfg.serverUdpPort);
        this.rqCounter = rqCounter;
        this.chunkCountSupplier = chunkCountSupplier;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::sendHeartbeatSafely, 0, cfg.heartbeatIntervalSeconds, TimeUnit.SECONDS);
    }

    private void sendHeartbeatSafely() {
        try {
            long rq = rqCounter.incrementAndGet();
            int numChunks = chunkCountSupplier.getAsInt();
            long ts = Instant.now().toEpochMilli();
            String hb = MessageCodec.buildHeartbeat(rq, cfg.name, numChunks, ts);
            udp.send(hb, serverAddr);
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