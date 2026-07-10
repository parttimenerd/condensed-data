package me.bechberger.condensed;

import java.io.*;
import java.nio.file.*;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.zip.CRC32;
import me.bechberger.condensed.RIOException.IntegrityCheckException;

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

    /**
     * Byte offset where the footer record begins (i.e. the length of the start header + compressed
     * main stream). {@link Optional#empty()} if the file has no readable footer.
     */
    public static OptionalLong footerStart(Path path) {
        if (!Files.isRegularFile(path)) {
            return OptionalLong.empty();
        }
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            long len = raf.length();
            if (len < 8) return OptionalLong.empty();
            raf.seek(len - 4);
            long b0 = raf.read(), b1 = raf.read(), b2 = raf.read(), b3 = raf.read();
            if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) return OptionalLong.empty();
            long footerLen = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
            if (footerLen <= 0 || footerLen > len - 4) return OptionalLong.empty();
            // subtract the 1-byte sentinel that precedes the zlib blob
            long footerStart = len - 4 - footerLen - 1;
            if (footerStart < 0) return OptionalLong.empty();
            return OptionalLong.of(footerStart);
        } catch (Exception e) {
            return OptionalLong.empty();
        }
    }

    /**
     * Verify the whole-file CRC32 for a regular file, if it has a footer that carries one.
     *
     * <p>No-op when: the path is not a regular file (stream/ZIP input can't be re-read), there is
     * no footer, or the stored CRC is 0 (footer written before the CRC feature existed). Throws
     * {@link IntegrityCheckException} on mismatch.
     */
    public static void verify(Path path) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        Optional<CJFRFooter> footerOpt = tryRead(path);
        if (footerOpt.isEmpty()) {
            return; // no footer → nothing to verify
        }
        long stored = footerOpt.get().mainStreamCrc32();
        if (stored == 0L) {
            return; // footer predates the CRC feature
        }
        OptionalLong startOpt = footerStart(path);
        if (startOpt.isEmpty()) {
            return;
        }
        long footerStart = startOpt.getAsLong();
        CRC32 crc = new CRC32();
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(path)) {
            long remaining = footerStart;
            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                int read = in.read(buf, 0, toRead);
                if (read < 0) break;
                crc.update(buf, 0, read);
                remaining -= read;
            }
        } catch (IOException e) {
            throw new RIOException("Failed to read file for integrity check: " + path, e);
        }
        long actual = crc.getValue();
        if (actual != stored) {
            throw new IntegrityCheckException(path, stored, actual);
        }
    }
}
