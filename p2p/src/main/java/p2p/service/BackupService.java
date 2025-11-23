package p2p.service;

import p2p.config.PeerConfig;
import p2p.net.TcpChunkSender;
import p2p.net.UdpControlChannel;
import p2p.protocol.MessageCodec;
import p2p.protocol.MessageCodec.PeerEndpoint;
import p2p.util.Crc32Util;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class BackupService {
    private final PeerConfig cfg;
    private final UdpControlChannel udp;
    private final InetSocketAddress serverAddr;
    private final AtomicLong rqCounter;
    private final TcpChunkSender tcpSender;

    private final ExecutorService chunkExecutor = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));

    public BackupService(PeerConfig cfg, UdpControlChannel udp, AtomicLong rqCounter) {
        this.cfg = cfg;
        this.udp = udp;
        this.serverAddr = new InetSocketAddress(cfg.serverHost, cfg.serverUdpPort);
        this.rqCounter = rqCounter;
        this.tcpSender = new TcpChunkSender(cfg);
    }

    public void backupFile(Path file) throws IOException, InterruptedException, ExecutionException, TimeoutException {
        String fileName = file.getFileName().toString();
        long fileSize = file.toFile().length();
        long fileChecksum = Crc32Util.crc32(file);
        long rq = rqCounter.incrementAndGet();

        String req = MessageCodec.buildBackupReq(rq, fileName, fileSize, fileChecksum);
        udp.send(req, serverAddr);
        System.out.println("[BACKUP_REQ] sent: " + req);

        MessageCodec.ParsedBackupPlan plan = udp.awaitBackupPlan(Duration.ofSeconds(10));
        if (plan == null) {
            String denied = udp.awaitBackupDenied(Duration.ofSeconds(1));
            if (denied != null) {
                throw new IllegalStateException("Backup denied: " + denied);
            }
            // Fallback: use static peers from config if provided
            if (cfg.backupStaticPeers != null && !cfg.backupStaticPeers.isBlank()) {
                List<PeerEndpoint> endpoints = MessageCodec.parsePeerList(cfg.backupStaticPeers);
                if (endpoints.isEmpty()) {
                    throw new IllegalStateException("No endpoints parsed from backup.staticPeers");
                }
                plan = new MessageCodec.ParsedBackupPlan(rq, fileName, endpoints, cfg.backupChunkSize);
                System.out.println("[BACKUP_PLAN] using local static peers, chunkSize=" + plan.chunkSize);
            } else {
                throw new TimeoutException("Timed out waiting for BACKUP_PLAN and no backup.staticPeers configured");
            }
        }
        System.out.println("[BACKUP_PLAN] received: chunkSize=" + plan.chunkSize + ", peers=" + plan.peerEndpoints.size());

        if (plan.peerEndpoints.isEmpty()) {
            throw new IllegalStateException("No storage peers provided in BACKUP_PLAN");
        }

        // Compute chunk assignments round-robin across provided endpoints
        int chunkSize = plan.chunkSize;
        int numChunks = (int) ((fileSize + chunkSize - 1) / chunkSize);
        List<PeerEndpoint> assignments = new ArrayList<>(numChunks);
        for (int i = 0; i < numChunks; i++) {
            assignments.add(plan.peerEndpoints.get(i % plan.peerEndpoints.size()));
        }

        List<Future<Boolean>> futures = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(file.toFile())) {
            for (int chunkId = 0; chunkId < numChunks; chunkId++) {
                PeerEndpoint target = assignments.get(chunkId);
                int size = (int) Math.min((long) chunkSize, fileSize - ((long) chunkId * chunkSize));
                byte[] buf = new byte[size];
                int totalRead = 0;
                while (totalRead < size) {
                    int n = fis.read(buf, totalRead, size - totalRead);
                    if (n < 0) throw new IOException("Unexpected EOF when reading chunk " + chunkId);
                    totalRead += n;
                }
                long chunkCrc = Crc32Util.crc32(buf);
                long chunkRq = rqCounter.incrementAndGet();
                final long rqX = chunkRq;
                final String fnX = fileName;
                final int idX = chunkId;
                final byte[] dataX = buf;
                final long crcX = chunkCrc;
                final PeerEndpoint targetX = target;
                futures.add(chunkExecutor.submit(() -> sendChunkWithRetry(rqX, fnX, idX, dataX, crcX, targetX)));
            }
        }

        boolean allOk = true;
        for (Future<Boolean> f : futures) {
            allOk &= f.get();
        }

        if (allOk) {
            String done = MessageCodec.buildBackupDone(rq, fileName);
            try {
                udp.send(done, serverAddr);
                System.out.println("[BACKUP_DONE] sent: " + done);
            } catch (IOException ioe) {
                System.err.println("[BACKUP_DONE] failed to send to server: " + ioe.getMessage());
            }
        } else {
            System.err.println("[BACKUP] one or more chunks failed; not sending BACKUP_DONE");
        }
    }

    private boolean sendChunkWithRetry(long chunkRq, String fileName, int chunkId, byte[] data, long checksum, PeerEndpoint target) {
        int attempts = 0;
        while (attempts < 3) {
            attempts++;
            try {
                UdpControlChannel.AckWaiter waiter = udp.registerAckWaiter(chunkRq, fileName, chunkId); // register before sending
                tcpSender.sendChunk(chunkRq, fileName, chunkId, data, checksum, target.tcpSocketAddress());
                System.out.printf("[SEND_CHUNK] rq=%d file=%s id=%d size=%d -> %s:%d (attempt %d)\n",
                        chunkRq, fileName, chunkId, data.length, target.ip, target.tcpPort, attempts);
                UdpControlChannel.AckResult res = waiter.await(Duration.ofSeconds(5));
                boolean ok = res != null && res.ok;
                if (ok) {
                    System.out.printf("[CHUNK_OK] rq=%d file=%s id=%d\n", chunkRq, fileName, chunkId);
                    return true;
                } else {
                    System.err.printf("[CHUNK_ERROR] rq=%d file=%s id=%d; will retry\n", chunkRq, fileName, chunkId);
                }
            } catch (TimeoutException te) {
                System.err.printf("[ACK_TIMEOUT] rq=%d file=%s id=%d; will retry\n", chunkRq, fileName, chunkId);
            } catch (IOException | InterruptedException | ExecutionException e) {
                System.err.printf("[SEND_FAIL] rq=%d file=%s id=%d: %s; will retry\n", chunkRq, fileName, chunkId, e.getMessage());
            }
        }
        System.err.printf("[CHUNK_CANCEL] rq=%d file=%s id=%d after 3 attempts\n", chunkRq, fileName, chunkId);
        return false;
    }
}