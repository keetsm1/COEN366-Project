package p2p;

import p2p.config.PeerConfig;
import p2p.net.UdpControlChannel;
import p2p.protocol.MessageCodec;
import p2p.service.BackupService;
import p2p.service.HeartbeatService;
import p2p.service.StorageReceiverService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public class PeerMain {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: java p2p.PeerMain <command> [args]\n" +
                    "Commands:\n" +
                    "  register                 Register with server\n" +
                    "  heartbeat                Start heartbeat loop\n" +
                    "  backup <filePath>        Backup file to peers");
            return;
        }

        String configPath = System.getProperty("config", "config.properties");
        PeerConfig cfg = PeerConfig.load(Paths.get(configPath));
        AtomicLong rqCounter = new AtomicLong(System.currentTimeMillis());
        try (UdpControlChannel udp = new UdpControlChannel(cfg.peerUdpPort)) {
            InetSocketAddress serverAddr = new InetSocketAddress(cfg.serverHost, cfg.serverUdpPort);
            String cmd = args[0];

            StorageReceiverService storage = null;
            boolean isStorageRole = cfg.role.equalsIgnoreCase("STORAGE") || cfg.role.equalsIgnoreCase("BOTH");

            switch (cmd) {
                case "register": {
                    long rq = rqCounter.incrementAndGet();
                    String reg = MessageCodec.buildRegister(rq, cfg);
                    udp.send(reg, serverAddr);
                    System.out.println("[REGISTER] sent: " + reg);
                    break;
                }
                case "heartbeat": {
                    long rq = rqCounter.incrementAndGet();
                    String reg = MessageCodec.buildRegister(rq, cfg);
                    udp.send(reg, serverAddr);
                    System.out.println("[REGISTER] sent: " + reg);
                    HeartbeatService hb = new HeartbeatService(cfg, udp, rqCounter, () -> 0);
                    hb.start();
                    if (isStorageRole) {
                        storage = new StorageReceiverService(cfg, udp);
                        storage.start();
                    }
                    System.out.println("[HEARTBEAT] running every " + cfg.heartbeatIntervalSeconds + "s");
                    Thread.currentThread().join();
                    break;
                }
                case "backup": {
                    if (args.length < 2) {
                        System.err.println("backup requires <filePath>");
                        System.exit(2);
                    }
                    Path file = Paths.get(args[1]);
                    long rq = rqCounter.incrementAndGet();
                    String reg = MessageCodec.buildRegister(rq, cfg);
                    udp.send(reg, serverAddr);
                    System.out.println("[REGISTER] sent: " + reg);

                    HeartbeatService hb = new HeartbeatService(cfg, udp, rqCounter, () -> 0);
                    hb.start();
                    if (isStorageRole) {
                        storage = new StorageReceiverService(cfg, udp);
                        storage.start();
                    }
                    BackupService backup = new BackupService(cfg, udp, rqCounter);
                    try {
                        backup.backupFile(file);
                    } catch (IOException | InterruptedException | ExecutionException | TimeoutException e) {
                        System.err.println("[BACKUP] failed: " + e.getMessage());
                        System.exit(1);
                    } finally {
                        try {
                            hb.close();
                        } catch (Exception ignored) {}
                        if (storage != null) {
                            try {
                                storage.close();
                            } catch (Exception ignored) {}
                        }
                    }
                    break;
                }
                default:
                    System.err.println("Unknown command: " + cmd);
                    System.exit(2);
            }
        }
    }
}