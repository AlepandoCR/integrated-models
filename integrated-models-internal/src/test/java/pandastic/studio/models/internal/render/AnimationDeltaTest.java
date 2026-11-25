package pandastic.studio.models.internal.render;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic that lets this plugin's pose be composed on top of a clip the renderer is playing,
 * rather than against the static rest pose it used to be composed against.
 *
 * <p>Every test here is about one property: {@code Q(rest) · Q(delta)} has to equal the single quaternion
 * the renderer builds from the summed Euler triple, {@code Q(rest + clip)}. That is what makes two writers
 * to one display agree instead of racing.
 */
class AnimationDeltaTest {

    private static final float TOLERANCE = 1.0E-5F;

    /** What the renderer sends: one quaternion from the summed triple, in its ZYX convention. */
    private static Quaternionf rendererRotation(Vector3f restDegrees, Vector3f clipDegrees) {
        return AnimationDelta.toQuaternion(new Vector3f(restDegrees).add(clipDegrees), new Quaternionf());
    }

    /**
     * Whether two quaternions are the same <em>rotation</em> — which is not the same question as
     * whether their components match.
     *
     * <p>{@code q} and {@code -q} are one rotation, the double cover of the unit quaternions, and a
     * composition whose result crosses a hemisphere boundary returns the opposite sign as a matter of
     * course. Comparing components therefore fails on a rotation that is exactly right, which is what
     * it did here for a clip of {@code (-120, 95, 160)}: summed with the rest pose that is a total of
     * {@code (-137, 136, 168)}, far enough round for the sign to flip. Aligning the signs first asks
     * the question the production code actually has to get right.
     */
    private static boolean sameRotation(Quaternionf ours, Quaternionf theirs) {
        final float dot = ours.x * theirs.x + ours.y * theirs.y
                + ours.z * theirs.z + ours.w * theirs.w;
        final Quaternionf aligned = dot < 0F
                ? new Quaternionf(-ours.x, -ours.y, -ours.z, -ours.w)
                : ours;
        return aligned.equals(theirs, TOLERANCE);
    }

    /** What we send: the rest orientation composed with the delta this class produced. */
    private static Quaternionf composed(Vector3f restDegrees, Vector3f clipDegrees) {
        final Vector3f live = new Vector3f(restDegrees).add(clipDegrees);
        final Vector3f delta = AnimationDelta.of(
                AnimationDelta.restInverse(restDegrees, new Quaternionf()),
                live,
                new Quaternionf(),
                new Quaternionf(),
                new Vector3f());
        return AnimationDelta.toQuaternion(restDegrees, new Quaternionf())
                .mul(PoseQuaternions.fromEuler(delta.x, delta.y, delta.z, new Quaternionf()));
    }

    @Test
    void composingTheDeltaOntoRestReproducesWhatTheRendererSends() {
        // The whole point. A bone authored at an angle, with a clip turning it about a different axis:
        // the case where an Euler subtraction is wrong and nothing in a unit test of one axis notices.
        final Vector3f rest = new Vector3f(12F, -30F, 5F);
        final Vector3f clip = new Vector3f(0F, 20F, 0F);

        assertTrue(sameRotation(composed(rest, clip), rendererRotation(rest, clip)));
    }

    @Test
    void aPlainEulerSubtractionIsVisiblyWrongOnTheSameInput() {
        // Documents why the quaternion route exists rather than leaving it to a comment. If this ever
        // starts passing, the two conventions have converged and this class has become unnecessary.
        final Vector3f rest = new Vector3f(12F, -30F, 5F);
        final Vector3f clip = new Vector3f(0F, 20F, 0F);

        final Quaternionf naive = AnimationDelta.toQuaternion(rest, new Quaternionf())
                .mul(AnimationDelta.toQuaternion(clip, new Quaternionf()));
        final float errorDegrees = (float) Math.toDegrees(
                naive.difference(rendererRotation(rest, clip), new Quaternionf()).angle());

        assertTrue(errorDegrees > 1F,
                "expected the naive subtraction to be visibly wrong, but it was off by only "
                        + errorDegrees + " degrees");
    }

    @Test
    void agreesWithTheRendererOnEveryCombinationOfAxes() {
        final Vector3f rest = new Vector3f(-17F, 41F, 8F);
        final Vector3f[] clips = {
                new Vector3f(11F, 0F, 0F),
                new Vector3f(0F, -23F, 0F),
                new Vector3f(0F, 0F, 37F),
                new Vector3f(14F, -9F, 6F),
                new Vector3f(-120F, 95F, 160F),
        };

        for (final Vector3f clip : clips) {
            assertTrue(sameRotation(composed(rest, clip), rendererRotation(rest, clip)),
                    "disagreed for clip " + clip);
        }
    }

    @Test
    void anUnrotatedBoneMakesTheDeltaTheClipItself() {
        // The common case in Blockbench: most bones are authored at zero, where the exact route and the
        // naive one coincide. Worth pinning so the exact route cannot regress into something that is
        // only right for rotated bones.
        final Vector3f rest = new Vector3f();
        final Vector3f clip = new Vector3f(25F, -40F, 15F);

        final Vector3f delta = AnimationDelta.of(
                AnimationDelta.restInverse(rest, new Quaternionf()),
                clip, new Quaternionf(), new Quaternionf(), new Vector3f());

        assertAll(
                () -> assertEquals(Math.toRadians(25D), delta.x, TOLERANCE),
                () -> assertEquals(Math.toRadians(-40D), delta.y, TOLERANCE),
                () -> assertEquals(Math.toRadians(15D), delta.z, TOLERANCE));
    }

    @Test
    void isRestIsAnExactTestAndNotATolerance() {
        final Vector3f rest = new Vector3f(12F, -30F, 5F);

        assertAll(
                () -> assertTrue(AnimationDelta.isRest(rest, new Vector3f(12F, -30F, 5F))),
                // A renderer copies the default frame for a bone no keyframe touches, so "nearly rest"
                // never arises from that path — and when it does arise it is a real, small rotation that
                // has to be composed rather than discarded.
                () -> assertFalse(AnimationDelta.isRest(rest, new Vector3f(12F, -30.0001F, 5F))));
    }

    @Test
    void theRestTestIsWhatMakesTheNoClipCaseExactAndNotTheArithmetic() {
        // The exactness guarantee the whole COMPOSE policy rests on, and the measurement that shows why
        // it has to be a short-circuit rather than a property of the maths. A float inverse of a unit
        // quaternion is only nearly an inverse, so the composed delta is only nearly zero — and "nearly
        // zero" is a delta as far as AnimatedRotations#set is concerned, which would nudge every
        // animation-owned bone that no clip is touching.
        float worst = 0F;
        for (int sample = 0; sample < 2_000; sample++) {
            final Vector3f rest = new Vector3f(
                    sample * 0.37F % 360F - 180F,
                    sample * 1.13F % 360F - 180F,
                    sample * 2.71F % 360F - 180F);
            final Vector3f delta = AnimationDelta.of(
                    AnimationDelta.restInverse(rest, new Quaternionf()),
                    new Vector3f(rest), new Quaternionf(), new Quaternionf(), new Vector3f());
            worst = Math.max(worst,
                    Math.max(Math.abs(delta.x), Math.max(Math.abs(delta.y), Math.abs(delta.z))));
            assertTrue(AnimationDelta.isRest(rest, new Vector3f(rest)),
                    "the rest test has to catch every authored orientation, and missed " + rest);
        }

        // Small enough to be invisible as motion, and deliberately not asserted to be zero: over the
        // whole range of authored rotations it is not, which is exactly why the short-circuit above is
        // the guarantee rather than this number.
        final float measured = worst;
        assertTrue(measured < 1.0E-6F, "unexpectedly large residual: " + measured);
    }

    @Test
    void aZeroDeltaIsRecordedAsAbsentByTheOverlay() {
        // What the producer writes for a bone its clip is not moving: exact zeroes, including the signed
        // zero a decomposition can legitimately produce.
        final AnimatedRotations overlay = AnimatedRotations.of(
                RenderFixtures.rig("one", "model").bone("body", null, null, true).build());
        overlay.set(0, 0F, -0F, 0F);

        assertAll(
                () -> assertFalse(overlay.hasDelta(0)),
                () -> assertEquals(0, overlay.deltaCount()));
    }

    @Test
    void theDecompositionInvertsTheConversion() {
        final Vector3f degrees = new Vector3f(33F, -12F, 71F);
        final Quaternionf quaternion = AnimationDelta.toQuaternion(degrees, new Quaternionf());

        final Vector3f back = quaternion.getEulerAnglesZYX(new Vector3f());
        final Quaternionf rebuilt = PoseQuaternions.fromEuler(back.x, back.y, back.z, new Quaternionf());

        assertAll(
                () -> assertTrue(rebuilt.equals(quaternion, TOLERANCE)),
                // Not the identity, so the round trip above is actually saying something.
                () -> assertNotEquals(0F, back.x));
    }
}
