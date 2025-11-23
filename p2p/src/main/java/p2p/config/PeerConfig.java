package p2p.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class PeerConfig {
    public final String name;
    public final String role; // OWNER | STORAGE | BOTH
    public final String peerIp;
    public final int peerUdpPort;
    public final int peerTcpPort;
    public final int storageCapacityMB;
    public final String serverHost;
    public final int serverUdpPort;
    public final int heartbeatIntervalSeconds;
    public final String backupStaticPeers; // optional: [Peer@IP:TCP:UDP;...]
    public final int backupChunkSize; // optional fallback chunk size

    private PeerConfig(String name, String role, String peerIp, int peerUdpPort, int peerTcpPort,
                       int storageCapacityMB, String serverHost, int serverUdpPort,
                       int heartbeatIntervalSeconds, String backupStaticPeers, int backupChunkSize) {
        this.name = name;
        this.role = role;
        this.peerIp = peerIp;
        this.peerUdpPort = peerUdpPort;
        this.peerTcpPort = peerTcpPort;
        this.storageCapacityMB = storageCapacityMB;
        this.serverHost = serverHost;
        this.serverUdpPort = serverUdpPort;
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        this.backupStaticPeers = backupStaticPeers;
        this.backupChunkSize = backupChunkSize;
    }

    public static PeerConfig load(Path propertiesPath) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(propertiesPath)) {
            props.load(in);
        }

        String name = require(props, "peer.name");
        String role = require(props, "peer.role");
        String peerIp = props.getProperty("peer.ip", localIpFallback());
        int peerUdpPort = Integer.parseInt(require(props, "peer.udpPort"));
        int peerTcpPort = Integer.parseInt(require(props, "peer.tcpPort"));
        int storageCapacityMB = Integer.parseInt(props.getProperty("peer.storageMB", "0"));
        String serverHost = require(props, "server.host");
        int serverUdpPort = Integer.parseInt(require(props, "server.udpPort"));
        int heartbeatIntervalSeconds = Integer.parseInt(props.getProperty("heartbeat.intervalSeconds", "10"));
        String backupStaticPeers = props.getProperty("backup.staticPeers", "");
        int backupChunkSize = Integer.parseInt(props.getProperty("backup.chunkSize", "65536"));

        return new PeerConfig(name, role, peerIp, peerUdpPort, peerTcpPort, storageCapacityMB,
                serverHost, serverUdpPort, heartbeatIntervalSeconds, backupStaticPeers, backupChunkSize);
    }

    private static String require(Properties props, String key) {
        String v = props.getProperty(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("Missing required config key: " + key);
        }
        return v;
    }

    private static String localIpFallback() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "127.0.0.1";
        }
    }
}