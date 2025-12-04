package me.bechberger.condensed;

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
            super("Unexpected end of stream", cause);
        }

        public UnexpectedEOFException() {
            super("Unexpected end of stream");
        }
    }

    public static class CannotCloseStreamException extends RIOException {
        public CannotCloseStreamException(Throwable cause) {
            super("Cannot close stream", cause);
        }
    }
}
