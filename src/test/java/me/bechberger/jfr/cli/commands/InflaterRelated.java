package me.bechberger.jfr.cli.commands;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a test class or method as requiring inflater-related classes (JMC). Tests annotated with
 * this are skipped when running against an inflaterless JAR (system property {@code
 * cjfr.test.inflaterless} is set to {@code true}).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(InflaterRelatedCondition.class)
public @interface InflaterRelated {}
