package me.bechberger.condensed;

import java.io.*;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.InflaterInputStream;
import org.jetbrains.annotations.Nullable;

/**
 * Precomputed summary stored as a zlib-compressed footer at the end of a .cjfr file.
 *
 * <p>Layout appended after the compressed main stream:
 *
 * <pre>
 *   [ zlib-compressed footer payload ][ 4-byte little-endian uint32 = payload length ]
 * </pre>
 *
 * The uncompressed payload starts with a magic type-ID varint (7) and 4 ASCII bytes ("CJFR").
 */
public record CJFRFooter(
        int version,
        long totalEvents,
        long startTimeMicros,
        long durationMicros,
        Map<String, Long> eventCounts,
        @Nullable GcStats gcStats,
        @Nullable CpuStats cpuStats,
        @Nullable AllocStats allocStats,
        /**
         * CRC32 over the on-disk bytes {@code [0, footerStart)} (start header + compressed main
         * stream). Filled in by {@link CondensedOutputStream#writeFooter} just before
         * serialization; 0 when constructed by the collector before the stream is finalized.
         */
        long mainStreamCrc32) {

    public static final int CURRENT_VERSION = 1;
    public static final int FOOTER_TYPE_ID = 7;
    public static final byte[] MAGIC = {'C', 'J', 'F', 'R'};

    public CJFRFooter withMainStreamCrc32(long crc) {
        return new CJFRFooter(
                version,
                totalEvents,
                startTimeMicros,
                durationMicros,
                eventCounts,
                gcStats,
                cpuStats,
                allocStats,
                crc);
    }

    public record GcStats(
            long gcCount,
            long youngGcCount,
            long oldGcCount,
            long totalGcMicros,
            long maxGcMicros,
            long medianGcMicros,
            long p95GcMicros,
            /** max phase pause from jdk.GCPhasePause, 0 if no phase-pause events */
            long maxGcPhasePauseMicros,
            Map<String, Long> causeCounts,
            /** collector name from jdk.GCConfiguration.youngCollector, null if not recorded */
            @Nullable String collectorName,
            long maxHeapUsedBeforeGcBytes,
            long maxHeapUsedAfterGcBytes,
            long maxMetaspaceUsedBytes,
            long totalGcCpuUserMicros,
            long totalGcCpuSystemMicros,
            long bucketSeconds,
            GcBucket[] buckets) {

        public record GcBucket(
                long gcCount,
                long totalGcMicros,
                long maxGcMicros,
                /** max "After GC" used heap in this bucket, 0 if no GCHeapSummary */
                long heapUsedAfterGcBytes,
                /** max "Before GC" used heap in this bucket; shows when heap went wild */
                long heapUsedBeforeGcBytes) {}
    }

    public record CpuStats(
            long bucketSeconds, float[] jvmUser, float[] jvmSystem, float[] machineTotal) {}

    /**
     * Allocation and promotion statistics. Collected from jdk.ObjectAllocationInNewTLAB,
     * jdk.ObjectAllocationOutsideTLAB, jdk.PromoteObjectInNewPLAB, jdk.PromoteObjectOutsidePLAB.
     */
    public record AllocStats(
            long totalAllocationBytes,
            long totalPromotionBytes,
            long bucketSeconds,
            long[] allocatedBytesPerBucket,
            long[] promotedBytesPerBucket) {}

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    public void writeTo(DataOutputStream out) throws IOException {
        writeUnsignedVarInt(out, FOOTER_TYPE_ID);
        out.write(MAGIC);
        writeUnsignedVarInt(out, version);

        byte flags = 0;
        if (gcStats != null) flags |= 1;
        if (cpuStats != null) flags |= 2;
        if (allocStats != null) flags |= 4;
        out.writeByte(flags);

        writeUnsignedVarInt(out, totalEvents);
        writeSignedLong8(out, startTimeMicros);
        writeSignedVarInt(out, durationMicros);
        writeSignedLong8(out, mainStreamCrc32);

        writeUnsignedVarInt(out, eventCounts.size());
        for (var e : eventCounts.entrySet()) {
            writeString(out, e.getKey());
            writeUnsignedVarInt(out, e.getValue());
        }

        if (gcStats != null) writeGcStats(out, gcStats);
        if (cpuStats != null) writeCpuStats(out, cpuStats);
        if (allocStats != null) writeAllocStats(out, allocStats);
    }

    private static void writeGcStats(DataOutputStream out, GcStats g) throws IOException {
        writeUnsignedVarInt(out, g.gcCount());
        writeUnsignedVarInt(out, g.youngGcCount());
        writeUnsignedVarInt(out, g.oldGcCount());
        writeSignedVarInt(out, g.totalGcMicros());
        writeSignedVarInt(out, g.maxGcMicros());
        writeSignedVarInt(out, g.medianGcMicros());
        writeSignedVarInt(out, g.p95GcMicros());
        writeSignedVarInt(out, g.maxGcPhasePauseMicros());
        writeUnsignedVarInt(out, g.causeCounts().size());
        for (var e : g.causeCounts().entrySet()) {
            writeString(out, e.getKey());
            writeUnsignedVarInt(out, e.getValue());
        }
        // collectorName: null encoded as empty string
        writeString(out, g.collectorName() != null ? g.collectorName() : "");
        writeSignedLong8(out, g.maxHeapUsedBeforeGcBytes());
        writeSignedLong8(out, g.maxHeapUsedAfterGcBytes());
        writeSignedLong8(out, g.maxMetaspaceUsedBytes());
        writeSignedLong8(out, g.totalGcCpuUserMicros());
        writeSignedLong8(out, g.totalGcCpuSystemMicros());
        writeUnsignedVarInt(out, g.bucketSeconds());
        writeUnsignedVarInt(out, g.buckets().length);
        for (var b : g.buckets()) {
            writeUnsignedVarInt(out, b.gcCount());
            writeSignedVarInt(out, b.totalGcMicros());
            writeSignedVarInt(out, b.maxGcMicros());
            writeSignedLong8(out, b.heapUsedAfterGcBytes());
            writeSignedLong8(out, b.heapUsedBeforeGcBytes());
        }
    }

    private static void writeCpuStats(DataOutputStream out, CpuStats c) throws IOException {
        writeUnsignedVarInt(out, c.bucketSeconds());
        int n = c.jvmUser().length;
        writeUnsignedVarInt(out, n);
        for (int i = 0; i < n; i++) {
            writeFloat(out, c.jvmUser()[i]);
            writeFloat(out, c.jvmSystem()[i]);
            writeFloat(out, c.machineTotal()[i]);
        }
    }

    private static void writeAllocStats(DataOutputStream out, AllocStats a) throws IOException {
        writeSignedLong8(out, a.totalAllocationBytes());
        writeSignedLong8(out, a.totalPromotionBytes());
        writeUnsignedVarInt(out, a.bucketSeconds());
        writeUnsignedVarInt(out, a.allocatedBytesPerBucket().length);
        for (long v : a.allocatedBytesPerBucket()) writeSignedLong8(out, v);
        writeUnsignedVarInt(out, a.promotedBytesPerBucket().length);
        for (long v : a.promotedBytesPerBucket()) writeSignedLong8(out, v);
    }

    // -------------------------------------------------------------------------
    // Deserialization
    // -------------------------------------------------------------------------

    public static CJFRFooter readRest(DataInputStream in, int version) throws IOException {
        if (version > CURRENT_VERSION) {
            throw new UnsupportedFooterVersionException(version);
        }

        byte flags = in.readByte();
        boolean hasGc = (flags & 1) != 0;
        boolean hasCpu = (flags & 2) != 0;
        boolean hasAlloc = (flags & 4) != 0;

        long totalEvents = readUnsignedVarint(in);
        long startTimeMicros = readSignedLong8(in);
        long durationMicros = readSignedVarint(in);
        long mainStreamCrc32 = readSignedLong8(in);

        int n = (int) readUnsignedVarint(in);
        Map<String, Long> eventCounts = new LinkedHashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            eventCounts.put(readString(in), readUnsignedVarint(in));
        }

        GcStats gcStats = hasGc ? readGcStats(in) : null;
        CpuStats cpuStats = hasCpu ? readCpuStats(in) : null;
        AllocStats allocStats = hasAlloc ? readAllocStats(in) : null;

        return new CJFRFooter(
                version,
                totalEvents,
                startTimeMicros,
                durationMicros,
                Collections.unmodifiableMap(eventCounts),
                gcStats,
                cpuStats,
                allocStats,
                mainStreamCrc32);
    }

    private static GcStats readGcStats(DataInputStream in) throws IOException {
        long gcCount = readUnsignedVarint(in);
        long youngGcCount = readUnsignedVarint(in);
        long oldGcCount = readUnsignedVarint(in);
        long totalGcMicros = readSignedVarint(in);
        long maxGcMicros = readSignedVarint(in);
        long medianGcMicros = readSignedVarint(in);
        long p95GcMicros = readSignedVarint(in);
        long maxGcPhasePauseMicros = readSignedVarint(in);
        int causeCount = (int) readUnsignedVarint(in);
        Map<String, Long> causeCounts = new LinkedHashMap<>(causeCount * 2);
        for (int i = 0; i < causeCount; i++) {
            causeCounts.put(readString(in), readUnsignedVarint(in));
        }
        String collectorNameRaw = readString(in);
        String collectorName = collectorNameRaw.isEmpty() ? null : collectorNameRaw;
        long maxHeapBefore = readSignedLong8(in);
        long maxHeapAfter = readSignedLong8(in);
        long maxMetaspace = readSignedLong8(in);
        long totalGcCpuUser = readSignedLong8(in);
        long totalGcCpuSystem = readSignedLong8(in);
        long bucketSeconds = readUnsignedVarint(in);
        int numBuckets = (int) readUnsignedVarint(in);
        GcStats.GcBucket[] buckets = new GcStats.GcBucket[numBuckets];
        for (int i = 0; i < numBuckets; i++) {
            long bc = readUnsignedVarint(in);
            long bt = readSignedVarint(in);
            long bm = readSignedVarint(in);
            long bha = readSignedLong8(in);
            long bhb = readSignedLong8(in);
            buckets[i] = new GcStats.GcBucket(bc, bt, bm, bha, bhb);
        }
        return new GcStats(
                gcCount,
                youngGcCount,
                oldGcCount,
                totalGcMicros,
                maxGcMicros,
                medianGcMicros,
                p95GcMicros,
                maxGcPhasePauseMicros,
                Collections.unmodifiableMap(causeCounts),
                collectorName,
                maxHeapBefore,
                maxHeapAfter,
                maxMetaspace,
                totalGcCpuUser,
                totalGcCpuSystem,
                bucketSeconds,
                buckets);
    }

    private static CpuStats readCpuStats(DataInputStream in) throws IOException {
        long bucketSeconds = readUnsignedVarint(in);
        int n = (int) readUnsignedVarint(in);
        float[] jvmUser = new float[n];
        float[] jvmSystem = new float[n];
        float[] machineTotal = new float[n];
        for (int i = 0; i < n; i++) {
            jvmUser[i] = readFloat(in);
            jvmSystem[i] = readFloat(in);
            machineTotal[i] = readFloat(in);
        }
        return new CpuStats(bucketSeconds, jvmUser, jvmSystem, machineTotal);
    }

    private static AllocStats readAllocStats(DataInputStream in) throws IOException {
        long totalAlloc = readSignedLong8(in);
        long totalPromo = readSignedLong8(in);
        long bucketSeconds = readUnsignedVarint(in);
        int na = (int) readUnsignedVarint(in);
        long[] allocPerBucket = new long[na];
        for (int i = 0; i < na; i++) allocPerBucket[i] = readSignedLong8(in);
        int np = (int) readUnsignedVarint(in);
        long[] promoPerBucket = new long[np];
        for (int i = 0; i < np; i++) promoPerBucket[i] = readSignedLong8(in);
        return new AllocStats(
                totalAlloc, totalPromo, bucketSeconds, allocPerBucket, promoPerBucket);
    }

    // -------------------------------------------------------------------------
    // zlib compress/decompress
    // -------------------------------------------------------------------------

    public byte[] toCompressedBytes() {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (DataOutputStream dos = new DataOutputStream(raw)) {
                writeTo(dos);
            }
            byte[] payload = raw.toByteArray();
            Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
            deflater.setInput(payload);
            deflater.finish();
            ByteArrayOutputStream compressed = new ByteArrayOutputStream(payload.length / 2 + 64);
            byte[] buf = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(buf);
                compressed.write(buf, 0, n);
            }
            deflater.end();
            return compressed.toByteArray();
        } catch (IOException e) {
            throw new RIOException("Failed to serialize footer", e);
        }
    }

    public static @Nullable CJFRFooter fromCompressedBytes(byte[] zlibBytes) {
        try {
            byte[] payload;
            try (InflaterInputStream iis =
                    new InflaterInputStream(new ByteArrayInputStream(zlibBytes))) {
                payload = iis.readAllBytes();
            }
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            long typeId = readUnsignedVarint(in);
            if (typeId != FOOTER_TYPE_ID) return null;
            byte[] tag = in.readNBytes(4);
            if (!Arrays.equals(tag, MAGIC)) return null;
            int version = (int) readUnsignedVarint(in);
            if (version > CURRENT_VERSION) return null;
            return readRest(in, version);
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Low-level I/O primitives
    // -------------------------------------------------------------------------

    private static void writeUnsignedVarInt(DataOutputStream out, long value) throws IOException {
        while ((value & 0xFFFFFFFFFFFFFF80L) != 0L) {
            out.writeByte((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.writeByte((int) (value & 0x7F));
    }

    private static void writeSignedVarInt(DataOutputStream out, long value) throws IOException {
        writeUnsignedVarInt(out, (value << 1) ^ (value >> 63));
    }

    private static void writeSignedLong8(DataOutputStream out, long value) throws IOException {
        for (int i = 0; i < 8; i++) {
            out.writeByte((int) (value & 0xFF));
            value >>>= 8;
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeUnsignedVarInt(out, bytes.length);
        if (bytes.length > 0) out.write(bytes);
    }

    private static void writeFloat(DataOutputStream out, float value) throws IOException {
        int bits = Float.floatToRawIntBits(value);
        for (int i = 0; i < 4; i++) {
            out.writeByte(bits & 0xFF);
            bits >>>= 8;
        }
    }

    private static long readUnsignedVarint(DataInputStream in) throws IOException {
        long result = 0;
        int shift = 0;
        int b;
        do {
            b = in.readByte() & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }

    private static long readSignedVarint(DataInputStream in) throws IOException {
        long unsigned = readUnsignedVarint(in);
        return (unsigned >>> 1) ^ -(unsigned & 1);
    }

    private static long readSignedLong8(DataInputStream in) throws IOException {
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result |= ((long) (in.readByte() & 0xFF)) << (i * 8);
        }
        return result;
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = (int) readUnsignedVarint(in);
        if (length == 0) return "";
        byte[] bytes = new byte[length];
        int read = in.read(bytes);
        if (read != length) throw new IOException("Unexpected EOF reading string");
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static float readFloat(DataInputStream in) throws IOException {
        int bits = 0;
        for (int i = 0; i < 4; i++) {
            bits |= (in.readByte() & 0xFF) << (i * 8);
        }
        return Float.intBitsToFloat(bits);
    }

    public static class UnsupportedFooterVersionException extends RuntimeException {
        public UnsupportedFooterVersionException(int version) {
            super("Unsupported footer version: " + version + " > " + CURRENT_VERSION);
        }
    }
}
