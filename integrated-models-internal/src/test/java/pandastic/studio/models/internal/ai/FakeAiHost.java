package pandastic.studio.models.internal.ai;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import pandastic.studio.models.api.ai.AiPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A creature, a thread rule and a region boundary, all scriptable.
 *
 * <h2>Why one fake covers the whole platform surface of this layer</h2>
 * Because {@link AiHost} is that surface. Everything else in the AI package is arithmetic, state and
 * ordering over it — the plan membership rule, the scan shrinking, the fault counting, the install and
 * teardown sequences — so substituting this one interface makes all of it exercisable with no server.
 *
 * <h2>What is scriptable, and why each knob exists</h2>
 * <ul>
 *   <li>{@link #owned} — whether this thread owns the creature. The single most important thing to be
 *       able to turn off, because the platform has no mode that would do it for us.</li>
 *   <li>{@link #ownedRadiusBlocks} — the region boundary, expressed as the furthest a scan may reach.
 *       Negative means "this thread owns nothing", which is the case a scan reports as unavailable
 *       rather than as empty.</li>
 *   <li>{@link #alive} and {@link #schedulable} — the two ways a creature can be gone. They are
 *       different: a creature can be dead while its scheduler still accepts work, and a creature's
 *       scheduler can refuse work the moment it is removed, and the layer unwinds differently for
 *       each.</li>
 * </ul>
 *
 * <h2>The creature reference throws on every call, deliberately</h2>
 * Nothing in this layer touches a creature directly — identity comes from {@link #creatureId()} and
 * everything else from this seam — so {@link #mob()} answers a proxy that refuses. That makes the rule
 * an assertion rather than a convention; see {@link FakeMob}.
 */
final class FakeAiHost implements AiHost {

    /** Work submitted through {@link #submit}, in order, run only when a test says so. */
    final List<Runnable> submitted = new ArrayList<>();

    /** Repeating bodies scheduled through {@link #repeat}, in order. */
    final List<ScheduledBody> scheduled = new ArrayList<>();

    boolean owned = true;
    boolean alive = true;
    boolean schedulable = true;

    /** Negative for a thread that owns nothing at all; see {@link AiHost#NOT_OWNED}. */
    double ownedRadiusBlocks = Double.MAX_VALUE;

    AiPoint position = AiPoint.ORIGIN;
    long gameTime;

    List<Entity> entities = List.of();
    List<Player> players = List.of();

    /** The last radius {@link #entitiesWithin} or {@link #playersWithin} was actually called with. */
    double lastScanRadius = Double.NaN;

    private final UUID creatureId = UUID.randomUUID();

    /**
     * A reference that exists and refuses to behave like a creature; see {@link FakeMob}.
     *
     * <p>The layer passes it to the NMS seam and nothing else, so a proxy that throws on every call is
     * both sufficient and an assertion that it stays that way.
     */
    private final Mob mob = FakeMob.create();

    /** One repeating body and its period, with the handle a test can inspect. */
    record ScheduledBody(Runnable body, Runnable retired, long periodTicks, FakeAiTask task) {
    }

    /** A cancellable handle that remembers whether it was cancelled. */
    static final class FakeAiTask implements AiTask {

        private boolean cancelled;

        @Override
        public void cancel() {
            this.cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }
    }

    @Override
    public Mob mob() {
        return this.mob;
    }

    @Override
    public UUID creatureId() {
        return this.creatureId;
    }

    @Override
    public boolean isOwnedByCurrentThread() {
        return this.owned;
    }

    @Override
    public boolean isAlive() {
        return this.alive;
    }

    @Override
    public AiPoint position() {
        return this.position;
    }

    @Override
    public long gameTime() {
        return this.gameTime;
    }

    @Override
    public boolean submit(Runnable work) {
        if (!this.schedulable) {
            return false;
        }
        this.submitted.add(work);
        return true;
    }

    @Override
    public AiTask repeat(Runnable body, Runnable retired, long periodTicks) {
        if (!this.schedulable) {
            return null;
        }
        final FakeAiTask task = new FakeAiTask();
        this.scheduled.add(new ScheduledBody(body, retired, periodTicks, task));
        return task;
    }

    @Override
    public double ownedRadius(double requestedBlocks) {
        if (!Double.isFinite(requestedBlocks) || requestedBlocks < 0.0D) {
            throw new IllegalArgumentException(
                    "requestedBlocks must be finite and non-negative but was " + requestedBlocks);
        }
        if (this.ownedRadiusBlocks < 0.0D) {
            return NOT_OWNED;
        }
        return Math.min(requestedBlocks, this.ownedRadiusBlocks);
    }

    @Override
    public List<Entity> entitiesWithin(double radiusBlocks) {
        this.lastScanRadius = radiusBlocks;
        return this.entities;
    }

    @Override
    public List<Player> playersWithin(double radiusBlocks) {
        this.lastScanRadius = radiusBlocks;
        return this.players;
    }

    @Override
    public String describe() {
        return "FakeCreature " + this.creatureId;
    }

    /** Runs every queued one-shot submission and clears the queue. */
    void drainSubmitted() {
        final List<Runnable> pending = List.copyOf(this.submitted);
        this.submitted.clear();
        pending.forEach(Runnable::run);
    }

    /** Runs the body of the one sensor at {@code index} once. */
    void tickSensor(int index) {
        this.scheduled.get(index).body().run();
    }

    /** Fires the retired callback the layer installed, as the platform would. */
    void retire() {
        this.alive = false;
        this.schedulable = false;
        this.scheduled.forEach(entry -> entry.retired().run());
    }

    /** How many scheduled bodies have not been cancelled. */
    long liveTaskCount() {
        return this.scheduled.stream().filter(entry -> !entry.task().isCancelled()).count();
    }
}
