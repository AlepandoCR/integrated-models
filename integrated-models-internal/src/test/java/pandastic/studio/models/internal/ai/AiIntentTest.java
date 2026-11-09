package pandastic.studio.models.internal.ai;

import pandastic.studio.models.api.ai.AiIntent;
import pandastic.studio.models.api.ai.AiPoint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The value a decision crosses threads as.
 *
 * <p>The cases worth writing are the ones where a malformed intent would be read by something else and
 * acted on: a speed with no destination is a number nothing will look at, and a non-finite coordinate
 * becomes a rotation on the wire.
 */
class AiIntentTest {

    private static final AiPoint SOMEWHERE = new AiPoint(10.0D, 64.0D, -3.5D);

    @Test
    void theIdentityValueAsksForNothing() {
        assertAll(
                () -> assertTrue(AiIntent.NONE.isIdle()),
                () -> assertFalse(AiIntent.NONE.hasMoveTarget()),
                () -> assertFalse(AiIntent.NONE.hasLookTarget()),
                () -> assertEquals(0.0D, AiIntent.NONE.moveSpeed()),
                // Equal to a freshly built empty one, deliberately: nothing compares intents by
                // identity, so a behaviour publishing its own empty intent means the same thing.
                () -> assertEquals(AiIntent.NONE, new AiIntent(null, 0.0D, null))
        );
    }

    @Test
    void aSpeedWithNoDestinationIsRefused() {
        // The failure: nothing reads a speed without a move target, so a behaviour that set one and
        // forgot the target looks exactly like one that deliberately asked for nothing.
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new AiIntent(null, 1.0D, null));

        assertTrue(refused.getMessage().contains("nothing will read it"), refused::getMessage);
    }

    @Test
    void aNonFiniteOrNegativeSpeedIsRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AiIntent(SOMEWHERE, Double.NaN, null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AiIntent(SOMEWHERE, Double.POSITIVE_INFINITY, null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AiIntent(SOMEWHERE, -1.0D, null))
        );
    }

    @Test
    void aLookOnlyIntentNeedsNoSpeed() {
        final AiIntent looking = AiIntent.lookAt(SOMEWHERE);

        assertAll(
                () -> assertFalse(looking.hasMoveTarget()),
                () -> assertTrue(looking.hasLookTarget()),
                () -> assertFalse(looking.isIdle())
        );
    }

    @Test
    void aLookTargetCanBeAddedToSomeoneElsesMoveTarget() {
        // Why withLookPoint exists: two behaviours in one activity decide the two halves, and neither
        // should have to know what the other asked for.
        final AiIntent moving = AiIntent.moveTo(SOMEWHERE, 1.2D);
        final AiIntent both = moving.withLookPoint(AiPoint.ORIGIN);

        assertAll(
                () -> assertEquals(SOMEWHERE, both.movePoint()),
                () -> assertEquals(1.2D, both.moveSpeed()),
                () -> assertEquals(AiPoint.ORIGIN, both.lookPoint()),
                () -> assertFalse(moving.hasLookTarget(), "the original must be untouched")
        );
    }

    @Test
    void droppingALookTargetFromAMoveOnlyIntentIsLegal() {
        assertFalse(AiIntent.moveTo(SOMEWHERE, 1.0D).withLookPoint(null).hasLookTarget());
    }

    @Test
    void aNonFiniteCoordinateIsRefusedByThePointItself() {
        // A NaN that reaches a navigation target makes the creature stand still with no error anywhere,
        // and one that reaches a look target is sent to the client as a rotation.
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AiPoint(Double.NaN, 0.0D, 0.0D)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AiPoint(0.0D, Double.POSITIVE_INFINITY, 0.0D)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AiPoint(0.0D, 0.0D, Double.NEGATIVE_INFINITY))
        );
    }

    @Test
    void squaredDistanceIsTheEuclideanOne() {
        assertEquals(9.0D + 16.0D, new AiPoint(3.0D, 4.0D, 0.0D).distanceSquaredTo(AiPoint.ORIGIN));
    }
}
