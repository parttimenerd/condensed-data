package me.bechberger.condensed;

public class UnexpectedMessageTypeException extends RuntimeException {
    public UnexpectedMessageTypeException(Message message) {
        super("Unexpected message type: " + message);
    }
}
