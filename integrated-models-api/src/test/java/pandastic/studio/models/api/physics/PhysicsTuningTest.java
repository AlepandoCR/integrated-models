package pandastic.studio.models.api.physics;

import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicsTuningTest {

    /** Deliberately unlike the constants the solver used to carry (260/120/40/60/2, 60 Hz, 4, 1.0E-5). */
    private static PhysicsTuning.Builder builder() {
        return PhysicsTuning.builder()
                .stiffnessToRate(97.5F)
                .dampingToRate(33.25F)
                .gravityToRate(17F)
                .windToRadians(7.125F)
                .turnToSpeed(0.75F)
                .substepRateHz(125)
                .maxSubsteps(9)
                .restEpsilonRadians(3.5E-4F);
    }

    @Test
    void refusesToBuildWithAnyCoefficientUnsupplied() {
        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> PhysicsTuning.builder().stiffnessToRate(1F).build());
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("no defaults"),
                        "it must say why, got: " + thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("dampingToRate"),
                        "and name what is missing"),
                () -> assertTrue(thrown.getMessage().contains("substepRateHz")),
                () -> assertTrue(!thrown.getMessage().contains("stiffnessToRate"),
                        "and not name what was supplied"));
    }

    @Test
    void buildsOnceEveryCoefficientIsSupplied() {
        assertDoesNotThrow(() -> builder().build());
    }

    @Test
    void refusesAConversionCoefficientThatWouldDeleteItsOwnTerm() {
        // Zero does not mean "off", it means the setting it scales silently stops existing — and a
        // spring with no restoring term is not a spring that rings slowly, it is one that never returns.
        assertAll(
                () -> assertRefused(b -> b.stiffnessToRate(0F), "stiffnessToRate"),
                () -> assertRefused(b -> b.stiffnessToRate(-1F), "stiffnessToRate"),
                () -> assertRefused(b -> b.dampingToRate(0F), "dampingToRate"),
                () -> assertRefused(b -> b.restEpsilonRadians(0F), "restEpsilonRadians"));
    }

    @Test
    void refusesANegativeRateButAcceptsZeroWhereZeroIsAWorld() {
        assertAll(
                // Gravity pointing upward contradicts a pendulum's rest pose rather than restyling it.
                () -> assertRefused(b -> b.gravityToRate(-1F), "gravityToRate"),
                () -> assertRefused(b -> b.windToRadians(-1F), "windToRadians"),
                () -> assertRefused(b -> b.turnToSpeed(-1F), "turnToSpeed"),
                // Zero gravity, no wind and no turn drag are all legitimate configurations.
                () -> assertDoesNotThrow(() -> builder().gravityToRate(0F).build()),
                () -> assertDoesNotThrow(() -> builder().windToRadians(0F).build()),
                () -> assertDoesNotThrow(() -> builder().turnToSpeed(0F).build()));
    }

    @Test
    void refusesAStepBudgetBelowOne() {
        assertAll(
                () -> assertRefused(b -> b.substepRateHz(0), "substepRateHz"),
                () -> assertRefused(b -> b.substepRateHz(-60), "substepRateHz"),
                // Zero substeps would make solve() a no-op, i.e. physics that silently does nothing.
                () -> assertRefused(b -> b.maxSubsteps(0), "maxSubsteps"));
    }

    @Test
    void refusesNaNAndInfinity() {
        assertAll(
                () -> assertRefused(b -> b.stiffnessToRate(Float.NaN), "stiffnessToRate"),
                () -> assertRefused(b -> b.dampingToRate(Float.POSITIVE_INFINITY), "dampingToRate"),
                () -> assertRefused(b -> b.gravityToRate(Float.NaN), "gravityToRate"),
                () -> assertRefused(b -> b.windToRadians(Float.NaN), "windToRadians"),
                () -> assertRefused(b -> b.turnToSpeed(Float.NaN), "turnToSpeed"),
                () -> assertRefused(b -> b.restEpsilonRadians(Float.NaN), "restEpsilonRadians"));
    }

    @Test
    void honoursADeliberatelyOddCalibrationRatherThanSubstitutingADefault() {
        final PhysicsTuning odd = builder().build();
        assertAll(
                () -> assertEquals(97.5F, odd.stiffnessToRate()),
                () -> assertEquals(33.25F, odd.dampingToRate()),
                () -> assertEquals(17F, odd.gravityToRate()),
                () -> assertEquals(7.125F, odd.windToRadians()),
                () -> assertEquals(0.75F, odd.turnToSpeed()),
                () -> assertEquals(125, odd.substepRateHz()),
                () -> assertEquals(9, odd.maxSubsteps()),
                () -> assertEquals(3.5E-4F, odd.restEpsilonRadians()));
    }

    @Test
    void derivesTheStepFromTheRateRatherThanTakingItSeparately() {
        // Two ways to say the same thing is two ways for them to disagree, and a step that does not
        // match the rate is exactly the condition the stability bound is stated against.
        assertAll(
                () -> assertEquals(1F / 125F, builder().build().substepSeconds(), 1.0E-9F),
                () -> assertEquals(1F / 60F, builder().substepRateHz(60).build().substepSeconds(),
                        1.0E-9F),
                () -> assertEquals(1F / 7F, builder().substepRateHz(7).build().substepSeconds(),
                        1.0E-9F));
    }

    @Test
    void theStabilityBoundIsAPropertyOfTheIntegratorAndNotAKnob() {
        // omega * h < 2 is where semi-implicit Euler's amplification factor leaves the unit disc. A
        // configurable version of it would be a switch labelled "permit NaN", so it stays a constant —
        // but the frequency it implies moves with the configured rate, which is the useful half.
        assertAll(
                () -> assertEquals(2F, PhysicsTuning.STABILITY_LIMIT),
                () -> assertEquals(250F, builder().build().maxStableAngularFrequency(), 1.0E-4F),
                () -> assertEquals(120F, builder().substepRateHz(60).build()
                        .maxStableAngularFrequency(), 1.0E-4F),
                () -> assertEquals(PhysicsTuning.STABILITY_LIMIT,
                        builder().build().maxStableAngularFrequency()
                                * builder().build().substepSeconds(), 1.0E-4F));
    }

    private static void assertRefused(
            UnaryOperator<PhysicsTuning.Builder> tweak,
            String expectedInMessage
    ) {
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tweak.apply(builder()).build());
        assertTrue(thrown.getMessage().contains(expectedInMessage),
                "it must name the coefficient, got: " + thrown.getMessage());
    }
}
