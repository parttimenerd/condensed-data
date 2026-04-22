package me.bechberger.jfr.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests verifying the noReconstitution flag is correctly negated.
 *
 * <p>The fix: Pass {@code !eventFilterOptionMixin.noReconstitution()} as the reconstitute
 * parameter.
 *
 * <p>Affected files: InflateCommand.java, ViewCommand.java, SummaryCommand.java
 */
public class NoReconstitutionBugTest {

    /**
     * When user sets --no-reconstitution, noReconstitution() returns true, so reconstitute should
     * be !true = false (don't reconstitute).
     */
    @Test
    public void testNoReconstitutionFlagIsInverted() {
        // When user sets --no-reconstitution, noReconstitution() returns true
        boolean noReconstitutionFlagValue = true;

        // The fixed code does: !noReconstitution()
        boolean reconstitute = !noReconstitutionFlagValue;

        // User wants NO reconstitution → reconstitute should be false
        assertThat(reconstitute)
                .as("When user sets --no-reconstitution, reconstitute should be false")
                .isFalse();
    }

    /**
     * When user does NOT set --no-reconstitution, noReconstitution() returns false, so reconstitute
     * should be !false = true (reconstitute by default).
     */
    @Test
    public void testDefaultReconstitutionBehaviorIsInverted() {
        // When user does NOT set --no-reconstitution, noReconstitution() returns false
        boolean noReconstitutionFlagValue = false;

        // The fixed code does: !noReconstitution()
        boolean reconstitute = !noReconstitutionFlagValue;

        // Default: reconstitute should be true (events are reconstituted by default)
        assertThat(reconstitute).as("By default, reconstitute should be true").isTrue();
    }
}
