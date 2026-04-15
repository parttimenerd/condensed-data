package me.bechberger.jfr.cli.commands;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 condition that disables tests annotated with {@link InflaterRelated} when the system
 * property {@code cjfr.test.inflaterless} is {@code "true"}.
 */
class InflaterRelatedCondition implements ExecutionCondition {

    private static final String INFLATERLESS_PROPERTY = "cjfr.test.inflaterless";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if ("true".equals(System.getProperty(INFLATERLESS_PROPERTY))) {
            return ConditionEvaluationResult.disabled(
                    "Skipped: inflater-related test on inflaterless JAR");
        }
        return ConditionEvaluationResult.enabled("Inflater classes available");
    }
}
