package pandastic.studio.models.api.input;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * One delivery of one matched combination to one handler.
 *
 * <h2>Read the snapshot, do not read the world</h2>
 * A handler is called on whichever thread observed the input, which is either the player's own region
 * thread or a network thread — {@link #dispatch()} says which, and {@link InputDispatch} says what each
 * permits. Everything a decision needs is on {@link #snapshot()}, which is an immutable value and safe
 * to read from anywhere. Anything that touches an entity, a model or a vehicle goes through
 * {@link #onOwningRegion(Runnable)}.
 *
 * <p>In particular: a vehicle must not be driven from here. The published snapshot is what a vehicle's
 * own scheduled step reads, once per step, and a handler that tried to move it directly would be
 * writing to an entity from a thread that may not own it. Handlers are for decisions that are about the
 * input itself — arming something, toggling a mode, suppressing a key.
 *
 * <h2>Suppression is a request, not a statement</h2>
 * {@link #suppress()} returns whether it was honoured, and it is refused in two cases that the
 * consumer was already told about at registration: the binding did not declare
 * {@link InputResponse#SUPPRESS}, or this delivery came from a source that cannot stop this family of
 * input. A handler that cares should check the answer; one that does not is no worse off than if the
 * method returned {@code void}, which is the version of this api that hid the problem.
 *
 * <h2>Not thread-safe, and does not need to be</h2>
 * One delivery is built, passed to one handler, and discarded. It is never shared between threads and
 * must not be retained past the handler's return — {@link #snapshot()} is the part that is safe to keep.
 */
public final class InputEvent {

    /**
     * A suppressor for a delivery that cannot be stopped.
     *
     * <p>Returns {@code false} rather than throwing, because a handler calling {@link #suppress()} on a
     * source without the capability has made a portability mistake and not a programming error: the
     * same code is correct on a server with the packet source installed.
     */
    public static final @NotNull BooleanSupplier UNSUPPORTED = () -> false;

    private final @NotNull Player player;
    private final @NotNull InputCombination combination;
    private final @NotNull InputSnapshot snapshot;
    private final @NotNull InputResponse response;
    private final @NotNull InputDispatch dispatch;
    private final @NotNull BooleanSupplier suppressor;
    private final @NotNull Executor owningRegion;

    private boolean suppressed;

    /**
     * Builds a delivery.
     *
     * <p>Public because the capture layer is in another module, and because a test exercising a
     * handler needs to be able to make one. A consumer never calls this.
     *
     * @param player       whose input matched
     * @param combination  what matched
     * @param snapshot     the input it matched against, which is the value a handler should read
     * @param response     what the binding declared it may do
     * @param dispatch     which thread the handler is on
     * @param suppressor   how to stop this input; {@link #UNSUPPORTED} when it cannot be stopped.
     *                     Returns whether the stop took effect
     * @param owningRegion where a task that touches the world has to run. Called inline by
     *                     {@link #onOwningRegion(Runnable)} when {@code dispatch} is already
     *                     {@link InputDispatch#PLAYER_REGION}
     */
    public InputEvent(
            @NotNull Player player,
            @NotNull InputCombination combination,
            @NotNull InputSnapshot snapshot,
            @NotNull InputResponse response,
            @NotNull InputDispatch dispatch,
            @NotNull BooleanSupplier suppressor,
            @NotNull Executor owningRegion
    ) {
        this.player = Objects.requireNonNull(player, "player");
        this.combination = Objects.requireNonNull(combination, "combination");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.response = Objects.requireNonNull(response, "response");
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
        this.suppressor = Objects.requireNonNull(suppressor, "suppressor");
        this.owningRegion = Objects.requireNonNull(owningRegion, "owningRegion");
    }

    /**
     * Whose input this is.
     *
     * <p>Safe to <em>name</em> from any thread — the identity and the UUID are stable — but reading its
     * position, inventory or passengers is only legal when {@link #dispatch()} is
     * {@link InputDispatch#PLAYER_REGION}.
     */
    public @NotNull Player player() {
        return this.player;
    }

    /** What matched. */
    public @NotNull InputCombination combination() {
        return this.combination;
    }

    /**
     * The input this matched against.
     *
     * <p>Immutable and safe to keep, including across threads. This is the one thing a handler should
     * read, and it is the same object the player's other bindings saw for this publication, so two
     * handlers cannot disagree about what happened.
     */
    public @NotNull InputSnapshot snapshot() {
        return this.snapshot;
    }

    /** What the binding declared it may do about the input. */
    public @NotNull InputResponse response() {
        return this.response;
    }

    /** Which thread the handler is running on, and therefore what it may touch. */
    public @NotNull InputDispatch dispatch() {
        return this.dispatch;
    }

    /** Whether {@link #suppress()} on this delivery can do anything. */
    public boolean canSuppress() {
        return this.response == InputResponse.SUPPRESS && this.suppressor != UNSUPPORTED;
    }

    /**
     * Asks for this input not to take effect.
     *
     * <p>Idempotent: a second call on the same delivery reports the first call's outcome without asking
     * the source again.
     *
     * @return whether the input was actually stopped
     */
    public boolean suppress() {
        if (this.suppressed) {
            return true;
        }
        if (this.response != InputResponse.SUPPRESS) {
            return false;
        }
        this.suppressed = this.suppressor.getAsBoolean();
        return this.suppressed;
    }

    /** Whether {@link #suppress()} has been called on this delivery and was honoured. */
    public boolean isSuppressed() {
        return this.suppressed;
    }

    /**
     * Runs {@code task} where it is legal to touch the player.
     *
     * <p>Inline when the handler is already on the player's region thread, and on the player's own
     * entity scheduler otherwise. Either way the task sees the player alive or does not run — the
     * entity scheduler drops a task whose entity is gone, which is the behaviour a handler wants and
     * the reason this is not a plain "schedule on the global thread".
     *
     * <p>Note what this does <strong>not</strong> promise: ordering against other inputs. Two packets
     * handed across in sequence can be executed in either order relative to each other's region tasks,
     * so a task here should act on {@link #snapshot()} rather than re-derive state from "what just
     * happened".
     *
     * @param task what to run
     */
    public void onOwningRegion(@NotNull Runnable task) {
        Objects.requireNonNull(task, "task");
        if (this.dispatch == InputDispatch.PLAYER_REGION) {
            task.run();
            return;
        }
        this.owningRegion.execute(task);
    }

    @Override
    public @NotNull String toString() {
        return "InputEvent[" + this.combination.describe() + " for " + this.player.getName()
                + " on " + this.dispatch + ']';
    }
}
