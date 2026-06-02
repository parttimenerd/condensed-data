package me.bechberger.util;

import java.util.Locale;

/** Utility class for formatting memory sizes and parsing the same format */
public class MemoryUtil {

    public enum MemoryUnit {
        BYTES,
        BITS
    }

    public static String formatMemory(long bytes, int decimals, MemoryUnit unit) {
        return formatMemory(bytes, decimals, 20, unit);
    }

    public static String formatMemory(
            long bytes, int minDecimals, int maxDecimals, MemoryUnit unit) {
        String suffix = unit == MemoryUnit.BITS ? "b" : "B";
        double absBytes = Math.abs((double) bytes);
        if (absBytes < 1024) {
            return bytes + suffix;
        }
        int exp = Math.min((int) (Math.log(absBytes) / Math.log(1024)), 4);
        double divisor = Math.pow(1024, exp);
        char unitChar = "KMGT".charAt(exp - 1);
        for (int d = minDecimals; d <= maxDecimals; d++) {
            String numberStr = String.format(Locale.ENGLISH, "%." + d + "f", bytes / divisor);
            if (Math.round(Double.parseDouble(numberStr) * divisor) == bytes) {
                return numberStr + unitChar + suffix;
            }
        }
        // No exact round-trip within maxDecimals, use maxDecimals (lossy)
        String numberStr = String.format(Locale.ENGLISH, "%." + maxDecimals + "f", bytes / divisor);
        return numberStr + unitChar + suffix;
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
        var numberStr = memory.substring(0, memory.length() - 1);
        if (numberStr.isEmpty()) {
            throw new IllegalArgumentException("Invalid memory format: '" + memory + "'");
        }
        var numberPart = Double.parseDouble(numberStr);
        return Math.round(numberPart * Math.pow(1024, exp));
    }
}
