package pandastic.studio.models.api.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmissionCadenceTest {

    /**
     * The window limits these tests run against. Wide enough not to clamp anything, so each assertion is
     * about the cadence arithmetic rather than about the bounds — {@link CadenceBoundsTest} covers the
     * clamping, with values that disagree with the removed constants.
     */
    private static final CadenceBounds BOUNDS = new CadenceBounds(1, 59);

    @Test
    void emittingAtTheClientTickRateNeedsTheSmallestWindow() {
        final EmissionCadence cadence = EmissionCadence.of(60, 20, BOUNDS);
        assertAll(
                () -> assertEquals(20, cadence.emissionHz()),
                () -> assertEquals(3, cadence.stepsPerEmission()),
                () -> assertEquals(1, cadence.interpolationTicks()));
    }

    @Test
    void windowAlwaysCoversTheGapBetweenUpdates() {
        // The whole point: a window shorter than the interval finishes early and the bone sits still
        // until the next packet, which stutters in the rhythm of the emission rate.
        assertAll(
                () -> assertEquals(2, EmissionCadence.of(60, 10, BOUNDS).interpolationTicks()),
                () -> assertEquals(4, EmissionCadence.of(60, 5, BOUNDS).interpolationTicks()),
                () -> assertEquals(5, EmissionCadence.of(60, 4, BOUNDS).interpolationTicks()),
                () -> assertEquals(10, EmissionCadence.of(60, 2, BOUNDS).interpolationTicks()),
                () -> assertEquals(20, EmissionCadence.of(60, 1, BOUNDS).interpolationTicks()));
    }

    @Test
    void windowNeverFallsBelowTheEmissionIntervalInTicks() {
        for (final int hz : new int[]{1, 2, 4, 5, 10, 20}) {
            final EmissionCadence cadence = EmissionCadence.of(60, hz, BOUNDS);
            final int intervalTicks = EmissionCadence.CLIENT_TICK_HZ / hz;
            assertTrue(cadence.interpolationTicks() >= intervalTicks,
                    hz + " Hz: window " + cadence.interpolationTicks()
                            + " must cover the " + intervalTicks + "-tick interval");
        }
    }

    @Test
    void emitsOnEveryNthStep() {
        final EmissionCadence cadence = EmissionCadence.of(60, 10, BOUNDS);
        assertAll(
                () -> assertTrue(cadence.shouldEmit(0)),
                () -> assertFalse(cadence.shouldEmit(1)),
                () -> assertFalse(cadence.shouldEmit(5)),
                () -> assertTrue(cadence.shouldEmit(6)),
                () -> assertTrue(cadence.shouldEmit(12)));
    }

    @Test
    void emitsEveryStepWhenTheRatesMatch() {
        final EmissionCadence cadence = EmissionCadence.of(20, 20, BOUNDS);
        assertEquals(1, cadence.stepsPerEmission());
        for (long step = 0; step < 10; step++) {
            assertTrue(cadence.shouldEmit(step));
        }
    }

    @Test
    void jitterToleranceAddsExactlyOneTick() {
        final EmissionCadence tight = EmissionCadence.of(60, 20, BOUNDS);
        final EmissionCadence tolerant = tight.withJitterTolerance();

        assertAll(
                () -> assertEquals(2, tolerant.interpolationTicks()),
                () -> assertEquals(tight.emissionHz(), tolerant.emissionHz()),
                () -> assertEquals(tight.stepsPerEmission(), tolerant.stepsPerEmission()),
                () -> assertEquals(tight.bounds(), tolerant.bounds()),
                // The original is untouched: these are value objects.
                () -> assertEquals(1, tight.interpolationTicks()));
    }

    @Test
    void jitterToleranceRespectsTheConfiguredWindowCeiling() {
        final EmissionCadence slowest = EmissionCadence.of(60, 1, BOUNDS);
        assertEquals(20, slowest.interpolationTicks());

        EmissionCadence widened = slowest;
        for (int i = 0; i < 100; i++) {
            widened = widened.withJitterTolerance();
        }
        assertEquals(BOUNDS.maxInterpolationTicks(), widened.interpolationTicks());
    }

    @Test
    void refusesToEmitFasterThanTheClientCanConsume() {
        // Above the client tick rate the extra packets are discarded unseen, so this is a
        // configuration error rather than a trade-off worth allowing. CLIENT_TICK_HZ stays a constant
        // for exactly this reason: made configurable, this check could be switched off.
        final IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class, () -> EmissionCadence.of(60, 40, BOUNDS));
        assertTrue(thrown.getMessage().contains("discarded unseen"));

        assertThrows(IllegalArgumentException.class, () -> EmissionCadence.of(60, 21, BOUNDS));
        assertThrows(IllegalArgumentException.class, () -> EmissionCadence.of(60, 0, BOUNDS));
        assertThrows(IllegalArgumentException.class, () -> EmissionCadence.of(60, -1, BOUNDS));
    }

    @Test
    void theClientTickRateIsAProtocolFactAndStaysOne() {
        // A display entity consumes its pending-update flags exactly once per client tick. Twenty is how
        // many distinct visible state changes a second the client is capable of, whatever the server
        // sends, so this is not a knob and the emission ceiling is derived from it rather than set.
        assertEquals(20, EmissionCadence.CLIENT_TICK_HZ);
        assertEquals(EmissionCadence.CLIENT_TICK_HZ,
                EmissionCadence.of(60, EmissionCadence.CLIENT_TICK_HZ, BOUNDS).emissionHz());
    }

    @Test
    void refusesRatesThatAreNotACleanMultiple() {
        final IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class, () -> EmissionCadence.of(50, 20, BOUNDS));
        assertTrue(thrown.getMessage().contains("not a multiple"));
    }

    @Test
    void refusesASimulationSlowerThanItsEmission() {
        assertThrows(IllegalArgumentException.class, () -> EmissionCadence.of(10, 20, BOUNDS));
    }
}
