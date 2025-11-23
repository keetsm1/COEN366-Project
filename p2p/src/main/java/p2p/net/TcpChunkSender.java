package p2p.net;

import p2p.config.PeerConfig;
import p2p.protocol.MessageCodec;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class TcpChunkSender {
    private final PeerConfig cfg;

    public TcpChunkSender(PeerConfig cfg) {
        this.cfg = cfg;
    }

    public void sendChunk(long rq, String fileName, int chunkId, byte[] chunkData, long checksum,
                          InetSocketAddress target) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(target);
            OutputStream out = socket.getOutputStream();
            String header = MessageCodec.buildSendChunkHeader(
                    rq, fileName, chunkId, chunkData.length, checksum, cfg.name, cfg.peerUdpPort) + "\n";
            out.write(header.getBytes(StandardCharsets.UTF_8));
            out.write(chunkData);
            out.flush();
        }
    }
}