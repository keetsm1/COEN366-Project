package p2p.util;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.zip.CRC32;

public class Crc32Util {
    public static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        return crc.getValue();
    }

    public static long crc32(Path file) throws IOException {
        CRC32 crc = new CRC32();
        try (FileInputStream fis = new FileInputStream(file.toFile())) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = fis.read(buf)) != -1) {
                crc.update(buf, 0, n);
            }
        }
        return crc.getValue();
    }

    public static long crc32(ByteBuffer buffer, int length) {
        CRC32 crc = new CRC32();
        int remaining = Math.min(length, buffer.remaining());
        byte[] tmp = new byte[remaining];
        buffer.get(tmp);
        crc.update(tmp, 0, tmp.length);
        return crc.getValue();
    }
}