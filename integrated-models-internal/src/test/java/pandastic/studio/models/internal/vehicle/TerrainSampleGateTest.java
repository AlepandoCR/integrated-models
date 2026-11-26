package pandastic.studio.models.internal.vehicle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decision to read the ground, with no ground and no vehicle: five inputs in, one boolean out.
 *
 * <h2>Why this is the suite that proves the saving</h2>
 * Because the saving <em>is</em> this function. A terrain reading is the only block access in the plugin
 * and cannot be moved off the owning thread on a regionised server, so the only available lever is taking
 * fewer of them — and every judgement about when that is safe lives here, where it can be driven with a
 * scripted clock instead of a world. {@link VehicleTerrainSamplingTest} then checks that a real control
 * step honours the answer.
 *
 * <p>The three overrides are what the suite is mostly about, because each of them is a configuration
 * being refused rather than obeyed: a vehicle that has crossed a whole column, a reading that has
 * expired, and a vehicle that has never read anything, all of which read regardless of what the
 * thresholds say.
 */
class TerrainSampleGateTest {

    /**
     * A deliberately odd set of thresholds: a quarter block, five degrees, four ticks and ten.
     *
     * <p>None of them is a shipped default, so an assertion that agrees with one of these figures is an
     * assertion that the gate read its settings rather than that it happened to agree.
     */
    private static final TerrainSampling SAMPLING = new TerrainSampling(0.25F, 5F, 4, 10, false);

    /**
     * The clock's origin, and negative on purpose.
     *
     * <p>{@link System#nanoTime()}'s origin is arbitrary and genuinely is negative on some JVMs, so a
     * gate that compared readings instead of differencing them would pass against a zero-based clock and
     * refuse every reading in production.
     */
    private static final long ORIGIN = -9_000_000_000L;

    private final TerrainSampleGate gate = new TerrainSampleGate();

    private static long ticks(double count) {
        return Math.round(count * (double) VehicleStepper.NANOS_PER_TICK);
    }

    // ------------------------------------------------------------------ the first reading

    @Test
    void theFirstReadingIsAlwaysDueBecauseThereIsNothingToReuse() {
        // Not a special case bolted on: a vehicle's first step has no previous reading, so there is no
        // decision to make. Everything below assumes this has happened.
        assertTrue(this.gate.readingDue(SAMPLING, 0D, 64D, 0D, 0F, ORIGIN));
    }

    // ------------------------------------------------------------------ the deadband

    @Test
    void aVehicleThatHasNotMovedFarEnoughReadsNothing() {
        // The key claim, and the one the owner asked for: the ground does not change while the vehicle
        // has not moved, so a parked or crawling vehicle costs no block reads at all.
        this.gate.readingDue(SAMPLING, 0D, 64D, 0D, 0F, ORIGIN);

        assertAll(
                () -> assertFalse(this.gate.readingDue(SAMPLING, 0D, 64D, 0D, 0F, ORIGIN + ticks(4)),
                        "parked, and well past the interval"),
                () -> assertFalse(this.gate.readingDue(SAMPLING, 0.2D, 64D, 0D, 0F, ORIGIN + ticks(5)),
                        "a fifth of a block is inside a quarter-block deadband")
        );
    }

    @Test
    void aVehicleThatHasMovedPastTheDeadbandReadsAgain() {
        this.gate.readingDue(SAMPLING, 0D, 64D, 0D, 0F, ORIGIN);

        assertTrue(this.gate.readingDue(SAMPLING, 0.3D, 64D, 0D, 0F, ORIGIN + ticks(4)));
    }

    @Test
    void theDeadbandIsMeasuredInThreeDimensionsSoAClimbCrossesIt() {
        // A climb moves a vehicle vertically and almost not at all horizontally, and it is exactly the
        // moment the ground underneath has changed. A deadband on the horizontal alone would hold the
        // reading that armed the climb for the whole of it.
        this.gate.readingDue(SAMPLING, 0D, 64D, 0D, 0F, ORIGIN);

        assertTrue(this.gate.readingDue(SAMPLING, 0D, 64.4D, 0D, 0F, ORIGIN + ticks(4)));
    }

    // ------------------------------------------------------------------ the turn

    @Test
    void aVehicleThatHasTurnedEnoughReadsAgainWithoutHavingMoved() {
        // Which column counts as "ahead" is chosen by the heading, so a vehicle turning on the spot is
        // looking at ground it has never read. Its own threshold rather than part of the distance,
        // because no distance was travelled.
        this.gate.readingDue(SAMPLING, 0D, 64D, 0D, 0F, ORIGIN);

        assertAll(
                () -> assertFalse(this.gate.readingDue(SAMPLING, 0D, 64D, 0D, 3F, ORIGIN + ticks(4)),
                        "three degrees is inside a five-degree deadband"),
                () -> assertTrue(this.gate.readingDue(SAMPLING, 0D, 64D, 0D, 6F, ORIGIN + ticks(8)))
        );
    }

    @Test
    void aTurnAcrossTheSeamIsTheFewDegreesItIsAndNotThreeHundredAndSixty() {
        // Normalised, like every other heading difference in this layer. Unnormalised, a vehicle whose
        // heading crossed 180 would read the ground on that one step however still it was standing.
        this.gate.readingDue(SAMPLING, 0D, 64D, 0D, 179F, ORIGIN);

        assertFalse(this.gate.readingDue(SAMPLING, 0D, 64D, 0D, -179F, ORIGIN + ticks(4)),
                "179 to -179 is two degrees of turn, which is inside the deadband");
    }

    // ------------------------------------------------------------------ the interval

    @Test
    void nothingIsReadInsideTheIntervalHoweverFarTheVehicleHasMoved() {
        // The term that bounds a vehicle at full speed, which is the one the deadband cannot help: such a
        // vehicle crosses the deadband on every single step. Half a block is twice the deadband and still
        // reads nothing, because two ticks is inside a four-tick interval.
        this.gate.readingDue(SAMPLING, 0D, 64D, 0D, 0F, ORIGIN);

        assertFalse(this.gate.readingDue(SAMPLING, 0.5D, 64D, 0D, 0F, ORIGIN + ticks(2)));
    }

    @Test
    void aVehicleThatHasCrossedAWholeColumnReadsInsideTheIntervalAnyway() {
        // The override that keeps the look-ahead honest at any sample rate, and the answer to "a probe
        // that was safe at one block of look-ahead may not be at four". The probe looks exactly one
        // column ahead, so a vehicle that has moved a column is standing in the column its reading called
        // "ahead": that reading is not stale, it is about somewhere else. No interval may suppress it.
        this.gate.readingDue(SAMPLING, 0D, 64D, 0D, 0F, ORIGIN);

        assertAll(
                () -> assertTrue(this.gate.readingDue(SAMPLING, (double) TerrainSample.PROBE_RUN_BLOCKS,
                                64D, 0D, 0F, ORIGIN + ticks(1)),
                        "one column travelled inside one tick still reads"),
                // And a relocation, which is the same thing several hundred times over.
                () -> assertTrue(this.gate.readingDue(SAMPLING, 400D, 64D, 400D, 0F, ORIGIN + ticks(1)))
        );
    }

    // ------------------------------------------------------------------ the age

    @Test
    void aReadingExpiresEvenForAVehicleThatHasNotMovedAtAll() {
        // The other half of the deadband and the reason it is safe: the thing that changed may be the
        // world rather than the vehicle. Somebody builds a ramp under a parked cart, and no amount of the
        // cart not moving will ever notice.
        this.gate.readingDue(SAMPLING, 0D, 64D, 0D, 0F, ORIGIN);

        assertAll(
                () -> assertFalse(this.gate.readingDue(SAMPLING, 0D, 64D, 0D, 0F, ORIGIN + ticks(9))),
                () -> assertTrue(this.gate.readingDue(SAMPLING, 0D, 64D, 0D, 0F, ORIGIN + ticks(10)))
        );
    }

    @Test
    void theAgeIsMeasuredFromTheLastReadingRatherThanTheLastQuestion() {
        // A gate that restarted its clock on every call — rather than on every reading taken — would
        // never expire anything on a loop that asks it every tick, which is every loop there is.
        this.gate.readingDue(SAMPLING, 0D, 64D, 0D, 0F, ORIGIN);
        for (int tick = 1; tick <= 9; tick++) {
            assertFalse(this.gate.readingDue(SAMPLING, 0D, 64D, 0D, 0F, ORIGIN + ticks(tick)),
                    "tick " + tick + " is inside the age");
        }

        assertTrue(this.gate.readingDue(SAMPLING, 0D, 64D, 0D, 0F, ORIGIN + ticks(10)));
    }

    // ------------------------------------------------------------------ the identity settings

    @Test
    void theIdentitySettingsReadOnEveryStepHoweverStillTheVehicleIs() {
        // What every vehicle did before these keys existed, and what the compatibility constructors of
        // VehicleSettings supply. All three thresholds are zero, so each comparison passes trivially —
        // including the one about elapsed time, which matters because a step's measured gap can be
        // shorter than a tick and an interval of one tick would then suppress readings on a loop
        // configured to take them every step.
        assertAll(
                () -> assertTrue(this.gate.readingDue(
                        TerrainSampling.EVERY_STEP, 0D, 64D, 0D, 0F, ORIGIN)),
                () -> assertTrue(this.gate.readingDue(
                        TerrainSampling.EVERY_STEP, 0D, 64D, 0D, 0F, ORIGIN)),
                () -> assertTrue(this.gate.readingDue(
                        TerrainSampling.EVERY_STEP, 0D, 64D, 0D, 0F, ORIGIN + 1L))
        );
    }

    // ------------------------------------------------------------------ the settings themselves

    @Test
    void aDeadbandWiderThanTheProbesOwnRunIsRefused() {
        // Structural rather than a matter of taste: past one column the reading describes ground the
        // vehicle has already driven into, and the gate overrides the deadband there regardless — so the
        // value would be a key an operator could set and watch do nothing.
        assertThrows(IllegalArgumentException.class,
                () -> new TerrainSampling(TerrainSample.PROBE_RUN_BLOCKS + 0.1F, 5F, 4, 10, false));
    }

    @Test
    void anAgeBelowTheIntervalIsRefusedBecauseItWouldMakeTheDeadbandInert() {
        // The age is the override and is checked first, so an age under the interval fires on the first
        // step past it: the configuration would describe a saving the server does not make.
        assertThrows(IllegalArgumentException.class,
                () -> new TerrainSampling(0.25F, 5F, 8, 4, false));
    }

    @Test
    void aNegativeThresholdIsRefusedRatherThanTreatedAsZero() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new TerrainSampling(-0.1F, 5F, 4, 10, false)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new TerrainSampling(0.25F, -1F, 4, 10, false)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new TerrainSampling(0.25F, 5F, -1, 10, false)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new TerrainSampling(0.25F, 5F, 4, -1, false))
        );
    }

    @Test
    void theTicksAreConvertedFromTheOneFactTheRestOfTheLayerUses() {
        // Derived from VehicleStepper's own nanoseconds-per-tick rather than from a figure of its own, so
        // that a reading's age and a step's elapsed time cannot disagree about how long a tick is.
        assertAll(
                () -> assertEquals(4L * VehicleStepper.NANOS_PER_TICK, SAMPLING.minIntervalNanos()),
                () -> assertEquals(10L * VehicleStepper.NANOS_PER_TICK, SAMPLING.maxAgeNanos()),
                () -> assertEquals(0.0625D, SAMPLING.moveDeadbandSquared(), 1.0E-9D)
        );
    }
}
