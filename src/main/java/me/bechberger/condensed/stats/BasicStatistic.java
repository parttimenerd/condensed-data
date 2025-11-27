package me.bechberger.condensed.stats;

/**
 * Statistics without the write cause tracking and tree building
 */
public class BasicStatistic extends Statistic {

    @Override
    public void pushWriteCauseContext(WriteCause cause) {
    }

    @Override
    public void popWriteCauseContext() {
    }
}