package me.bechberger.jfr.cli;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import me.bechberger.condensed.ReadStruct;
import me.bechberger.jfr.cli.CLIUtils.DurationConverter;
import me.bechberger.jfr.cli.CLIUtils.InstantConverter;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import static me.bechberger.jfr.cli.EventFilter.TwoPhaseEventFilter.Phase.INFORMATION_GATHERING;

@FunctionalInterface
public interface EventFilter {
    /**
     * Checks if the filter matches the event.
     *
     * @param struct event to check
     * @return matched filter?
     */
    boolean test(ReadStruct struct);

    /**
     * Stateful event filter that first gathers information about the events and then filters them.
     */
    abstract class TwoPhaseEventFilter implements EventFilter {

        enum Phase {
            INFORMATION_GATHERING,
            FILTERING
        }

        private Phase phase = INFORMATION_GATHERING;

        void setPhase(Phase phase) {
            this.phase = phase;
        }

        @Override
        public boolean test(ReadStruct struct) {
            return switch (phase) {
                case INFORMATION_GATHERING -> {
                    analyze(struct);
                    yield false;
                }
                case FILTERING -> properTest(struct);
            };
        }

        /** Analyze the event to gather information for the filter */
        abstract void analyze(ReadStruct struct);

        /** Check if the event matches the filter */
        abstract boolean properTest(ReadStruct struct);
    }

    class EventFilterOptionMixin {

        @Option(
                names = {"--start"},
                description = "The start time",
                converter = InstantConverter.class)
        Instant start;

        @Option(
                names = {"--end"},
                description = "The end time",
                converter = InstantConverter.class)
        Instant end;

        @Option(
                names = {"--duration"},
                description = "The duration, don't pass it if both start and end are passed",
                converter = DurationConverter.class)
        Duration duration;

        @Option(
                names = {"--events"},
                description = "The event types to include")
        List<String> eventTypes;

        @Option(
                names = "--no-reconstitution",
                description = "Don't reconstitute the events, just read the combined events")
        boolean noReconstitution;

        @Spec CommandSpec spec;

        /** Create an event filter from the options */
        EventFilter createFilter() {
            if (start == null && end == null && duration == null && eventTypes == null) {
                return event -> true;
            }
            if (start != null && end != null && duration != null) {
                throw new ParameterException(
                        spec.commandLine(), "Only pass start and end or duration, not all");
            }
            Instant start = this.start;
            Instant end = this.end;
            if (start == null) {
                if (end == null) {
                    start = Instant.MIN;
                    end = Instant.MAX;
                } else if (duration != null) {
                    start = end.minus(duration);
                }
            } else if (end == null) {
                if (duration != null) {
                    end = start.plus(duration);
                }
            }
            Instant fixedStart = start;
            Instant fixedEnd = end;
            return event -> {
                if (event == null) {
                    return false;
                }
                if (fixedStart != null || fixedEnd != null) {
                    var startTime = event.get("startTime", Instant.class);
                    if (fixedStart != null) { // startTime should be present for almost all events
                        if (startTime == null || startTime.isBefore(fixedStart)) {
                            return false;
                        }
                    }
                    var endTime = event.get("endTime", Instant.class);
                    if (fixedEnd != null && endTime != null) {
                        if (endTime.isAfter(fixedEnd)) {
                            return false;
                        }
                    }
                }
                return eventTypes == null || eventTypes.contains(event.getType().getName());
            };
        }
    }
}
