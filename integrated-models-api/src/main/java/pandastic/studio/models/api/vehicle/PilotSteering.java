package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Turning "go to that point" into the seven keys a rider would be holding, as one pure function.
 *
 * <h2>The two vocabularies, and why bridging them belongs here</h2>
 * The AI layer publishes a destination: an {@link pandastic.studio.models.api.ai.AiIntent} with a move
 * point and a speed multiplier. The vehicle layer consumes held keys: a {@link RiderIntent} of seven
 * booleans. Those are not two spellings of one thing — a destination is a position and a key is a rate —
 * and something has to close the gap.
 *
 * <p>It closes <em>here</em>, as arithmetic over doubles, rather than anywhere that could move an entity.
 * That is the whole design decision of the AI-on-vehicle path and it has one justification: a vehicle's
 * position has exactly one writer, the control step, and the moment anything else sets a path or a
 * velocity on the root entity there are two. See {@link VehiclePilot} for what that costs. Synthesising
 * the input instead keeps {@link VehicleSolver} the only thing that decides motion, which means an
 * autonomous vehicle obeys the operator's speeds, rates, friction, boost, teleport threshold and terrain
 * ceilings for free — they are all downstream of the intent.
 *
 * <h2>Why this holds no configured number at all</h2>
 * A controller that compares against a threshold usually needs one configured. This one does not, and the
 * reason is a property of what it is steering with: a key is either down or up, so the smallest correction
 * that can be <em>asked for</em> is one step of full input. That quantity is {@code rate × ticks} — the
 * profile's own rate, times the measured elapsed time — and it is the only honest dead band available:
 *
 * <ul>
 *   <li><strong>Heading.</strong> One step turns at most {@code turnRateDegreesPerTick × ticks}. Holding
 *       a turn key while the heading error is smaller than that ends the step with a <em>larger</em>
 *       error than it started with, so the vehicle oscillates about its bearing forever.</li>
 *   <li><strong>Distance.</strong> One step of full throttle covers about
 *       {@code accelerationBlocksPerTickSquared × ticks²}. Below that there is no input that improves the
 *       position, so asking for one is how an autonomous vehicle never settles — and a vehicle that never
 *       settles never reaches the rest fixed point and writes a position every step for ever. The dead
 *       band is what makes "arrived" a stable state rather than an almost-true one.</li>
 * </ul>
 *
 * <p>So the thresholds are derived, not chosen, and they track a reload of {@code vehicle.yml} and a
 * stalled server automatically. An arrival radius configured in a file would be a second statement of the
 * same quantity that could disagree with the first.
 *
 * <h2>Braking distance is the aim, not the distance left</h2>
 * The decision is not "am I near the target" but "where do I end up if I stop asking now". A vehicle
 * travelling at {@code v} needs {@code v² / (2·braking)} blocks to arrest — the closed form of the
 * solver's own linear braking — so the signed quantity that matters is the offset to the target
 * <em>minus</em> that overshoot. One formula then covers accelerating toward a target, braking before it,
 * and reversing out of an overshoot, with no mode to be in and no state to carry; see {@link #axis}.
 *
 * <h2>What it does with the control scheme, and what it refuses to ask for</h2>
 * The intent goes through {@link VehicleSteering#of}, so a pilot is subject to the vehicle's scheme
 * exactly as a rider is. Under {@link ControlScheme#TURNING} the side keys turn, so the vehicle aims at
 * the target and drives along its own facing. Under {@link ControlScheme#STRAFING} the heading never
 * changes from input at all, so the target is projected onto the body frame and each axis is driven
 * independently.
 *
 * <p>Three keys are never pressed, and each would be a no-op that costs the rest fixed point:
 * <ul>
 *   <li>a turn key on a profile whose {@code turnRateDegreesPerTick} is zero — a rail-bound vehicle;</li>
 *   <li>a side key under {@link ControlScheme#STRAFING} on a profile whose strafe speed is zero;</li>
 *   <li>the backward key on a profile whose reverse speed is zero, because the solver reads a zero
 *       reverse target as <em>coasting</em> rather than as braking. Such a vehicle slows by friction, so
 *       releasing is the brake.</li>
 * </ul>
 * In each case the steering would come back non-neutral while producing no motion, and
 * {@code VehicleStepper} only snaps a vehicle to a dead stop while the steering <em>is</em> neutral.
 *
 * <p>{@link RiderIntent#jump()} and {@link RiderIntent#sneak()} are never pressed either, for the reasons
 * the layer already gives: sneak dismounts a rider on the same tick the server sees it, and jump is
 * carried rather than consumed because vertical motion belongs to the root entity's own physics. A pilot
 * therefore cannot ask a {@link VehicleKind#FLYER} to change altitude — see {@link VehiclePilot}.
 */
public final class PilotSteering {

    private PilotSteering() {
    }

    /**
     * The keys a rider would hold to bring the vehicle to {@code (targetX, targetZ)}.
     *
     * <p>Horizontal only. The vertical is deliberately absent rather than ignored: there is no key the
     * solver reads it from, so a target's height cannot be acted on and pretending otherwise would be a
     * silent half-feature.
     *
     * @param context where the vehicle is, how it is moving, how it drives, and the measured step
     * @param targetX the destination's world x, in blocks
     * @param targetZ the destination's world z, in blocks
     * @return the synthesised intent. {@link RiderIntent#NONE} when the step covers no elapsed time, when
     *         the vehicle has arrived within one step's authority, or when every key that would help is
     *         one this profile cannot act on
     * @throws IllegalArgumentException if either coordinate is not finite
     */
    @Contract(pure = true)
    public static @NotNull RiderIntent toward(
            @NotNull PilotContext context,
            double targetX,
            double targetZ
    ) {
        Objects.requireNonNull(context, "context");
        require(targetX, "targetX");
        require(targetZ, "targetZ");

        if (!context.hasElapsed()) {
            // No elapsed time is no authority: every dead band below is a multiple of it, so a step with
            // none has nothing to compare against — and the solver would produce no displacement from the
            // answer anyway. Asking for nothing is the honest reading of "nothing has happened yet".
            return RiderIntent.NONE;
        }

        final VehicleProfile profile = context.profile();
        final VehicleState state = context.state();
        final float ticks = context.ticks();

        final double deltaX = targetX - context.x();
        final double deltaZ = targetZ - context.z();

        // Minecraft yaw grows clockwise from +Z, so the facing is (-sin, cos) and the vehicle's own left
        // is (cos, sin) — the same convention VehicleSolver projects its displacement along, which is what
        // makes a projection taken here and a displacement produced there agree about which way sideways
        // is.
        final double heading = Math.toRadians(context.headingDegrees());
        final double sin = Math.sin(heading);
        final double cos = Math.cos(heading);
        final double ahead = -deltaX * sin + deltaZ * cos;
        final double leftward = deltaX * cos + deltaZ * sin;

        final float braking = profile.brakingBlocksPerTickSquared();
        // What one step of full input covers. The dead band for every distance comparison below; see the
        // class note for why it is derived rather than configured.
        final double authority = (double) profile.accelerationBlocksPerTickSquared() * ticks * ticks;

        final int throttle = axis(ahead, state.forwardSpeedBlocksPerTick(), braking, authority,
                profile.maxReverseSpeedBlocksPerTick() > 0F);

        final boolean turning = profile.controlScheme() == ControlScheme.TURNING;
        // Zero only when the vehicle is already at the target, in which case there is no bearing to aim
        // at: atan2(0, 0) answers zero, which is a heading rather than an absence, and acting on it would
        // spin an arrived vehicle to due south.
        final boolean aimable = deltaX != 0.0D || deltaZ != 0.0D;

        int turnKey = 0;
        if (turning && aimable && profile.turnRateDegreesPerTick() > 0F) {
            final float desired = (float) Math.toDegrees(Math.atan2(-deltaX, deltaZ));
            final float error = VehicleState.normaliseDegrees(desired - context.headingDegrees());
            final float turnAuthority = profile.turnRateDegreesPerTick() * ticks;
            if (error > turnAuthority) {
                // Positive error wants a larger yaw, which is the way yaw grows, which is the right key.
                turnKey = 1;
            } else if (error < -turnAuthority) {
                turnKey = -1;
            }
        }

        int sideKey = 0;
        if (!turning && profile.maxStrafeSpeedBlocksPerTick() > 0F) {
            // Symmetric, so the negative direction is as available as the positive one.
            sideKey = axis(leftward, state.strafeSpeedBlocksPerTick(), braking, authority, true);
        }

        return new RiderIntent(
                throttle > 0,
                throttle < 0,
                turning ? turnKey < 0 : sideKey > 0,
                turning ? turnKey > 0 : sideKey < 0,
                // Jump and sneak are never asked for; see the class note.
                false,
                false,
                boosting(throttle, turnKey, ahead, profile, braking, authority));
    }

    /**
     * Which way to push one body axis: {@code +1}, {@code -1} or nothing.
     *
     * <h2>One formula for three regimes</h2>
     * The question asked is not "how far is the target" but "where does this axis end up if the input is
     * released now". A speed of {@code v} overshoots by {@code v² / (2·braking)} — the closed form of the
     * solver's linear braking — in the direction it is already travelling, so subtracting that signed
     * overshoot from the offset yields a single number whose sign is the answer. Accelerating toward a
     * distant target, braking before a near one, and reversing out of one already passed all fall out of
     * it, which is why a pilot carries no mode and no state.
     *
     * @param offset            signed distance to the target along this axis, in blocks
     * @param speed             current speed along this axis, in blocks per tick
     * @param braking           the profile's braking rate, in blocks per tick squared. Strictly positive,
     *                          which {@link VehicleProfile} guarantees, so the overshoot is always finite
     * @param authority         what one step of full input covers along this axis, in blocks. The dead
     *                          band, and the reason an arrived vehicle settles
     * @param negativeAvailable whether this profile can actually move the negative way along this axis. A
     *                          key that cannot move the vehicle still makes the steering non-neutral,
     *                          which suppresses the rest snap; see the class note
     * @return {@code 1} for the positive direction, {@code -1} for the negative, {@code 0} to release
     */
    @Contract(pure = true)
    private static int axis(
            double offset,
            float speed,
            float braking,
            double authority,
            boolean negativeAvailable
    ) {
        final double overshoot = (double) speed * (double) speed / (2.0D * (double) braking);
        final double remaining = offset - Math.signum((double) speed) * overshoot;
        if (remaining > authority) {
            return 1;
        }
        if (remaining < -authority) {
            return negativeAvailable ? -1 : 0;
        }
        return 0;
    }

    /**
     * Whether holding the boost key is still something the vehicle could stop out of.
     *
     * <p>Boost is free exactly while there is room ahead to arrest the <em>boosted</em> top speed, which
     * is the same braking-distance question {@link #axis} asks with a different speed — the one the
     * vehicle would reach rather than the one it is at. That makes the decision a prediction rather than a
     * reaction, which is what it has to be: by the time the current speed is too high to stop in, it is
     * too late to not have boosted.
     *
     * <p>Refused while the vehicle is turning, under {@link ControlScheme#TURNING}, because boosting into
     * a bearing it has not reached yet is how a vehicle takes the outside of every corner. Under
     * {@link ControlScheme#STRAFING} there is no bearing to be wrong about, and the boost applies ahead
     * only, so the term does not arise.
     *
     * <p>Refused outright on a profile whose boost multiplier is the neutral {@code 1}: the key would
     * change no speed and would only keep the steering non-neutral.
     */
    @Contract(pure = true)
    private static boolean boosting(
            int throttle,
            int turnKey,
            double ahead,
            @NotNull VehicleProfile profile,
            float braking,
            double authority
    ) {
        if (throttle <= 0 || turnKey != 0 || profile.boostMultiplier() <= 1F) {
            return false;
        }
        final float boosted = profile.boostedForwardSpeedBlocksPerTick();
        final double overshoot = (double) boosted * (double) boosted / (2.0D * (double) braking);
        return ahead - overshoot > authority;
    }

    private static void require(double coordinate, @NotNull String name) {
        if (!Double.isFinite(coordinate)) {
            throw new IllegalArgumentException(name + " must be finite but was " + coordinate);
        }
    }
}
