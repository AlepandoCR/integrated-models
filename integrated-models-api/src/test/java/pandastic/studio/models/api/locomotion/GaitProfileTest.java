package pandastic.studio.models.api.locomotion;

import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ranges matter for one reason that is not aesthetic: the phase advance divides by
 * {@link GaitProfile#strideLength()}, so a stride near zero turns any movement into an unbounded number
 * of cycles per step. Everything else is a scale on an angle, where the worst case is a limb passing
 * through the body.
 *
 * <p>Two layers are under test and they are not interchangeable. The canonical constructor enforces the
 * arithmetic — NaN, infinity, a non-positive stride, a negative magnitude, a fraction out of
 * {@code [0, 1]} — and {@link GaitProfile#requireWithin(BindingBounds)} enforces the operator's ranges.
 * Every range assertion below deliberately uses bounds that disagree with the constants this type used
 * to carry, so a leftover fallback would fail rather than coincide.
 */
class GaitProfileTest {

    private static final BindingBounds BOUNDS = GaitFixtures.bounds();
    private static final GaitTuning TUNING = GaitFixtures.tuning();

    @Test
    void defaultsToTheCalibratedLookAtTheConfiguredProportions() {
        final GaitProfile profile = GaitProfile.defaults(TUNING);
        assertAll(
                () -> assertEquals(GaitFixtures.DEFAULT_STRIDE, profile.strideLength()),
                () -> assertEquals(1F, profile.legSwing()),
                () -> assertEquals(1F, profile.armSwing()),
                () -> assertEquals(1F, profile.torsoSway()),
                () -> assertEquals(GaitFixtures.DEFAULT_HEAD_STABILISE, profile.headStabilise()),
                () -> assertEquals(GaitFixtures.DEFAULT_HAND_STABILISE, profile.handStabilise()),
                () -> assertEquals(1F, profile.sneakDepth()),
                () -> assertEquals(1F, profile.breathDepth()),
                () -> assertEquals(profile, GaitProfile.builder(TUNING).build(),
                        "defaults(tuning) is just the builder's seed"));
    }

    @Test
    void theSeededDefaultsFollowTheTuningRatherThanAnyConstant() {
        // The property the owner cares about: a deliberately odd tuning must reach the profile. These
        // three numbers are nothing like the 1.3, 0.65 and 0.35 the declarations default to, so a
        // surviving fallback shows up here immediately.
        final GaitTuning odd = GaitFixtures.tuningBuilder()
                .defaultStrideLength(2.37F)
                .defaultHeadStabilise(0.04F)
                .defaultHandStabilise(0.71F)
                .build();
        final GaitProfile profile = GaitProfile.defaults(odd);

        assertAll(
                () -> assertEquals(2.37F, profile.strideLength()),
                () -> assertEquals(0.04F, profile.headStabilise()),
                () -> assertEquals(0.71F, profile.handStabilise()));
    }

    @Test
    void refusesAStrideOutsideTheConfiguredRange() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> profileWithStride(GaitFixtures.MIN_STRIDE - 0.01F).requireWithin(BOUNDS)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> profileWithStride(GaitFixtures.MAX_STRIDE + 0.01F).requireWithin(BOUNDS)),
                () -> assertDoesNotThrow(
                        () -> profileWithStride(GaitFixtures.MIN_STRIDE).requireWithin(BOUNDS)),
                () -> assertDoesNotThrow(
                        () -> profileWithStride(GaitFixtures.MAX_STRIDE).requireWithin(BOUNDS)),
                // The removed constants were 0.1 and 8. Both now sit outside the configured range, so
                // either one being honoured would mean the bound came from somewhere other than BOUNDS.
                () -> assertThrows(IllegalArgumentException.class,
                        () -> profileWithStride(0.1F).requireWithin(BOUNDS)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> profileWithStride(8F).requireWithin(BOUNDS)));
    }

    @Test
    void refusesANonPositiveStrideWithoutNeedingBoundsAtAll() {
        // A divisor of zero is arithmetic rather than policy, so the canonical constructor owns it and
        // no configuration can widen the range into it.
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> GaitProfile.builder(TUNING).strideLength(0F).build()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> GaitProfile.builder(TUNING).strideLength(-1F).build()));
    }

    @Test
    void refusesScalesBeyondTheConfiguredExaggerationCeiling() {
        final float past = GaitFixtures.MAX_SCALE + 0.01F;
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> scaled(builder -> builder.legSwing(past))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> scaled(builder -> builder.armSwing(past))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> scaled(builder -> builder.torsoSway(past))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> scaled(builder -> builder.breathDepth(past))),
                // The removed ceiling was 2, which this configuration puts out of range.
                () -> assertThrows(IllegalArgumentException.class,
                        () -> scaled(builder -> builder.legSwing(2F))),
                () -> assertDoesNotThrow(
                        () -> scaled(builder -> builder.legSwing(GaitFixtures.MAX_SCALE))));
    }

    @Test
    void refusesAFractionBeyondOneWithoutNeedingBounds() {
        // The two stabilisations and sneakDepth are fractions of something already calibrated rather
        // than scales, so their ceiling is what the word "fraction" means and is not an operator's
        // choice. A hand stabilisation past 1 would over-rotate past level and swing the palm the
        // opposite way, which is not a stronger version of holding it still.
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> GaitProfile.builder(TUNING).headStabilise(1.01F).build()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> GaitProfile.builder(TUNING).handStabilise(1.01F).build()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> GaitProfile.builder(TUNING).sneakDepth(1.01F).build()),
                () -> assertDoesNotThrow(() -> GaitProfile.builder(TUNING).headStabilise(1F).build()),
                () -> assertDoesNotThrow(() -> GaitProfile.builder(TUNING).handStabilise(1F).build()),
                () -> assertDoesNotThrow(() -> GaitProfile.builder(TUNING).handStabilise(0F).build()),
                () -> assertDoesNotThrow(() -> GaitProfile.builder(TUNING).sneakDepth(1F).build()));
    }

    @Test
    void refusesANegativeScale() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> GaitProfile.builder(TUNING).legSwing(-0.01F).build()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> GaitProfile.builder(TUNING).armSwing(-1F).build()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> GaitProfile.builder(TUNING).torsoSway(-1F).build()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> GaitProfile.builder(TUNING).headStabilise(-1F).build()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> GaitProfile.builder(TUNING).handStabilise(-1F).build()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> GaitProfile.builder(TUNING).sneakDepth(-1F).build()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> GaitProfile.builder(TUNING).breathDepth(-1F).build()));
    }

    @Test
    void refusesNaNOnEveryField() {
        // NaN fails a range comparison silently — `NaN < min` is false — so it needs its own check, and
        // a NaN scale would reach the client as a bone that vanishes.
        assertAll(
                () -> assertNaNRefused(GaitProfile.builder(TUNING).strideLength(Float.NaN),
                        "strideLength"),
                () -> assertNaNRefused(GaitProfile.builder(TUNING).legSwing(Float.NaN), "legSwing"),
                () -> assertNaNRefused(GaitProfile.builder(TUNING).armSwing(Float.NaN), "armSwing"),
                () -> assertNaNRefused(GaitProfile.builder(TUNING).torsoSway(Float.NaN), "torsoSway"),
                () -> assertNaNRefused(GaitProfile.builder(TUNING).headStabilise(Float.NaN),
                        "headStabilise"),
                () -> assertNaNRefused(GaitProfile.builder(TUNING).handStabilise(Float.NaN),
                        "handStabilise"),
                () -> assertNaNRefused(GaitProfile.builder(TUNING).sneakDepth(Float.NaN), "sneakDepth"),
                () -> assertNaNRefused(GaitProfile.builder(TUNING).breathDepth(Float.NaN),
                        "breathDepth"));
    }

    private static void assertNaNRefused(GaitProfile.Builder builder, String field) {
        final IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(thrown.getMessage().contains(field),
                "it must name the field, got: " + thrown.getMessage());
    }

    @Test
    void refusesANonFiniteStride() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> GaitProfile.builder(TUNING).strideLength(Float.POSITIVE_INFINITY).build()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> GaitProfile.builder(TUNING).strideLength(Float.NEGATIVE_INFINITY).build()));
    }

    @Test
    void zeroIsLegalForEveryScaleBecauseAStatueIsAValidStyle() {
        final GaitProfile statue = assertDoesNotThrow(() -> GaitProfile.builder(TUNING)
                .legSwing(0F)
                .armSwing(0F)
                .torsoSway(0F)
                .headStabilise(0F)
                .handStabilise(0F)
                .sneakDepth(0F)
                .breathDepth(0F)
                .build()
                .requireWithin(BOUNDS));
        assertEquals(0F, statue.breathDepth());
    }

    @Test
    void theConfiguredCeilingIsLegalBecauseExaggerationIsAStyle() {
        assertDoesNotThrow(() -> GaitProfile.builder(TUNING)
                .legSwing(GaitFixtures.MAX_SCALE)
                .armSwing(GaitFixtures.MAX_SCALE)
                .torsoSway(GaitFixtures.MAX_SCALE)
                .breathDepth(GaitFixtures.MAX_SCALE)
                .build()
                .requireWithin(BOUNDS));
    }

    @Test
    void requireWithinReturnsTheSameInstanceSoItChains() {
        final GaitProfile profile = GaitProfile.defaults(TUNING);
        assertSame(profile, profile.requireWithin(BOUNDS));
    }

    @Test
    void withStrideLengthChangesOnlyTheStride() {
        final GaitProfile source = GaitProfile.builder(TUNING)
                .legSwing(1.2F)
                .armSwing(0.25F)
                .torsoSway(0F)
                .headStabilise(0.1F)
                .handStabilise(0.62F)
                .sneakDepth(0.4F)
                .breathDepth(1.1F)
                .build();
        final GaitProfile scaled = source.withStrideLength(2.5F);

        assertAll(
                () -> assertEquals(2.5F, scaled.strideLength()),
                () -> assertEquals(source.withStrideLength(source.strideLength()), source,
                        "records compare by value, so a no-op rescale must equal the original"),
                () -> assertEquals(source.legSwing(), scaled.legSwing()),
                () -> assertEquals(source.armSwing(), scaled.armSwing()),
                () -> assertEquals(source.torsoSway(), scaled.torsoSway()),
                () -> assertEquals(source.headStabilise(), scaled.headStabilise()),
                () -> assertEquals(source.handStabilise(), scaled.handStabilise()),
                () -> assertEquals(source.sneakDepth(), scaled.sneakDepth()),
                () -> assertEquals(source.breathDepth(), scaled.breathDepth()),
                () -> assertThrows(IllegalArgumentException.class, () -> source.withStrideLength(0F)),
                // 2.5 is inside both layers; 3.5 is inside the arithmetic invariants but outside this
                // configuration's range, which is exactly why the two checks are separate.
                () -> assertDoesNotThrow(() -> scaled.requireWithin(BOUNDS)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> source.withStrideLength(3.5F).requireWithin(BOUNDS)));
    }

    @Test
    void theBuilderRoundTripsEveryField() {
        final GaitProfile built = GaitProfile.builder(TUNING)
                .strideLength(1.75F)
                .legSwing(0.5F)
                .armSwing(0.6F)
                .torsoSway(0.7F)
                .headStabilise(0.8F)
                .handStabilise(0.85F)
                .sneakDepth(0.9F)
                .breathDepth(1.1F)
                .build();
        assertEquals(new GaitProfile(1.75F, 0.5F, 0.6F, 0.7F, 0.8F, 0.85F, 0.9F, 1.1F), built);
    }

    private static GaitProfile profileWithStride(float strideLength) {
        return GaitProfile.builder(TUNING).strideLength(strideLength).build();
    }

    private static GaitProfile scaled(UnaryOperator<GaitProfile.Builder> tweak) {
        return tweak.apply(GaitProfile.builder(TUNING)).build().requireWithin(BOUNDS);
    }
}
