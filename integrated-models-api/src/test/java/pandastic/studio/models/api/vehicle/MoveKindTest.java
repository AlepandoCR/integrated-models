package pandastic.studio.models.api.vehicle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The decision that routes a displacement to the asynchronous teleport instead of an in-place write.
 *
 * <p>This is the single most consequential branch in the vehicle layer and the one a live server is worst
 * at reporting on: a write that leaves the region's owned chunks fails <em>silently</em>, so the only
 * place the rule can be held to account is here.
 */
class MoveKindTest {

    /** Deliberately not the shipped default, so a hardcoded threshold cannot pass this. */
    private static final float LIMIT = 3F;

    @Test
    void anExactlyZeroDisplacementIsNothingToWrite() {
        assertSame(MoveKind.NONE, MoveKind.forDelta(0.0D, 0.0D, 0.0D, LIMIT));
    }

    @Test
    void aDisplacementWithinTheThresholdIsWrittenInPlace() {
        assertAll(
                () -> assertSame(MoveKind.LOCAL, MoveKind.forDelta(0.25D, 0.0D, 0.0D, LIMIT)),
                () -> assertSame(MoveKind.LOCAL, MoveKind.forDelta(0.0D, -0.5D, 0.0D, LIMIT)),
                // Exactly at the threshold is still local: the comparison is strict, so the configured
                // figure is the largest permitted write rather than the smallest refused one.
                () -> assertSame(MoveKind.LOCAL, MoveKind.forDelta(LIMIT, 0.0D, 0.0D, LIMIT))
        );
    }

    @Test
    void theThresholdIsATrueDistanceRatherThanPerAxis() {
        // Each component is well inside the threshold and the diagonal is not. Measured per axis this
        // would be written in place, and a diagonal step leaves a region sooner than its components
        // suggest — which is exactly the silent failure this decision exists to prevent.
        assertAll(
                () -> assertSame(MoveKind.LOCAL, MoveKind.forDelta(2.5D, 0.0D, 0.0D, LIMIT)),
                () -> assertSame(MoveKind.LOCAL, MoveKind.forDelta(0.0D, 0.0D, 2.5D, LIMIT)),
                // Each component is 2.5, inside the threshold of 3; the diagonal is 3.54 and is not.
                () -> assertSame(MoveKind.TELEPORT, MoveKind.forDelta(2.5D, 0.0D, 2.5D, LIMIT)),
                () -> assertSame(MoveKind.TELEPORT, MoveKind.forDelta(2.0D, 2.0D, 2.0D, LIMIT))
        );
    }

    @Test
    void aDisplacementPastTheThresholdGoesThroughTheTeleport() {
        assertAll(
                () -> assertSame(MoveKind.TELEPORT, MoveKind.forDelta(3.001D, 0.0D, 0.0D, LIMIT)),
                () -> assertSame(MoveKind.TELEPORT, MoveKind.forDelta(0.0D, 0.0D, -80.0D, LIMIT))
        );
    }

    @Test
    void aNonFiniteDisplacementTakesTheSlowPathRatherThanBeingRefused() {
        // Refusing outright would leave the caller with nothing to do and a vehicle that is stuck.
        // Routing it through the teleport lets the platform refuse the destination instead, which it does
        // with a message naming the coordinate.
        assertAll(
                () -> assertSame(MoveKind.TELEPORT,
                        MoveKind.forDelta(Double.NaN, 0.0D, 0.0D, LIMIT)),
                () -> assertSame(MoveKind.TELEPORT,
                        MoveKind.forDelta(0.0D, Double.POSITIVE_INFINITY, 0.0D, LIMIT))
        );
    }

    @Test
    void aMoveAnswersForItsOwnDisplacement() {
        final VehicleMove small = new VehicleMove(0.1D, 0.0D, 0.1D, 0F, VehicleState.STOPPED);
        final VehicleMove huge = new VehicleMove(40.0D, 0.0D, 0.0D, 0F, VehicleState.STOPPED);

        assertAll(
                () -> assertSame(MoveKind.LOCAL, small.kind(LIMIT)),
                () -> assertSame(MoveKind.TELEPORT, huge.kind(LIMIT)),
                () -> assertSame(MoveKind.NONE, VehicleMove.still(VehicleState.STOPPED).kind(LIMIT))
        );
    }
}
