package me.bechberger.util;

import java.util.Locale;

/** Utility class for formatting memory sizes and parsing the same format */
public class MemoryUtil {

    public enum MemoryUnit {
        BYTES,
        BITS
    }

    public static String formatMemory(long bytes, int decimals, MemoryUnit unit) {
        String suffix = unit == MemoryUnit.BITS ? "b" : "B";
        if (bytes < 1024) {
            return bytes + suffix;
        }
        int exp = Math.min((int) (Math.log(bytes) / Math.log(1024)), 4);
        double divisor = Math.pow(1024, exp);
        char unitChar = "KMGT".charAt(exp - 1);
        for (int d = decimals; d <= 20; d++) {
            String numberStr = String.format(Locale.ENGLISH, "%." + d + "f", bytes / divisor);
            if (Math.round(Double.parseDouble(numberStr) * divisor) == bytes) {
                return numberStr + unitChar + suffix;
            }
        }
        return bytes + suffix;
    }

    public static String formatMemory(long bytes, int decimals) {
        return formatMemory(bytes, decimals, MemoryUnit.BYTES);
    }

    public static String formatMemory(long bytes) {
        return formatMemory(bytes, 2);
    }

    public static long parseMemory(String memory) {
        memory = memory.strip().toLowerCase();
        if (memory.endsWith("b")) {
            memory = memory.substring(0, memory.length() - 1);
        }
        if (memory.isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid memory format: empty after removing 'b' suffix");
        }
        int exp = "kmgt".indexOf(memory.charAt(memory.length() - 1)) + 1;
        if (exp == 0) {
            return Math.round(Double.parseDouble(memory));
        }
        var numberPart = Double.parseDouble(memory.substring(0, memory.length() - 1));
        return Math.round(numberPart * Math.pow(1024, exp));
    }
}
