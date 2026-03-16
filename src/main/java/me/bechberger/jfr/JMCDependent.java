package me.bechberger.jfr;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks classes that depend on org.openjdk.jmc and can be removed when building a JMC-free JAR.
 *
 * <p>The reduce-jar.py script uses javap to detect this annotation and strips annotated classes
 * when --without-jmc is specified.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface JMCDependent {}
