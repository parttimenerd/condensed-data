package me.bechberger.jfr.cli.agent;

import java.lang.instrument.Instrumentation;
import me.bechberger.jfr.UnsafeRecordedObjectAccessor;

/**
 * {@code Launcher-Agent-Class}: the JVM instantiates this and calls {@link #agentmain} before
 * {@code main()} when the jar is run as {@code java -jar condensed-data.jar ...}, giving us an
 * {@link Instrumentation} to open {@code jdk.jfr/jdk.jfr.consumer}. That lets the CLI's positional
 * {@link UnsafeRecordedObjectAccessor} fast path work with zero user flags.
 *
 * <p>Silent no-op if opening fails (older JDK, jlink runtime without {@code jdk.attach}, or a
 * classpath launch where no launcher agent runs) — the accessor then falls back to the public API.
 *
 * <p>Separate from {@link Agent} on purpose: a launcher agent is invoked with an empty args string,
 * and {@code Agent.agentmain("")} would start a recording. This class only opens the module.
 */
public final class ModuleOpenerAgent {

    private ModuleOpenerAgent() {}

    public static void agentmain(String args, Instrumentation inst) {
        try {
            UnsafeRecordedObjectAccessor.openModule(inst);
        } catch (Throwable ignored) {
            // Fast path stays off; accessor uses the public-API fallback.
        }
    }

    public static void premain(String args, Instrumentation inst) {
        agentmain(args, inst);
    }
}
