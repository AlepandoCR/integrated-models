package pandastic.studio.models.api.input;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two things are being asserted here and they are of different kinds.
 *
 * <p>The first is that each form matches what it says it matches, which is ordinary. The second is that
 * {@link InputCombination#relationTo(InputCombination)} is <strong>sound</strong>: it may say two
 * bindings overlap when the timing in fact keeps them apart, and it must never say they are independent
 * when they can both fire, because a missed shadow is a consumer debugging a handler that runs twice
 * with nothing to look at. That asymmetry is the reason the analysis exists at all, so it is the part
 * tested hardest.
 */
@DisplayName("InputCombination")
final class InputCombinationTest {

    private static final long MILLISECOND = InputTuning.NANOS_PER_MILLISECOND;
    private static final long NOW = 10_000L * MILLISECOND;

    private static final InputTuning TUNING = InputTuning.ofMillis(250L, 500L, 250L, 1_500L);

    // ------------------------------------------------------------------- matching

    @Test
    @DisplayName("a chord matches only when every key is down")
    void chord() {
        final InputCombination sprintJump = InputCombination.chord(InputKey.SPRINT, InputKey.JUMP);

        assertTrue(sprintJump.matches(held(InputKey.SPRINT, InputKey.JUMP), TUNING));
        assertFalse(sprintJump.matches(held(InputKey.SPRINT), TUNING));
        assertFalse(sprintJump.matches(held(), TUNING));
        // Extra keys do not disqualify a chord: it says what must be down, not what must not be.
        assertTrue(sprintJump.matches(held(InputKey.SPRINT, InputKey.JUMP, InputKey.SNEAK), TUNING));
    }

    @Test
    @DisplayName("without() excludes a modifier and keeps the combination a conjunction")
    void without() {
        final InputCombination useNotSneaking =
                InputCombination.held(InputKey.USE).without(InputKey.SNEAK);

        assertTrue(useNotSneaking.matches(held(InputKey.USE), TUNING));
        assertFalse(useNotSneaking.matches(held(InputKey.USE, InputKey.SNEAK), TUNING));
        assertTrue(useNotSneaking.isConjunction(),
                "a chord plus an exclusion has to stay exact, or shadow detection degrades");
        assertEquals(Set.of(InputKey.USE), useNotSneaking.requiredKeys());
        assertEquals(Set.of(InputKey.SNEAK), useNotSneaking.forbiddenKeys());
    }

    @Test
    @DisplayName("a press is an edge, a hold is a duration")
    void edgesAndDurations() {
        final InputTrace pressed = InputSnapshot.ofFlags(
                InputState.of(InputKey.JUMP), InputState.NONE, NOW);
        final InputTrace heldLong = trace(InputKey.SNEAK, 600L * MILLISECOND, InputTrace.NO_EARLIER_PRESS);
        final InputTrace heldBriefly = trace(InputKey.SNEAK, 100L * MILLISECOND, InputTrace.NO_EARLIER_PRESS);

        assertTrue(InputCombination.pressed(InputKey.JUMP).matches(pressed, TUNING));
        assertFalse(InputCombination.pressed(InputKey.JUMP)
                .matches(held(InputKey.JUMP), TUNING), "a key already down is not a press");

        assertTrue(InputCombination.hold(InputKey.SNEAK).matches(heldLong, TUNING));
        assertFalse(InputCombination.hold(InputKey.SNEAK).matches(heldBriefly, TUNING));
        // An explicit duration is the consumer's own statement and overrides the configured one.
        assertTrue(InputCombination.hold(InputKey.SNEAK, 50L * MILLISECOND)
                .matches(heldBriefly, TUNING));
    }

    @Test
    @DisplayName("a double tap needs a press and a recent previous press")
    void doubleTap() {
        final InputTrace inside = pressedWithGap(200L * MILLISECOND);
        final InputTrace outside = pressedWithGap(400L * MILLISECOND);
        final InputTrace first = pressedWithGap(InputTrace.NO_EARLIER_PRESS);

        assertTrue(InputCombination.doubleTap(InputKey.FORWARD).matches(inside, TUNING));
        assertFalse(InputCombination.doubleTap(InputKey.FORWARD).matches(outside, TUNING));
        assertFalse(InputCombination.doubleTap(InputKey.FORWARD).matches(first, TUNING),
                "a single press must not count as a double tap");
        assertTrue(InputCombination.doubleTap(InputKey.FORWARD, 500L * MILLISECOND)
                .matches(outside, TUNING));
    }

    @Test
    @DisplayName("anyOf matches when a single branch does")
    void anyOf() {
        final InputCombination either = InputCombination.anyOf(List.of(
                InputCombination.held(InputKey.LEFT), InputCombination.held(InputKey.RIGHT)));

        assertTrue(either.matches(held(InputKey.LEFT), TUNING));
        assertTrue(either.matches(held(InputKey.RIGHT), TUNING));
        assertFalse(either.matches(held(InputKey.FORWARD), TUNING));
    }

    // -------------------------------------------------------------- normalisation

    @Test
    @DisplayName("nesting and duplication are flattened, so one condition has one representation")
    void flattening() {
        final InputCombination nested = InputCombination.held(InputKey.SPRINT)
                .and(InputCombination.held(InputKey.JUMP))
                .and(InputCombination.held(InputKey.SNEAK));
        final InputCombination flat =
                InputCombination.chord(InputKey.SPRINT, InputKey.JUMP, InputKey.SNEAK);

        // If these compared unequal, a registry would report two descriptions of one condition as
        // merely overlapping, which is the one answer that actively misleads.
        assertEquals(flat, nested);
        assertEquals(InputRelation.EQUIVALENT, flat.relationTo(nested));

        // A repeated part is the same condition, so it collapses to the single part.
        assertEquals(InputCombination.held(InputKey.SPRINT),
                InputCombination.held(InputKey.SPRINT).and(InputCombination.held(InputKey.SPRINT)));
    }

    @Test
    @DisplayName("an empty chord is refused rather than matching everything")
    void emptyChord() {
        assertThrows(IllegalArgumentException.class, () -> InputCombination.chord(new InputKey[0]));
        assertThrows(IllegalArgumentException.class, () -> InputCombination.allOf(List.of()));
        assertThrows(IllegalArgumentException.class, () -> InputCombination.anyOf(List.of()));
    }

    // ------------------------------------------------------------ satisfiability

    @Test
    @DisplayName("requiring and forbidding one key is unsatisfiable")
    void contradiction() {
        final InputCombination impossible =
                InputCombination.held(InputKey.SNEAK).without(InputKey.SNEAK);

        assertFalse(impossible.isSatisfiable());
        assertFalse(impossible.matches(held(InputKey.SNEAK), TUNING));
        assertFalse(impossible.matches(held(), TUNING));
    }

    @Test
    @DisplayName("a hold on a momentary input is unsatisfiable, and says so instead of never firing")
    void holdOnPulse() {
        assertEquals(InputKind.PULSE, InputKey.ATTACK.kind());
        assertFalse(InputCombination.hold(InputKey.ATTACK).isSatisfiable());
        // A hold on a held key is fine, which is what makes the previous assertion a statement about
        // the key's kind rather than about holds.
        assertTrue(InputCombination.hold(InputKey.SNEAK).isSatisfiable());
    }

    // ---------------------------------------------------------------- relations

    @Test
    @DisplayName("a weaker conjunction shadows a stronger one, in both directions")
    void shadowing() {
        final InputCombination sneak = InputCombination.held(InputKey.SNEAK);
        final InputCombination sneakUse = InputCombination.chord(InputKey.SNEAK, InputKey.USE);

        assertEquals(InputRelation.SHADOWS, sneak.relationTo(sneakUse));
        assertEquals(InputRelation.SHADOWED_BY, sneakUse.relationTo(sneak));

        // And the claim the relation makes is true of the matching itself.
        final InputTrace both = held(InputKey.SNEAK, InputKey.USE);
        assertTrue(sneakUse.matches(both, TUNING));
        assertTrue(sneak.matches(both, TUNING),
                "the shadowing binding must really fire whenever the shadowed one does");
    }

    @Test
    @DisplayName("an exclusion makes two bindings independent")
    void independence() {
        final InputCombination useSneaking = InputCombination.chord(InputKey.USE, InputKey.SNEAK);
        final InputCombination useNotSneaking =
                InputCombination.held(InputKey.USE).without(InputKey.SNEAK);

        assertEquals(InputRelation.INDEPENDENT, useSneaking.relationTo(useNotSneaking));
        assertEquals(InputRelation.INDEPENDENT, useNotSneaking.relationTo(useSneaking));

        // Which is the honest answer: no single input satisfies both.
        assertFalse(useSneaking.matches(held(InputKey.USE), TUNING)
                && useNotSneaking.matches(held(InputKey.USE), TUNING));
        assertFalse(useSneaking.matches(held(InputKey.USE, InputKey.SNEAK), TUNING)
                && useNotSneaking.matches(held(InputKey.USE, InputKey.SNEAK), TUNING));
    }

    @Test
    @DisplayName("independence is decided before shape, so it holds for a temporal form too")
    void independenceBeatsShape() {
        // A press is not a conjunction, so containment is undecidable for it — but "requires sneak
        // down" against "requires sneak up" is decidable whatever the shapes are, and this is the one
        // case where answering INDEPENDENT is sound for a non-conjunction.
        final InputCombination pressSneaking =
                InputCombination.pressed(InputKey.USE).and(InputCombination.held(InputKey.SNEAK));
        final InputCombination notSneaking = InputCombination.up(InputKey.SNEAK);

        assertEquals(InputRelation.INDEPENDENT, pressSneaking.relationTo(notSneaking));
    }

    @Test
    @DisplayName("a temporal form never claims containment, only overlap")
    void temporalFormsAreConservative() {
        final InputCombination sneakHeld = InputCombination.held(InputKey.SNEAK);
        final InputCombination sneakHeldLong = InputCombination.hold(InputKey.SNEAK);

        // The key sets are identical, so a naive comparison would call these equivalent — and they are
        // not: one matches the moment sneak goes down and the other half a second later. Reporting
        // OVERLAPS is the conservative answer, and the conservative direction is the safe one.
        assertEquals(InputRelation.OVERLAPS, sneakHeld.relationTo(sneakHeldLong));
        assertFalse(sneakHeldLong.isConjunction());
    }

    @Test
    @DisplayName("an alternative's requirements are the intersection of its branches'")
    void anyOfRequirements() {
        final InputCombination either = InputCombination.anyOf(List.of(
                InputCombination.chord(InputKey.SNEAK, InputKey.LEFT),
                InputCombination.chord(InputKey.SNEAK, InputKey.RIGHT)));

        // Only sneak is needed by every branch. Taking the union instead would claim this requires
        // left AND right, and a binding on "left" would then be reported as independent of it — a
        // missed shadow, which is the unsound direction.
        assertEquals(Set.of(InputKey.SNEAK), either.requiredKeys());
        assertEquals(Set.of(), either.forbiddenKeys());
        assertEquals(InputRelation.OVERLAPS,
                InputCombination.held(InputKey.LEFT).relationTo(either));
    }

    @Test
    @DisplayName("unrelated keys overlap rather than conflict")
    void unrelated() {
        assertEquals(InputRelation.OVERLAPS, InputCombination.held(InputKey.FORWARD)
                .relationTo(InputCombination.held(InputKey.SNEAK)));
    }

    @Test
    @DisplayName("describe() is readable and distinguishes the forms")
    void describe() {
        assertEquals("sprint+jump",
                InputCombination.chord(InputKey.SPRINT, InputKey.JUMP).describe());
        assertEquals("use+!sneak",
                InputCombination.held(InputKey.USE).without(InputKey.SNEAK).describe());
        assertEquals("hold(sneak,configured)", InputCombination.hold(InputKey.SNEAK).describe());
        assertEquals("hold(sneak,250ms)",
                InputCombination.hold(InputKey.SNEAK, 250L * MILLISECOND).describe());
        assertEquals("doubleTap(forward,configured)",
                InputCombination.doubleTap(InputKey.FORWARD).describe());
        assertEquals("press(use)", InputCombination.pressed(InputKey.USE).describe());
        assertEquals("release(use)", InputCombination.released(InputKey.USE).describe());
        assertEquals("any(left, right)", InputCombination.anyOf(List.of(
                InputCombination.held(InputKey.LEFT),
                InputCombination.held(InputKey.RIGHT))).describe());
    }

    // ----------------------------------------------------------------- fixtures

    /** A trace with {@code keys} down and nothing in motion, for the state-only forms. */
    private static InputTrace held(InputKey... keys) {
        return InputSnapshot.ofFlags(InputState.of(keys), InputState.of(keys), NOW);
    }

    /** A trace with {@code key} down for {@code heldNanos} and the given gap to its previous press. */
    private static InputTrace trace(InputKey key, long heldNanos, long gapNanos) {
        final long[] downSince = new long[InputKey.COUNT];
        final long[] gaps = new long[InputKey.COUNT];
        Arrays.fill(gaps, InputTrace.NO_EARLIER_PRESS);
        downSince[key.bit()] = NOW - heldNanos;
        gaps[key.bit()] = gapNanos;
        return InputSnapshot.of(
                InputState.of(key), InputState.of(key), NOW, downSince, gaps, 1L);
    }

    /** A trace on which {@link InputKey#FORWARD} was just pressed, with the given previous-press gap. */
    private static InputTrace pressedWithGap(long gapNanos) {
        final long[] downSince = new long[InputKey.COUNT];
        final long[] gaps = new long[InputKey.COUNT];
        Arrays.fill(gaps, InputTrace.NO_EARLIER_PRESS);
        downSince[InputKey.FORWARD.bit()] = NOW;
        gaps[InputKey.FORWARD.bit()] = gapNanos;
        return InputSnapshot.of(
                InputState.of(InputKey.FORWARD), InputState.NONE, NOW, downSince, gaps, 1L);
    }
}
