package pandastic.studio.models.api.vehicle;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * One object that owns a <em>kind</em> of vehicle's behaviour: what its riders' inputs do, and what
 * happens as they come and go.
 *
 * <h2>Why this exists next to {@link VehicleControls}</h2>
 * They answer different questions and neither replaces the other.
 *
 * <p>{@link VehicleControls} is a <strong>map of listeners</strong>: seat two does this, every seat does
 * that. It is the right shape when the behaviour is a handful of independent reactions, and it composes
 * — two listeners on one seat both run.
 *
 * <p>This is a <strong>single implementation per vehicle kind</strong>, and it is the right shape when
 * the behaviour has state and invariants of its own: a tank's turret angle, a submarine's trim, a
 * crane's extension. Those live in fields on the controller, where the methods that read and write them
 * are the only ones that can, instead of being captured in several lambdas that have to agree. One
 * object, one set of invariants — which is also what makes it testable without a server.
 *
 * <p>A controller is adapted onto the per-seat mechanism rather than duplicating it, so everything
 * documented about input threading applies here unchanged. Read it, because the one rule below is the
 * one that breaks servers.
 *
 * <h2>Where a controller is supplied</h2>
 * {@link VehicleSetup#controller()}, next to the messages and the controls, and therefore at creation:
 * the four-argument {@link VehicleBinder}{@code .create} is the one that takes a setup. It is fixed for
 * the vehicle's life, for the reason
 * {@link VehicleControls} is: a vehicle whose behaviour could be swapped while somebody was driving it
 * would change what a held key means mid-hold, and the rider has no way to be told.
 *
 * <h2>Both this and {@link VehicleControls} can be supplied, and then both run</h2>
 * In one fixed order, per seat: that seat's composed listener first, this controller afterwards.
 * Then, once every seat has been offered, {@link #onStep}. The rule is specific before general, which
 * is the same order {@link VehicleControls} composes its own three ways of addressing a seat in — index,
 * then name, then catch-all — with the controller as the most general case of all. Both see the
 * <em>same</em> {@link SeatInput} instance, so they cannot disagree about what a rider did.
 *
 * <h2>The thread rule, which is not negotiable</h2>
 * {@link #onPlayerInput} and {@link #onStep} are called on the thread that owns the vehicle's root
 * entity, from inside the vehicle's own control step. {@link #onSeated} and {@link #onVacated} are
 * called on that thread too, but from inside the mount or dismount that caused them, which is not
 * necessarily a step. All four make it legal to read and move the vehicle, its riders and its visual,
 * and <strong>illegal to touch anything in another region</strong> — another player far away, another
 * vehicle, a block across a boundary. Those need the region scheduler, the same as everywhere else in
 * this plugin.
 *
 * <p>{@link #onClosed} is the exception and it is the platform's rule rather than ours; see that
 * method.
 *
 * <p>It also means these methods are on a hot path: they run once per rider per control step. Blocking
 * in one of them stalls the region, and that includes every player in it and not just this vehicle.
 *
 * <h2>A controller that throws is contained, counted and eventually detached</h2>
 * The same policy a bound {@link pandastic.studio.models.api.input.InputHandler} gets, and for the same
 * reason: a hook on a control step that threw would otherwise take the rest of that step with it, every
 * step, for as long as the consumer's bug lasts. So every call is wrapped, the failure is reported
 * through the server's log at a configured interval rather than once per occurrence, and a hook that has
 * failed as many times <em>in a row</em> as the configuration permits <strong>detaches the whole
 * controller from that vehicle</strong>. A success resets the count.
 *
 * <p>Detached means this vehicle stops calling it: the vehicle keeps driving on its profile, with none
 * of the behaviour this object was supplying. One object holds one set of invariants, so a controller
 * whose input hook is broken has state nothing else should be built on — which is why the detach is the
 * whole controller and not the one hook. It is also per <em>vehicle</em> and not per server: one tank
 * detaching does not silence the controller the other forty share, because the state that has become
 * untrustworthy is the state of this one.
 *
 * <p>{@link #onClosed} is still delivered to a detached controller. It is bounded — once, ever — and it
 * is the only hook whose purpose is releasing what the controller opened.
 *
 * <p>So a controller must not use an exception to decline. There is nothing to decline to: a hook with
 * nothing to do should return.
 *
 * <h2>Everything is a default</h2>
 * A controller that only cares about input implements one method. That is deliberate interface
 * segregation: the alternative is five empty overrides in every implementation, which is noise that
 * hides the one method that matters, and a sixth hook added later breaking every existing controller.
 */
public interface VehicleController {

    /**
     * A controller that does nothing, for a vehicle whose behaviour is entirely its profile.
     *
     * <p>Not {@code null}: a vehicle always has a controller, so the step has nothing to check.
     */
    @NotNull VehicleController NONE = new VehicleController() {
    };

    /**
     * What this rider's inputs do, called once per control step for each occupied seat.
     *
     * <h2>Called every step, not on change</h2>
     * Including the steps where nothing is held, which {@link SeatInput#isIdle()} answers cheaply. That
     * is what lets a controller hold something down — a throttle, a dive — rather than only reacting to
     * the moment a key went down, and it is why the edges are offered as
     * {@link SeatInput#justPressed} and {@link SeatInput#justReleased} instead of being the only thing
     * on offer.
     *
     * <p>Every occupied seat, in index order, so the driver first. A seat nobody is in is not a call
     * with an empty input; it is no call at all.
     *
     * <h2>Two steps on which it is not called</h2>
     * Neither is a gap in the contract and both are states a vehicle really reaches. A step taken while
     * a <strong>relocation is in flight</strong> decides nothing at all — the root entity is
     * transiently out of the world, so there is no position to reason from — and a step that finds its
     * <strong>root entity gone</strong> tears the vehicle down instead of finishing. A controller must
     * therefore not count steps to measure time; {@link #onStep}'s {@code deltaTicks} is the measured
     * figure and is what the vehicle itself integrates.
     *
     * <h2>After that seat's listener</h2>
     * If the consumer also supplied {@link VehicleControls}, that seat's composed listener has already
     * run with this same value by the time this is called. See the type note for the whole order.
     *
     * <h2>Steering is not your job unless you take it</h2>
     * The driver's movement keys already reach the solver through the vehicle's
     * {@link VehicleProfile}, so a controller that does nothing still drives. This hook is for what the
     * profile cannot express: a passenger aiming, a horn, a turret, a landing gear. Reading the
     * driver's keys here to move the vehicle yourself means two writers to one position, which is a
     * fight you will lose to the solver.
     *
     * @param input who is in which seat and what they are holding, as one immutable value
     */
    default void onPlayerInput(@NotNull SeatInput input) {
    }

    /**
     * Called after {@code rider} has been seated, from inside the boarding call that seated them.
     *
     * <p>Not from a control step: boarding is something a command, a menu or a listener asks for, so
     * this runs on the root entity's owning thread but between two steps rather than during one. A
     * controller that wants to act on the vehicle's next <em>decision</em> about a new rider should set
     * a field here and read it in {@link #onPlayerInput} or {@link #onStep}.
     *
     * <p>After and not before: the seat is already theirs, so {@link Vehicle#getSeating()} agrees with
     * what this method is told. A controller that wants to refuse a rider cannot do it here — refusal
     * belongs to the boarding call, which returns a {@link BoardingResult} to whoever asked.
     *
     * <p>It is also after the rider has been told which seat they got, so a controller that throws
     * cannot cost them the one message about this that they can see.
     *
     * @param vehicle the vehicle they boarded
     * @param seat    where they ended up
     * @param rider   who boarded
     */
    default void onSeated(
            @NotNull Vehicle vehicle,
            @NotNull VehicleSeat seat,
            @NotNull Player rider
    ) {
    }

    /**
     * Called after {@code rider}'s seat emptied, for any reason this plugin observes.
     *
     * <p>Including the ones nobody asked for: they walked off, they died, they disconnected, the model
     * stopped rendering. A controller holding per-rider state has to release it here rather than on the
     * dismount call, because most departures never go through one. The ones nobody asked for are
     * noticed by a control step, so this one arrives from either side — a dismount call, or the step
     * that found the seat empty.
     *
     * <p><strong>{@link Vehicle#getSeating()} may still name this rider during this call.</strong> The
     * seat itself is already free, and that is what the arguments say; the occupancy other threads read
     * is rebuilt once after the whole pass, because a step that empties three seats publishes one value
     * rather than three. So read {@code seat} and {@code rider}, not the occupancy. That asymmetry with
     * {@link #onSeated} is real and is the reason it is written down here.
     *
     * <p>Not called for the riders a vehicle puts down because it is <em>closing</em>: that is
     * {@link #onClosed}, once, instead of one call per seat. A controller that releases per-rider state
     * has to release it in both places.
     *
     * @param vehicle the vehicle they left
     * @param seat    the seat that is now free
     * @param rider   who left
     */
    default void onVacated(
            @NotNull Vehicle vehicle,
            @NotNull VehicleSeat seat,
            @NotNull Player rider
    ) {
    }

    /**
     * Called once per control step, after every seat's input has been offered.
     *
     * <p>Where a controller puts the work that is about the vehicle as a whole rather than about one
     * rider — resolving two riders' conflicting requests, advancing its own animation, deciding to shut
     * down. Running after the inputs rather than before is what lets it see all of them.
     *
     * <p>Called on a step with nobody aboard too. A parked vehicle still steps, and a controller
     * animating something has to keep animating it.
     *
     * <h2>{@code deltaTicks} is measured, not nominal</h2>
     * A fraction, and the figure the vehicle itself integrated this step: the elapsed time between this
     * step and the previous one, in server ticks, after the clamp the operator configures for a server
     * that stalled. It is <em>not</em> the control period, because the platform drops missed iterations
     * of a repeating task rather than replaying them — a controller that assumed its nominal period
     * would advance its own state at a rate that depended on the server's load, which is the same
     * mistake the vehicle's own loop exists not to make.
     *
     * <p>So it is a {@code float}: a one-tick control period under load really does produce 1.3 ticks.
     * It is {@code 0} on the first step of a vehicle's life, which has nothing to difference against,
     * and on any step that follows one the loop skipped — a step discards its own elapsed time rather
     * than handing it to the next one, for the same reason the clamp exists.
     *
     * @param vehicle    the vehicle being stepped
     * @param deltaTicks server ticks this step covered, measured and clamped. Never negative; {@code 0}
     *                   on the first step
     */
    default void onStep(@NotNull Vehicle vehicle, float deltaTicks) {
    }

    /**
     * Called once when the vehicle is torn down, before its riders are put down.
     *
     * <p>The last call a controller receives, and the only one a <em>detached</em> controller still
     * receives. Release anything it opened; do not assume the vehicle is still usable afterwards.
     *
     * <h2>The one hook with no thread promise</h2>
     * Three things can decide a vehicle is finished — a caller, a control step, and the platform
     * reporting that the root entity has been removed — so this runs on whichever of them got there
     * first. That may be the root's owning thread, the server's main thread during a plugin disable, or
     * the platform's retired callback, which runs in critical code that may not remove an entity and
     * cannot defer work to anywhere else.
     *
     * <p>It is called inline on that thread rather than scheduled onto the owning one, and
     * deliberately: the two paths where scheduling would be needed are exactly the two where it cannot
     * work — a retired root has no scheduler left, and a task scheduled during disable is a task that
     * never runs, so a controller would simply never be told.
     *
     * <p>Therefore <strong>touch nothing but your own state here</strong>. Not the vehicle, not the
     * riders, not a block. The riders are still aboard when this is called, which is what makes it the
     * right place to <em>forget</em> them; it is not a place to move them.
     *
     * @param vehicle the vehicle being torn down, for identity. Already stopped
     */
    default void onClosed(@NotNull Vehicle vehicle) {
    }
}
