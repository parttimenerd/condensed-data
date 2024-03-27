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
}
