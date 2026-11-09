package pandastic.studio.models.internal.ai;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.ai.AiIntent;

import java.util.Objects;

/**
 * The one field a decision crosses threads through.
 *
 * <h2>Why a volatile reference to an immutable value, and nothing more</h2>
 * The same argument as {@link pandastic.studio.models.internal.sim.DriverHolder}, from the other
 * direction. A behaviour decides on the region thread that owns its creature; the simulation reads that
 * decision on its own thread, off the tick. One store of one immutable reference is what carries the
 * happens-before edge: a reader that sees the new intent sees every component of it, and sees them all
 * from the same decision.
 *
 * <p>Anything cheaper would be wrong and anything more expensive would be unnecessary. A lock would put
 * a region thread and the simulation thread in contention once per tick per creature for a value that
 * is never partially useful. Separate fields for the move and look targets would let a reader see a move
 * target from one tick and a look target from the next, which is precisely the kind of state no decision
 * ever produced.
 *
 * <h2>Reset is part of teardown, not a convenience</h2>
 * An uninstalled creature must not keep reporting the last thing it decided — something reading intents
 * would go on acting on a plan that is no longer installed. {@link #reset()} is called from the
 * teardown, which can run from any thread, which is the other reason there is no lock here.
 */
public final class IntentHolder {

    private volatile @NotNull AiIntent intent = AiIntent.NONE;

    /** A holder that starts out deciding nothing. */
    public IntentHolder() {
        // The field initialiser is the whole constructor; AiIntent.NONE is an identity value and not a
        // configured default, so there is nothing to inject.
    }

    /**
     * Publishes {@code intent}, replacing whatever was published before.
     *
     * <p>Last writer wins, including within one tick, which is why priority ordering inside an activity
     * matters: the lowest-priority behaviour that ran last is the one whose decision stands.
     *
     * @param intent the decision
     */
    public void publish(@NotNull AiIntent intent) {
        this.intent = Objects.requireNonNull(intent, "intent");
    }

    /**
     * The current decision.
     *
     * <p>One volatile read. Safe from any thread, which is the point.
     *
     * @return the live intent, never {@code null}
     */
    public @NotNull AiIntent current() {
        return this.intent;
    }

    /** Forgets the current decision. Part of teardown; see the class documentation. */
    public void reset() {
        this.intent = AiIntent.NONE;
    }

    @Override
    public @NotNull String toString() {
        return "IntentHolder[" + this.intent + "]";
    }
}
