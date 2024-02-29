package me.bechberger.condensed;

/** Unchecked IOException wrapper */
public class RIOException extends RuntimeException {
    public RIOException(String message, Throwable cause) {
        super(message, cause);
    }

    public RIOException(Throwable cause) {
        super(cause);
    }

    public RIOException(String message) {
        super(message);
    }
}
