package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bridging a destination into held keys: the half of the AI-on-vehicle path that is arithmetic.
 *
 * <h2>Why this is the suite that matters most</h2>
 * Because it is where the two vocabularies actually meet. Everything above it is lifecycle — install a
 * plan, attach a pilot, tear it down — and everything below it is the solver, which already has its own
 * suite. The translation from "go to that point" into seven booleans is the only part that can be subtly,
 * silently wrong: a sign flipped on the turn axis gives a vehicle that drives away from its target, and a
 * dead band derived from the wrong quantity gives one that never settles and writes a position every step
 * for ever.
 *
 * <p>None of it needs a server, and that is the design rather than a convenience: a
 * {@link PilotContext} is plain numbers, so the whole bridge is a pure function and a test can enumerate
 * its cases.
 *
 * <h2>The convention these tests pin down</h2>
 * Minecraft yaw grows clockwise from {@code +Z}, so the facing is {@code (-sin, cos)} and the vehicle's
 * own left is {@code (cos, sin)}. The consequence is counter-intuitive and is asserted rather than
 * assumed: a vehicle at yaw zero faces {@code +Z}, and {@code +X} is on its <strong>left</strong>.
 */
class PilotSteeringTest {

    /** A context at the origin, facing {@code +Z}, stopped, over one tick of elapsed time. */
    private static @NotNull PilotContext at(
            double x,
            double z,
            float headingDegrees,
            float forwardSpeed,
            @NotNull VehicleProfile profile
    ) {
        return context(x, z, new VehicleState(forwardSpeed, 0F, headingDegrees), profile, 1F);
    }

    private static @NotNull PilotContext context(
            double x,
            double z,
            @NotNull VehicleState state,
            @NotNull VehicleProfile profile,
            float ticks
    ) {
        return new PilotContext(StubVehicle.create(profile), x, 64.0D, z, state, profile, ticks);
    }

    /** The profile every test here drives unless it is about a profile that cannot do something. */
    private static @NotNull VehicleProfile car() {
        return VehicleFixtures.car();
    }

    // ------------------------------------------------------------------ the identity cases

    @Test
    void aStepWithNoElapsedTimeAsksForNothing() {
        // Every dead band below is a multiple of the elapsed time, so a step with none has nothing to
        // compare against — and the solver would produce no displacement from the answer anyway. The
        // first step of a vehicle's life is exactly this case.
        final PilotContext first = context(0.0D, 0.0D, VehicleState.STOPPED, car(), 0F);

        assertSame(RiderIntent.NONE, PilotSteering.toward(first, 0.0D, 1000.0D),
                "no elapsed time is no authority, and the identity value says so without allocating");
    }

    @Test
    void arrivingWithinOneStepsAuthorityReleasesEveryKey() {
        // The fixed point, and the reason it has to exist: a vehicle that keeps asking for a correction
        // it cannot make never goes neutral, and VehicleStepper only snaps a *neutral* vehicle to a dead
        // stop. One that never snaps writes a position every step for ever.
        //
        // One step of full throttle covers acceleration * ticks^2 = 0.25 blocks, so a fifth of a block
        // ahead is inside the authority.
        final RiderIntent arrived = PilotSteering.toward(at(0.0D, 0.0D, 0F, 0F, car()), 0.0D, 0.2D);

        assertTrue(arrived.isIdle(), "nothing held, so the steering goes neutral and the vehicle settles");
    }

    @Test
    void standingExactlyOnTheTargetDoesNotSpinTheVehicleToDueSouth() {
        // atan2(0, 0) answers zero, which is a heading rather than an absence of one. Acting on it would
        // turn an arrived vehicle to face +Z for no reason, which reads as the model being broken.
        final RiderIntent arrived = PilotSteering.toward(at(10.0D, -4.0D, 123F, 0F, car()), 10.0D, -4.0D);

        assertTrue(arrived.isIdle(), "already there, and no bearing to aim at");
    }

    // ------------------------------------------------------------------ the geometry

    @Test
    void drivesForwardWithoutTurningTowardATargetDeadAhead() {
        final RiderIntent intent = PilotSteering.toward(at(0.0D, 0.0D, 0F, 0F, car()), 0.0D, 100.0D);

        assertAll(
                () -> assertTrue(intent.forward(), "a hundred blocks is well past one step's authority"),
                () -> assertFalse(intent.backward()),
                () -> assertFalse(intent.left(), "dead ahead, so there is no heading error to correct"),
                () -> assertFalse(intent.right())
        );
    }

    @Test
    void turnsLeftTowardATargetOnItsLeftWhichIsPlusX() {
        // The convention, asserted rather than assumed. At yaw zero the facing is (0, 1) and the left is
        // (1, 0), so +X is on the vehicle's left and reaching it means *decreasing* yaw — which is the
        // left key, because yaw grows clockwise.
        final RiderIntent intent = PilotSteering.toward(at(0.0D, 0.0D, 0F, 0F, car()), 100.0D, 0.0D);

        assertAll(
                () -> assertTrue(intent.left(), "+X is to the left of a vehicle facing +Z"),
                () -> assertFalse(intent.right()),
                () -> assertFalse(intent.forward(),
                        "and no throttle: the target's projection onto the facing is zero, so driving"
                                + " ahead would make no progress toward it at all"),
                () -> assertFalse(intent.backward())
        );
    }

    @Test
    void turnsRightTowardATargetOnItsRightWhichIsMinusX() {
        final RiderIntent intent = PilotSteering.toward(at(0.0D, 0.0D, 0F, 0F, car()), -100.0D, 0.0D);

        assertAll(
                () -> assertTrue(intent.right()),
                () -> assertFalse(intent.left())
        );
    }

    @Test
    void reversesTowardATargetDirectlyBehindItWhileTheHeadingComesRound() {
        // The projection onto the facing is the honest statement of progress, and it is negative here.
        // Reversing toward a target behind is a thing a vehicle really does, and it happens while the
        // turn brings the heading round — both at once, from one formula each.
        final RiderIntent intent = PilotSteering.toward(at(0.0D, 0.0D, 0F, 0F, car()), 0.0D, -100.0D);

        assertAll(
                () -> assertTrue(intent.backward(), "the target is a hundred blocks astern"),
                () -> assertFalse(intent.forward()),
                () -> assertTrue(intent.left() || intent.right(), "and the heading is 180 degrees out")
        );
    }

    @Test
    void theHeadingDeadBandIsOneStepsTurnAuthority() {
        // Below it, holding the key ends the step with a *larger* error than it started with, so the
        // vehicle oscillates about its bearing for ever. The band is therefore the quantity one step can
        // turn — turnRate * ticks — and not a configured tolerance that could disagree with it.
        //
        // turnRate is ten degrees a tick. A target eight degrees off the bow over one tick is inside the
        // band; the same target over half a tick, where the authority is five degrees, is outside it.
        final double radians = Math.toRadians(8.0D);
        final double targetX = -100.0D * Math.sin(radians);
        final double targetZ = 100.0D * Math.cos(radians);

        final RiderIntent whole = PilotSteering.toward(
                context(0.0D, 0.0D, VehicleState.STOPPED, car(), 1F), targetX, targetZ);
        final RiderIntent half = PilotSteering.toward(
                context(0.0D, 0.0D, VehicleState.STOPPED, car(), 0.5F), targetX, targetZ);

        assertAll(
                () -> assertFalse(whole.left() || whole.right(),
                        "eight degrees is inside the ten a whole tick of turning covers"),
                () -> assertTrue(half.left() || half.right(),
                        "and outside the five that half a tick covers, so the band tracks the measured"
                                + " step rather than a nominal one")
        );
    }

    // ------------------------------------------------------------------ the braking distance

    @Test
    void brakesWhenReleasingNowWouldOvershootTheTarget() {
        // The question is not "am I near the target" but "where do I end up if I stop asking now". At a
        // block a tick with a braking rate of a half, arresting takes v^2 / 2b = one block — so a target
        // half a block ahead is already past the point of no return and the answer is the brake.
        final RiderIntent intent = PilotSteering.toward(at(0.0D, 0.0D, 0F, 1F, car()), 0.0D, 0.5D);

        assertAll(
                () -> assertTrue(intent.backward(), "one block of overshoot against half a block left"),
                () -> assertFalse(intent.forward())
        );
    }

    @Test
    void keepsAcceleratingWhileThereIsStillRoomToStop() {
        // The same speed, the same rate, a target four blocks away: three blocks of room beyond the one
        // the stop needs, which is well past the dead band.
        final RiderIntent intent = PilotSteering.toward(at(0.0D, 0.0D, 0F, 1F, car()), 0.0D, 4.0D);

        assertTrue(intent.forward(), "four blocks ahead against one block of stopping distance");
    }

    @Test
    void pushesForwardToArrestAReverseThatIsCarryingItAwayFromTheTarget() {
        // One formula, three regimes. Travelling astern at a block a tick with the target one block
        // ahead, the overshoot is *behind* the vehicle, so the signed remaining distance is two blocks
        // and the answer is forward — accelerate, brake and reverse-out all fall out of the same sign.
        final RiderIntent intent = PilotSteering.toward(at(0.0D, 0.0D, 0F, -1F, car()), 0.0D, 1.0D);

        assertAll(
                () -> assertTrue(intent.forward()),
                () -> assertFalse(intent.backward())
        );
    }

    // ------------------------------------------------------------------ the keys it refuses to press

    @Test
    void neverPressesTheBackwardKeyOnAProfileThatCannotReverse() {
        // The solver reads a zero reverse target as *coasting*, not as braking, so the key would produce
        // no motion while keeping the steering non-neutral — which suppresses the rest snap and is how a
        // vehicle writes a position every step for ever. Such a vehicle brakes by releasing.
        final VehicleProfile oneWay = VehicleProfile.builder(VehicleFixtures.BOUNDS)
                .forwardSpeed(1F)
                .reverseSpeed(0F)
                .acceleration(0.25F)
                .braking(0.5F)
                .friction(0.5F)
                .turnRate(10F)
                .build();

        final RiderIntent intent = PilotSteering.toward(at(0.0D, 0.0D, 0F, 1F, oneWay), 0.0D, 0.5D);

        assertAll(
                () -> assertFalse(intent.backward(), "a key that cannot move this vehicle is not pressed"),
                () -> assertFalse(intent.forward())
        );
    }

    @Test
    void neverPressesATurnKeyOnARailBoundProfile() {
        // A turn rate of zero is legal and means a vehicle that cannot be steered. Pressing the key would
        // change no heading and would only keep the steering non-neutral.
        final VehicleProfile railed = VehicleProfile.builder(VehicleFixtures.BOUNDS)
                .forwardSpeed(1F)
                .reverseSpeed(0.5F)
                .acceleration(0.25F)
                .braking(0.5F)
                .friction(0.5F)
                .turnRate(0F)
                .build();

        final RiderIntent intent = PilotSteering.toward(at(0.0D, 0.0D, 0F, 0F, railed), 100.0D, 0.0D);

        assertFalse(intent.left() || intent.right(), "nothing to turn with, so nothing is asked for");
    }

    @Test
    void neverAsksForJumpOrSneak() {
        // Sneak dismounts a rider on the same tick the server sees it, and jump is carried rather than
        // consumed because vertical motion belongs to the root entity's own physics. A pilot pressing
        // either would be asking for nothing — which is also why a flyer's altitude is not reachable
        // from here at all.
        final RiderIntent intent = PilotSteering.toward(at(0.0D, 0.0D, 0F, 0F, car()), 50.0D, 50.0D);

        assertAll(
                () -> assertFalse(intent.jump()),
                () -> assertFalse(intent.sneak())
        );
    }

    // ------------------------------------------------------------------ the strafing scheme

    @Test
    void slidesSidewaysInsteadOfTurningUnderTheStrafingScheme() {
        // Under STRAFING the side keys do not turn, so the heading never changes from input at all and
        // aiming would be a key that does nothing. The target is projected onto the body frame instead
        // and each axis is driven on its own.
        final VehicleProfile hovercraft = VehicleFixtures.hovercraft();
        final RiderIntent intent =
                PilotSteering.toward(at(0.0D, 0.0D, 0F, 0F, hovercraft), 100.0D, 0.0D);

        assertAll(
                () -> assertTrue(intent.left(), "+X is the body frame's left, and it slides there"),
                () -> assertFalse(intent.right()),
                () -> assertFalse(intent.forward(), "nothing ahead to drive toward")
        );
    }

    @Test
    void drivesBothAxesAtOnceUnderTheStrafingScheme() {
        final RiderIntent intent = PilotSteering.toward(
                at(0.0D, 0.0D, 0F, 0F, VehicleFixtures.hovercraft()), 100.0D, 100.0D);

        assertAll(
                () -> assertTrue(intent.forward(), "+Z is ahead"),
                () -> assertTrue(intent.left(), "+X is to the left"),
                () -> assertFalse(intent.right() || intent.backward())
        );
    }

    @Test
    void neverPressesASideKeyWhenAStrafingProfileCannotStrafe() {
        final VehicleProfile stuck = VehicleProfile.builder(VehicleFixtures.BOUNDS)
                .forwardSpeed(1F)
                .reverseSpeed(0.5F)
                .strafeSpeed(0F)
                .acceleration(0.25F)
                .braking(0.5F)
                .friction(0.5F)
                .turnRate(10F)
                .controls(ControlScheme.STRAFING)
                .build();

        final RiderIntent intent = PilotSteering.toward(at(0.0D, 0.0D, 0F, 0F, stuck), 100.0D, 0.0D);

        assertFalse(intent.left() || intent.right());
    }

    // ------------------------------------------------------------------ the boost

    @Test
    void boostsOnlyWhileThereIsRoomToStopFromTheBoostedTopSpeed() {
        // A prediction rather than a reaction: by the time the current speed is too high to stop in, it
        // is too late to not have boosted. The boosted top speed is two blocks a tick, so arresting it
        // takes 4 / (2 * 0.5) = four blocks.
        final VehicleProfile fast = VehicleProfile.builder(VehicleFixtures.BOUNDS)
                .forwardSpeed(1F)
                .reverseSpeed(0.5F)
                .acceleration(0.25F)
                .braking(0.5F)
                .friction(0.5F)
                .turnRate(10F)
                .boost(2F)
                .build();

        final RiderIntent far = PilotSteering.toward(at(0.0D, 0.0D, 0F, 0F, fast), 0.0D, 100.0D);
        final RiderIntent near = PilotSteering.toward(at(0.0D, 0.0D, 0F, 0F, fast), 0.0D, 4.1D);

        assertAll(
                () -> assertTrue(far.sprint(), "a hundred blocks is four for the stop and room to spare"),
                () -> assertTrue(far.forward()),
                () -> assertFalse(near.sprint(),
                        "four and a bit blocks leaves no room, so the boost is declined before it is"
                                + " reached rather than regretted afterwards"),
                () -> assertTrue(near.forward(), "and the vehicle still drives, unboosted")
        );
    }

    @Test
    void doesNotBoostIntoABearingItHasNotReachedYet() {
        // Boosting while still turning is how a vehicle takes the outside of every corner.
        final VehicleProfile fast = VehicleProfile.builder(VehicleFixtures.BOUNDS)
                .forwardSpeed(1F)
                .reverseSpeed(0.5F)
                .acceleration(0.25F)
                .braking(0.5F)
                .friction(0.5F)
                .turnRate(10F)
                .boost(2F)
                .build();

        // Forty-five degrees off the bow and a long way out: plenty of room, but the heading is wrong.
        final RiderIntent intent = PilotSteering.toward(at(0.0D, 0.0D, 0F, 0F, fast), -100.0D, 100.0D);

        assertAll(
                () -> assertTrue(intent.right(), "still coming round"),
                () -> assertFalse(intent.sprint(), "so the boost waits")
        );
    }

    @Test
    void neverPressesTheBoostKeyOnAProfileWithTheNeutralMultiplier() {
        // A multiplier of one is the neutral value: the key would change no speed and would only keep the
        // steering non-neutral.
        final RiderIntent intent = PilotSteering.toward(at(0.0D, 0.0D, 0F, 0F, car()), 0.0D, 1000.0D);

        assertAll(
                () -> assertEquals(1F, car().boostMultiplier()),
                () -> assertFalse(intent.sprint())
        );
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void refusesANonFiniteTarget() {
        final PilotContext context = at(0.0D, 0.0D, 0F, 0F, car());

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PilotSteering.toward(context, Double.NaN, 0.0D)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PilotSteering.toward(context, 0.0D, Double.POSITIVE_INFINITY))
        );
    }

    @Test
    void aContextRefusesANonFinitePositionAndANegativeStep() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new PilotContext(
                        StubVehicle.create(car()), Double.NaN, 0.0D, 0.0D, VehicleState.STOPPED, car(),
                        1F)),
                () -> assertThrows(IllegalArgumentException.class, () -> new PilotContext(
                        StubVehicle.create(car()), 0.0D, 0.0D, 0.0D, VehicleState.STOPPED, car(), -1F))
        );
    }

    @Test
    void theContextReportsTheHeadingTheSolverWillIntegrate() {
        // Not the entity's yaw: this layer's own authority over which way the vehicle is pointing, which
        // is the value the next solver call starts from.
        final PilotContext context = at(0.0D, 0.0D, 37F, 0F, car());

        assertAll(
                () -> assertEquals(37F, context.headingDegrees()),
                () -> assertTrue(context.hasElapsed())
        );
    }

    /**
     * A {@link Vehicle} that exists only to be a non-null reference in a context.
     *
     * <p>A proxy whose handler throws, for the reason the AI suite's creature fixture is one: the point
     * is that {@link PilotSteering} reads nothing off it. If some future change starts asking the vehicle
     * questions here, this fails naming the method instead of passing against a stub that answered zero.
     */
    private static final class StubVehicle {

        private StubVehicle() {
        }

        static @NotNull Vehicle create(@NotNull VehicleProfile profile) {
            Objects.requireNonNull(profile, "profile");
            return (Vehicle) Proxy.newProxyInstance(
                    StubVehicle.class.getClassLoader(),
                    new Class<?>[]{Vehicle.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "equals" -> proxy == arguments[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "StubVehicle";
                        case "getProfile" -> profile;
                        default -> throw new AssertionError("PilotSteering read " + method.getName()
                                + " off the vehicle. It is a pure function of the context's numbers;"
                                + " anything it needs belongs in PilotContext, where it carries no"
                                + " permission to touch a region.");
                    });
        }
    }
}
