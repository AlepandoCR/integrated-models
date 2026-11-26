package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.NotNull;

/**
 * A synthetic rider: something that steers a vehicle nobody is sitting in.
 *
 * <h2>Why this is a seam and not a second way to move a vehicle</h2>
 * Because a vehicle's position has exactly <strong>one</strong> writer and it is the control step. That
 * is the whole of the authority model — the server decides where the vehicle is, the client is told, and
 * every number the operator configured means what it says because {@link VehicleSolver} is the only thing
 * that turns a decision into a displacement.
 *
 * <p>So an autonomous vehicle is not a vehicle that something else moves. It is a vehicle whose
 * {@link RiderIntent} comes from somewhere other than a keyboard. A pilot supplies that intent and
 * nothing else: it cannot write a position, cannot set a heading, cannot teleport, and therefore cannot
 * fight the solver. Every guarantee the layer gives a ridden vehicle — the profile's speeds and rates,
 * the step-size independence, the teleport threshold, the terrain reaction, the rest fixed point — holds
 * unchanged for a piloted one, because all of them are downstream of the intent rather than beside it.
 *
 * <p>The alternative was tried in the sibling project and is the bug this seam exists to make
 * unreachable: a driver that reads an {@link pandastic.studio.models.api.ai.AiIntent} and calls the
 * mob's own pathfinder. That works for a plain creature and cannot work for a vehicle root, because the
 * navigation writes the entity's movement from the creature's own tick while the control step writes it
 * from the same tick a moment later. Two writers to one position, with no rule for which wins, and the
 * symptom is a vehicle that travels at neither the profile's speed nor the attribute's.
 *
 * <h2>A rider always wins</h2>
 * A pilot is consulted only on the steps where the <strong>driver's seat is empty</strong>. The moment
 * somebody boards it, their keys steer and the pilot is not asked. That is the only arrangement with one
 * decider at a time, and it is also the one a player expects: boarding an autonomous vehicle takes it
 * over, and leaving it gives it back. Passengers make no difference — only the driver's seat does, which
 * is the seat whose input the solver has always read.
 *
 * <h2>The thread rule, and why a pilot gets a value rather than the entity</h2>
 * {@link #steer} is called on the thread that owns the vehicle's root entity, from inside the control
 * step, before anything has been decided. It is handed a {@link PilotContext} of plain numbers rather
 * than anything it could read a world through, for the reason
 * {@link pandastic.studio.models.api.ai.AiPoint} is a triple of doubles: a value carries no permission
 * and needs none, so a pilot is pure arithmetic and is exercisable with no server anywhere.
 *
 * <p>A pilot may of course read a volatile field something else published — which is exactly what
 * {@link AiPilot} does with {@link pandastic.studio.models.api.ai.AiController#intent()}. What it must
 * not do is read an entity, a block or a player, because the step it runs inside owns one region and
 * nothing else.
 *
 * <h2>A pilot that throws is contained, and the vehicle coasts</h2>
 * The step reports the failure and uses {@link RiderIntent#NONE} for that step, so a broken pilot
 * produces a vehicle that rolls to a stop rather than a control loop that stops running. Unlike a
 * {@link VehicleController} a pilot is <em>not</em> detached after repeated failures: detaching it would
 * leave an autonomous vehicle permanently parked in the world with nothing to say why, where a
 * controller's detach only costs the behaviour it was adding.
 *
 * <h2>Nothing here is tunable</h2>
 * {@link #NONE} is an identity value. Everything a pilot needs to decide with arrives in the
 * {@link PilotContext}: the vehicle's own profile, its own state, its own position and the measured
 * elapsed time. {@link PilotSteering} derives every threshold it uses from those rather than holding one
 * — see its documentation, because that is what makes a pilot free of configuration rather than quietly
 * carrying some.
 */
@FunctionalInterface
public interface VehiclePilot {

    /**
     * A pilot that asks for nothing, so a vehicle carrying it behaves exactly as an unpiloted one.
     *
     * <p>Not {@code null}: a vehicle always has a pilot, so the control step has nothing to check beyond
     * one reference comparison — which is how a vehicle with no pilot pays nothing for the seam existing.
     */
    @NotNull VehiclePilot NONE = context -> RiderIntent.NONE;

    /**
     * What this pilot is asking for, once per control step on which the driver's seat is empty.
     *
     * <p>Called before anything about the step has been decided, and the returned intent is fed to the
     * same {@link VehicleSteering#of} translation a rider's keys go through — so a pilot is subject to the
     * vehicle's own {@link ControlScheme} and cannot ask for a motion the profile does not describe.
     *
     * <p>Must not block: it runs on the region thread and a stall here stalls every player in that
     * region. Must not touch the world; see the type documentation.
     *
     * @param context where the vehicle is, how it is moving, how it drives, and how much time this step
     *                covers
     * @return the keys a rider would be holding. {@link RiderIntent#NONE} to let the vehicle coast, which
     *         is what lets it reach the rest fixed point and stop writing positions at all
     */
    @NotNull RiderIntent steer(@NotNull PilotContext context);

    /**
     * Called once when the vehicle this pilot was steering is torn down.
     *
     * <p>Where a pilot releases what it owns — for {@link AiPilot}, the installed plan. Delivered from
     * inside the vehicle's one teardown, after the consumer's {@link VehicleController#onClosed} and
     * before the riders are put down, so a pilot and a controller cannot both be mid-release at once.
     *
     * <p><strong>No thread promise</strong>, for exactly the reasons
     * {@link VehicleController#onClosed} carries none: three things can decide a vehicle is finished and
     * one of them is the platform's retired callback, which cannot defer work anywhere. So touch nothing
     * but your own state, and use operations that are safe from any thread —
     * {@link pandastic.studio.models.api.ai.AiController#uninstall()} is documented to be exactly that.
     *
     * @param vehicle the vehicle being torn down, for identity. Already stopped
     */
    default void onClosed(@NotNull Vehicle vehicle) {
    }
}
