package me.bechberger.condensed.stats;

import me.bechberger.condensed.types.CondensedType;

/**
 * A statistic that collects nothing. The default on both the READ and WRITE streams so the hot path
 * pays no bookkeeping cost unless statistics are explicitly requested (e.g. {@code summary --full}
 * or {@code condense --statistics} installs a real {@link Statistic}). Extends {@link
 * BasicStatistic} so {@code enableFullStatistics()}, which upgrades a {@link BasicStatistic} to a
 * full {@link Statistic}, still applies.
 *
 * <p>Safe on the write stream because {@code CondensedOutputStream} owns its uncompressed-byte
 * counter directly (a plain field) rather than reading it from the statistic — so flush /
 * compression-block rotation and size estimation no longer depend on this object doing any work.
 */
public class NoopStatistic extends BasicStatistic {

    /** Shared no-op context: push/pop do nothing, so a single instance is safe to reuse. */
    private final WriteCauseContext noopContext = new WriteCauseContext(this, WriteCause.Start);

    @Override
    public void setModeAndCount(WriteMode mode) {}

    @Override
    public void record(int bytes) {}

    @Override
    public void recordString(long bytes) {}

    @Override
    public WriteCauseContext withWriteCauseContext(WriteCause cause) {
        return noopContext;
    }

    @Override
    public WriteCauseContext withWriteCauseContext(CondensedType<?, ?> type) {
        return noopContext;
    }
}
