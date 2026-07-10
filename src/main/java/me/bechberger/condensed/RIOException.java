package me.bechberger.condensed;

import java.nio.file.Path;

/** Unchecked IOException wrapper */
public class RIOException extends RuntimeException {
    public RIOException(String message, Throwable cause) {
        super(message, cause);
    }

    public RIOException(String message) {
        super(message);
    }

    public static class NoStartStringException extends RIOException {
        public NoStartStringException(Throwable cause) {
            super("Stream has no start string", cause);
        }

        public NoStartStringException(String firstString) {
            super("Stream has no start string, has the following string instead: " + firstString);
        }
    }

    public static class UnexpectedEOFException extends RIOException {
        public UnexpectedEOFException(Throwable cause) {
            super("Truncated stream: unexpected end of stream", cause);
        }

        public UnexpectedEOFException() {
            super("Truncated stream: unexpected end of stream");
        }
    }

    public static class CannotCloseStreamException extends RIOException {
        public CannotCloseStreamException(Throwable cause) {
            super("Cannot close stream", cause);
        }
    }

    /** The file declares a format version this reader does not understand. */
    public static class UnsupportedFormatVersionException extends RIOException {
        public UnsupportedFormatVersionException(int found, int max) {
            super(
                    "Unsupported condensed-data format version "
                            + found
                            + " (this build supports up to version "
                            + max
                            + "). The file was written by a newer tool.");
        }
    }

    /** The start header names a compression algorithm this build does not know. */
    public static class UnknownCompressionException extends RIOException {
        public UnknownCompressionException(String name, Throwable cause) {
            super("Unknown compression algorithm in start header: " + name, cause);
        }
    }

    /** The whole-file CRC32 in the footer does not match the recomputed value. */
    public static class IntegrityCheckException extends RIOException {
        public IntegrityCheckException(Path file, long expected, long actual) {
            super(
                    "Integrity check failed for "
                            + file
                            + ": footer CRC32 = "
                            + Long.toHexString(expected)
                            + " but recomputed CRC32 = "
                            + Long.toHexString(actual)
                            + ". The file is corrupted. Use --ignore-integrity to bypass.");
        }
    }
}
