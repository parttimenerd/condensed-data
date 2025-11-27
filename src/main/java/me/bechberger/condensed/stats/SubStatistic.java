package me.bechberger.condensed.stats;

public class SubStatistic {
    private final WriteMode mode;
    int count = 0;
    int bytes = 0;
    int strings = 0;
    int stringBytes = 0;
    int stringLe10Bytes = 0;
    int stringLe100Bytes = 0;
    int stringLe1000Bytes = 0;

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