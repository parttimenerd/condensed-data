package me.bechberger.util;

import java.util.Locale;

/**
 * Utility class for formatting memory sizes and parsing the same format
 */
public class MemoryUtil {

    public static String formatMemory(long bytes, int decimals) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        return String.format(
                Locale.ENGLISH,
                "%.0" + decimals + "f%sB", bytes / Math.pow(1024, exp), "KMGT".charAt(exp - 1));
    }

    public static String formatMemory(long bytes) {
        return formatMemory(bytes, 2);
    }

    public static long parseMemory(String memory) {
        memory = memory.toLowerCase();
        if (memory.endsWith("b")) {
            memory = memory.substring(0, memory.length() - 1);
        }
        int exp = "kmgt".indexOf(memory.charAt(memory.length() - 1)) + 1;
        if (exp == 0) {
            return (long)Double.parseDouble(memory);
        }
        var numberPart = Double.parseDouble(memory.substring(0, memory.length() - 1));
        return Math.round(numberPart * Math.pow(1024, exp));
    }

}
