package me.bechberger.jfr.cli;

public enum TruncateMode {
    BEGIN,
    END;

    public static TruncateMode fromCliValue(String value) {
        return switch (value.strip().toLowerCase()) {
            case "begin", "beginning" -> BEGIN;
            case "end" -> END;
            default ->
                    throw new IllegalArgumentException(
                            "Unknown truncate mode: " + value + " use one of [beginning, end]");
        };
    }
}
