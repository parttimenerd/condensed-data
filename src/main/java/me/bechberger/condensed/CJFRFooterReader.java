package me.bechberger.condensed;

import java.io.*;
import java.nio.file.*;
import java.util.Optional;

/** Reads the precomputed {@link CJFRFooter} from the end of a {@code .cjfr} file in O(1). */
public class CJFRFooterReader {

    /**
     * Try to read the footer from {@code path}.
     *
     * <p>Returns {@link Optional#empty()} if:
     *
     * <ul>
     *   <li>the file has no footer (old format),
     *   <li>the footer bytes are corrupt or have wrong magic/version,
     *   <li>the path is not a regular file (directory, ZIP, etc.).
     * </ul>
     */
    public static Optional<CJFRFooter> tryRead(Path path) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            long len = raf.length();
            if (len < 8) return Optional.empty();

            // Read little-endian uint32 footer length from last 4 bytes
            raf.seek(len - 4);
            long b0 = raf.read(), b1 = raf.read(), b2 = raf.read(), b3 = raf.read();
            if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) return Optional.empty();
            long footerLen = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);

            if (footerLen <= 0 || footerLen > len - 4) return Optional.empty();

            long footerStart = len - 4 - footerLen;
            raf.seek(footerStart);
            byte[] zlibBytes = new byte[(int) footerLen];
            raf.readFully(zlibBytes);

            CJFRFooter footer = CJFRFooter.fromCompressedBytes(zlibBytes);
            return Optional.ofNullable(footer);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
