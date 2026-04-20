package me.bechberger.condensed.stats;

public class SubStatistic {
    private final WriteMode mode;
    long count = 0;
    long bytes = 0;
    long strings = 0;
    long stringBytes = 0;
    long stringLe10Bytes = 0;
    long stringLe100Bytes = 0;
    long stringLe1000Bytes = 0;

    public SubStatistic(WriteMode mode) {
        this.mode = mode;
    }

    @Override
    public String toString() {
        return "SubStatistic{"
                + "mode="
                + mode
                + ", count="
                + count
                + ", bytes="
                + bytes
                + ", strings="
                + strings
                + ", stringBytes="
                + stringBytes
                + '}';
    }
}
