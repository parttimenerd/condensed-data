package me.bechberger.jfr.cli;

/** Just some ideas for filter queries */
public class QueryIdeas {

    final String SAMPLE_QUERY =
            "event.start in gc(mem >= 100MB && duration > 1s && duration > 90% &&"
                + " (event.GarbageCollection.sumOfPauses < 90%), 1s, 2s) || GC in event.category";
}
