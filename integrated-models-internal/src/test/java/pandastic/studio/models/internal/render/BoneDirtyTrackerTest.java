package pandastic.studio.models.internal.render;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.rig.Rig;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dirty decision is the difference between a resting model costing nothing and costing its full
 * bone count forever, so the cases that matter are the boring ones: nothing moved, and something moved
 * two links up.
 */
class BoneDirtyTrackerTest {

    private static final float EPSILON = RenderFixtures.EPSILON_RADIANS;

    /** A three-link chain: 0 is the root, 1 hangs off it, 2 hangs off 1. */
    private static BoneDirtyTracker chain() {
        return new BoneDirtyTracker(new int[]{-1, 0, 1}, EPSILON);
    }

    /**
     * One emission pass. Visits every bone in ascending order, which is the order the ancestor
     * propagation requires, and hands back what each bone decided.
     */
    private static boolean[] pass(BoneDirtyTracker tracker, float... pitches) {
        final boolean[] admitted = new boolean[pitches.length];
        for (int bone = 0; bone < pitches.length; bone++) {
            admitted[bone] = tracker.admit(bone, pitches[bone], 0F, 0F);
        }
        return admitted;
    }

    @Test
    void admitsEveryBoneOnTheFirstPass() {
        // Nothing is known about what the client has yet, so everything has to go out once.
        assertArrayEquals(new boolean[]{true, true, true}, pass(chain(), 0F, 0F, 0F));
    }

    @Test
    void rejectsEveryBoneOfARestingRig() {
        final BoneDirtyTracker tracker = chain();
        pass(tracker, 0F, 0F, 0F);

        assertAll(
                () -> assertArrayEquals(new boolean[]{false, false, false}, pass(tracker, 0F, 0F, 0F)),
                () -> assertArrayEquals(new boolean[]{false, false, false}, pass(tracker, 0F, 0F, 0F)));
    }

    @Test
    void rejectsAChangeAtTheEpsilonAndAdmitsOneJustPastIt() {
        final BoneDirtyTracker atEpsilon = chain();
        pass(atEpsilon, 0F, 0F, 0F);
        final BoneDirtyTracker pastEpsilon = chain();
        pass(pastEpsilon, 0F, 0F, 0F);

        assertAll(
                () -> assertFalse(pass(atEpsilon, EPSILON, 0F, 0F)[0], "exactly the epsilon is not movement"),
                () -> assertTrue(pass(pastEpsilon, EPSILON * 1.5F, 0F, 0F)[0], "past it is"));
    }

    @Test
    void admitsAChildWhoseParentMovedEvenThoughItsOwnAnglesDidNot() {
        // The display transform is absolute: when a parent turns, a child's absolute rotation and
        // position change even though its own angles are untouched. Missing this leaves a chain behind
        // its own root until its own angles happen to move.
        final BoneDirtyTracker tracker = chain();
        pass(tracker, 0F, 0F, 0F);

        assertArrayEquals(new boolean[]{true, true, true}, pass(tracker, 1F, 0F, 0F),
                "a root turning must carry the whole chain onto the wire");
    }

    @Test
    void propagatesDownwardOnlyAndNotUpward() {
        final BoneDirtyTracker tracker = chain();
        pass(tracker, 0F, 0F, 0F);

        assertArrayEquals(new boolean[]{false, true, true}, pass(tracker, 0F, 1F, 0F),
                "the middle link moved: its parent is unaffected, its child is not");
    }

    @Test
    void doesNotCarryASiblingAlong() {
        final BoneDirtyTracker siblings = new BoneDirtyTracker(new int[]{-1, 0, 0}, EPSILON);
        pass(siblings, 0F, 0F, 0F);

        assertArrayEquals(new boolean[]{false, true, false}, pass(siblings, 0F, 1F, 0F));
    }

    @Test
    void accumulatesSubEpsilonDriftUntilItCrossesTheThreshold() {
        // The baseline is what was last emitted, not what was last seen, so slow drift arrives late
        // rather than never. A per-pass comparison would discard it a fraction at a time forever.
        final BoneDirtyTracker tracker = chain();
        pass(tracker, 0F, 0F, 0F);

        assertAll(
                () -> assertFalse(pass(tracker, EPSILON * 0.4F, 0F, 0F)[0]),
                () -> assertFalse(pass(tracker, EPSILON * 0.8F, 0F, 0F)[0]),
                () -> assertTrue(pass(tracker, EPSILON * 1.2F, 0F, 0F)[0], "the drift must eventually land"));
    }

    @Test
    void freezesABoneWhoseAnglesWentNonFiniteWithoutPoisoningItsBaseline() {
        final BoneDirtyTracker tracker = chain();
        pass(tracker, 0.5F, 0F, 0F);

        assertAll(
                () -> assertFalse(tracker.admit(0, Float.NaN, 0F, 0F), "NaN must never reach the client"),
                () -> assertFalse(tracker.admit(0, Float.POSITIVE_INFINITY, 0F, 0F)),
                // The baseline is still 0.5, so returning to it is still "unchanged": the bad value was
                // not recorded and the bone is recoverable rather than permanently out of sync.
                () -> assertFalse(tracker.admit(0, 0.5F, 0F, 0F)),
                () -> assertTrue(tracker.admit(0, 1.5F, 0F, 0F)));
    }

    @Test
    void aNonFiniteBoneDoesNotForceItsChildren() {
        final BoneDirtyTracker tracker = chain();
        pass(tracker, 0F, 0F, 0F);

        assertAll(
                () -> assertFalse(tracker.admit(0, Float.NaN, 0F, 0F)),
                () -> assertFalse(tracker.admit(1, 0F, 0F, 0F), "a frozen parent must not drag its child"));
    }

    @Test
    void resetMakesTheWholeRigDirtyAgain() {
        // The baseline describes what the client has. A respawned display or a viewer coming into range
        // invalidates that, and the only safe answer is to resend everything.
        final BoneDirtyTracker tracker = chain();
        pass(tracker, 0.2F, 0.3F, 0.4F);
        assertArrayEquals(new boolean[]{false, false, false}, pass(tracker, 0.2F, 0.3F, 0.4F));

        tracker.reset();

        assertArrayEquals(new boolean[]{true, true, true}, pass(tracker, 0.2F, 0.3F, 0.4F));
    }

    @Test
    void derivesTheHierarchyFromARig() {
        final BoneDirtyTracker tracker = BoneDirtyTracker.of(cloak(), RenderFixtures.SETTINGS);
        pass(tracker, 0F, 0F, 0F);

        assertAll(
                () -> assertEquals(3, tracker.boneCount()),
                () -> assertEquals(EPSILON, tracker.epsilonRadians()),
                () -> assertArrayEquals(new boolean[]{true, true, true}, pass(tracker, 1F, 0F, 0F),
                        "the rig's parent links must drive the propagation"));
    }

    private static Rig cloak() {
        return RenderFixtures.rig("cloak", "npc")
                .root("body")
                .bone("cape", "body", null)
                .bone("cape_tip", "cape", null)
                .build();
    }

    @Test
    void takesTheEpsilonFromTheSettingsRatherThanAnyDefault() {
        // The injected value has to reach the comparison, not merely be stored. A tracker that kept a
        // default would agree with this test's first assertion by coincidence and fail the rest.
        final float huge = 0.5F;
        final BoneDirtyTracker configured =
                BoneDirtyTracker.of(cloak(), new RenderSettings(huge, 8));

        pass(configured, 0F, 0F, 0F);

        assertAll(
                () -> assertEquals(huge, configured.epsilonRadians()),
                // A tenth of a radian is three orders of magnitude past the stock epsilon and still
                // inside this one, so a tracker ignoring its settings would admit all three bones.
                () -> assertArrayEquals(new boolean[]{false, false, false}, pass(configured, 0.1F, 0F, 0F),
                        "a configured epsilon of 0.5 rad must swallow a 0.1 rad move"),
                () -> assertArrayEquals(new boolean[]{true, true, true}, pass(configured, 0.6F, 0F, 0F),
                        "and still admit one past it"));
    }

    @Test
    void aZeroEpsilonFromSettingsMakesEveryChangedBoneDirty() {
        // The "emit everything" end of the lever: an operator who wants no deduplication at all.
        final BoneDirtyTracker exact =
                BoneDirtyTracker.of(cloak(), new RenderSettings(0F, 4));
        pass(exact, 0F, 0F, 0F);

        assertAll(
                () -> assertEquals(0F, exact.epsilonRadians()),
                () -> assertArrayEquals(new boolean[]{true, true, true},
                        pass(exact, Float.MIN_VALUE, 0F, 0F),
                        "the smallest representable change must reach the wire at epsilon zero"),
                () -> assertArrayEquals(new boolean[]{false, false, false},
                        pass(exact, Float.MIN_VALUE, 0F, 0F),
                        "but a bone that did not move at all still costs nothing"));
    }

    @Test
    void anEnormousEpsilonFromSettingsSilencesTheRigAfterItsFirstPass() {
        // The other end: a threshold above any motion in the rig. Worth pinning because it is the
        // misconfiguration that presents as "the model does not move", and it should be reachable
        // through configuration rather than only through a code change.
        final BoneDirtyTracker deaf =
                BoneDirtyTracker.of(cloak(), new RenderSettings(1_000F, 4));

        assertAll(
                () -> assertArrayEquals(new boolean[]{true, true, true}, pass(deaf, 0F, 0F, 0F),
                        "the first pass always goes out, whatever the threshold"),
                () -> assertArrayEquals(new boolean[]{false, false, false}, pass(deaf, 100F, 0F, 0F)));
    }

    @Test
    void refusesABoneListThatIsNotTopological() {
        // The single-pass propagation reads the decision already made for a bone's parent, so a parent
        // that follows its child would silently read last pass's answer.
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new BoneDirtyTracker(new int[]{1, -1}, EPSILON)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new BoneDirtyTracker(new int[]{0}, EPSILON)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new BoneDirtyTracker(new int[0], EPSILON)));
    }

    @Test
    void refusesANonsensicalEpsilon() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new BoneDirtyTracker(new int[]{-1}, -1F)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new BoneDirtyTracker(new int[]{-1}, Float.NaN)));
    }

    @Test
    void aZeroEpsilonStillRejectsAnExactlyUnchangedBone() {
        // Worth pinning: an operator who wants every change emitted should still not pay for a model
        // that is standing perfectly still.
        final BoneDirtyTracker exact = new BoneDirtyTracker(new int[]{-1}, 0F);
        pass(exact, 0.25F);

        assertAll(
                () -> assertFalse(pass(exact, 0.25F)[0]),
                () -> assertTrue(pass(exact, Math.nextUp(0.25F))[0]));
    }
}
