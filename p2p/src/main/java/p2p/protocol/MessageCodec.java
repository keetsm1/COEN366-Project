package p2p.protocol;

import p2p.config.PeerConfig;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class MessageCodec {
    public enum Type {
        REGISTER,
        REGISTERED,
        REGISTER_DENIED,
        DE_REGISTER,
        BACKUP_REQ,
        BACKUP_PLAN,
        BACKUP_DENIED,
        STORE_REQ,
        SEND_CHUNK,
        CHUNK_OK,
        CHUNK_ERROR,
        BACKUP_DONE,
        STORE_ACK,
        HEARTBEAT,
        RESTORE_REQ,
        RESTORE_PLAN,
        GET_CHUNK,
        CHUNK_DATA,
        RESTORE_OK,
        RESTORE_FAIL,
        REPLICATE_REQ
    }

    public static String buildRegister(long rq, PeerConfig cfg) {
        return String.format("REGISTER %d %s %s %s %d %d %dMB",
                rq, cfg.name, cfg.role, cfg.peerIp, cfg.peerUdpPort, cfg.peerTcpPort, cfg.storageCapacityMB);
    }

    public static String buildDeRegister(long rq, String name) {
        return String.format("DE-REGISTER %d %s", rq, name);
    }

    public static String buildBackupReq(long rq, String fileName, long fileSize, long checksum) {
        return String.format("BACKUP_REQ %d %s %d %d", rq, fileName, fileSize, checksum);
    }

    public static String buildBackupDone(long rq, String fileName) {
        return String.format("BACKUP_DONE %d %s", rq, fileName);
    }

    public static String buildHeartbeat(long rq, String name, int numberChunks, long timestampMillis) {
        return String.format("HEARTBEAT %d %s %d %d", rq, name, numberChunks, timestampMillis);
    }

    public static String buildSendChunkHeader(long rq, String fileName, int chunkId, int chunkSize, long checksum,
                                              String ownerName, int ownerUdpPort) {
        // Extended header includes owner identity and UDP port so storage peers can send acks directly
        return String.format("SEND_CHUNK %d %s %d %d %d %s %d", rq, fileName, chunkId, chunkSize, checksum, ownerName, ownerUdpPort);
    }

    public static ParsedBackupPlan parseBackupPlan(String message) {
        // Expected format (assumption): BACKUP_PLAN RQ# File_Name [PeerName@IP:TCP:UDP;...] Chunk_Size
        String[] parts = message.trim().split(" ");
        if (parts.length < 5 || !"BACKUP_PLAN".equals(parts[0])) {
            throw new IllegalArgumentException("Invalid BACKUP_PLAN: " + message);
        }
        long rq = Long.parseLong(parts[1]);
        String fileName = parts[2];
        String peerListRaw = parts[3];
        int chunkSize = Integer.parseInt(parts[4]);
        List<PeerEndpoint> endpoints = parsePeerList(peerListRaw);
        return new ParsedBackupPlan(rq, fileName, endpoints, chunkSize);
    }

    public static List<PeerEndpoint> parsePeerList(String peerListRaw) {
        // Format: [PeerName@IP:TCP:UDP;PeerB@IP:TCP:UDP]
        String trimmed = peerListRaw.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) return List.of();
        return Arrays.stream(trimmed.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(MessageCodec::parsePeerEndpoint)
                .collect(Collectors.toList());
    }

    public static PeerEndpoint parsePeerEndpoint(String s) {
        // PeerName@IP:TCP:UDP
        String[] nameSplit = s.split("@");
        if (nameSplit.length != 2) throw new IllegalArgumentException("Invalid peer entry: " + s);
        String name = nameSplit[0];
        String[] addrSplit = nameSplit[1].split(":");
        if (addrSplit.length < 2) throw new IllegalArgumentException("Invalid peer address: " + s);
        String ip = addrSplit[0];
        int tcp = Integer.parseInt(addrSplit[1]);
        int udp = addrSplit.length >= 3 ? Integer.parseInt(addrSplit[2]) : -1;
        return new PeerEndpoint(name, ip, tcp, udp);
    }

    public static class ParsedBackupPlan {
        public final long rq;
        public final String fileName;
        public final List<PeerEndpoint> peerEndpoints;
        public final int chunkSize;

        public ParsedBackupPlan(long rq, String fileName, List<PeerEndpoint> peerEndpoints, int chunkSize) {
            this.rq = rq;
            this.fileName = fileName;
            this.peerEndpoints = peerEndpoints;
            this.chunkSize = chunkSize;
        }
    }

    public static class PeerEndpoint {
        public final String name;
        public final String ip;
        public final int tcpPort;
        public final int udpPort; // optional

        public PeerEndpoint(String name, String ip, int tcpPort, int udpPort) {
            this.name = name;
            this.ip = ip;
            this.tcpPort = tcpPort;
            this.udpPort = udpPort;
        }

        public InetSocketAddress tcpSocketAddress() {
            return new InetSocketAddress(ip, tcpPort);
        }

        public InetSocketAddress udpSocketAddress() {
            if (udpPort < 0) throw new IllegalStateException("UDP port not provided for peer " + name);
            return new InetSocketAddress(ip, udpPort);
        }
    }
}