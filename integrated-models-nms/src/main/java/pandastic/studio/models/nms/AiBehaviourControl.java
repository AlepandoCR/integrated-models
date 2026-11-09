package pandastic.studio.models.nms;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.ai.AiBehaviour;
import pandastic.studio.models.api.ai.AiContext;
import pandastic.studio.models.internal.nms.AiFaultSink;

import java.util.Objects;
import java.util.Set;

/**
 * One {@link AiBehaviour}, wearing the interface the brain knows how to tick.
 *
 * <h2>The lifecycle translation</h2>
 * The server's contract is four methods and a status field; the api's is five callbacks. They line up
 * almost exactly, and the two places they do not are worth naming:
 *
 * <ul>
 *   <li>The server has no "should I keep going" call. It has {@code tickOrStop}, which is expected to
 *       decide and then do one or the other. So {@link AiBehaviour#shouldContinue} and
 *       {@link AiBehaviour#maxDurationTicks()} are both evaluated at the top of that method, and the
 *       duration is measured against the game time captured at start rather than a counter of our own —
 *       a creature in an unloaded chunk stops ticking, and a counter would make a five-second behaviour
 *       last five seconds of <em>ticking</em> rather than five seconds.</li>
 *   <li>The server asks for the status rather than being told. {@link #status} is therefore the single
 *       piece of state here, and it is the brain's only view of whether this behaviour is running.</li>
 * </ul>
 *
 * <h2>Containment, and why it is {@link Throwable}</h2>
 * This object is ticked from inside the brain's tick, from inside the creature's tick, on a region
 * thread. An exception escaping any of these four methods does not fail the behaviour — it fails the
 * creature's tick, and because the brain ticks every tick it fails it again on the next one. So every
 * body call is wrapped.
 *
 * <p>{@link Throwable} rather than {@link Exception} because the most likely failure in a plugin-supplied
 * body is a {@link NoSuchMethodError} from code compiled against a different version of something, and
 * that is exactly as repeating and exactly as fatal to the creature's usefulness as a
 * {@link NullPointerException}. Letting it through would make the containment depend on which supertype
 * the failure happened to have.
 *
 * <h2>Disabled, because removed is not available</h2>
 * When the fault sink says a body has failed too often there is no way to take it out of the brain —
 * the server exposes clearing every behaviour and nothing finer. So {@link #disabled} is set and
 * {@link #tryStart} refuses from then on, which has the same effect from the brain's point of view: the
 * behaviour is never chosen and the ones below it in priority get their turn. The creature ends up
 * missing part of its plan, which is a degraded creature rather than a frozen one.
 *
 * <h2>One per creature</h2>
 * This holds running state, so a single instance must not be shared between two creatures. It is
 * created inside the brain's activity supplier, which the server calls once per brain, which is once
 * per installation.
 */
final class AiBehaviourControl implements BehaviorControl<LivingEntity> {

    private final @NotNull AiBehaviour behaviour;
    private final @NotNull AiContext context;
    private final @NotNull AiFaultSink faults;
    private final @NotNull Set<MemoryModuleType<?>> requiredMemories;

    /** The brain's only view of whether this behaviour is running. */
    private @NotNull Behavior.Status status = Behavior.Status.STOPPED;

    /**
     * Game time at which the current run started.
     *
     * <p>Game time rather than a tick counter, so a duration survives the creature's chunk unloading:
     * the brain stops ticking, the clock stops with it, and the behaviour resumes with the elapsed count
     * it had. A wall clock would have the behaviour expire while the creature was not there.
     */
    private long startedAt;

    /** Set once the configured consecutive-failure limit has been reached. Never cleared. */
    private boolean disabled;

    /**
     * @param behaviour        the plan's body
     * @param context          the per-creature context, built once per installation
     * @param faults           where an exception goes, and who decides whether to keep running this
     * @param requiredMemories the resolved slots for {@link AiBehaviour#requiredMemories()}
     */
    AiBehaviourControl(
            @NotNull AiBehaviour behaviour,
            @NotNull AiContext context,
            @NotNull AiFaultSink faults,
            @NotNull Set<MemoryModuleType<?>> requiredMemories
    ) {
        this.behaviour = Objects.requireNonNull(behaviour, "behaviour");
        this.context = Objects.requireNonNull(context, "context");
        this.faults = Objects.requireNonNull(faults, "faults");
        this.requiredMemories = Set.copyOf(requiredMemories);
    }

    @Override
    public @NotNull Behavior.Status getStatus() {
        return this.status;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The brain refuses to even consider a behaviour while one of these is empty, which is a check
     * done once by the brain rather than at the top of every {@link AiBehaviour#canStart}.
     */
    @Override
    public @NotNull Set<MemoryModuleType<?>> getRequiredMemories() {
        return this.requiredMemories;
    }

    @Override
    public boolean tryStart(@NotNull ServerLevel level, @NotNull LivingEntity entity, long gameTime) {
        if (this.disabled) {
            return false;
        }
        try {
            if (!this.behaviour.canStart(this.context)) {
                // A refusal is not a success: the body ran and answered. Reporting it as a success is
                // what keeps the consecutive count honest for a behaviour whose test usually works.
                this.faults.succeeded(AiFaultSink.BEHAVIOUR, this.behaviour.name());
                return false;
            }
            this.behaviour.start(this.context);
            this.faults.succeeded(AiFaultSink.BEHAVIOUR, this.behaviour.name());
        } catch (final Throwable failure) {
            report(failure);
            // Deliberately not started. A behaviour whose start threw is in whatever state its own
            // start left it, and ticking it from there is how one failure becomes a stuck creature.
            return false;
        }
        this.status = Behavior.Status.RUNNING;
        this.startedAt = gameTime;
        return true;
    }

    @Override
    public void tickOrStop(@NotNull ServerLevel level, @NotNull LivingEntity entity, long gameTime) {
        final long running = Math.max(0L, gameTime - this.startedAt);
        if (!wantsToContinue(running)) {
            doStop(level, entity, gameTime);
            return;
        }
        try {
            this.behaviour.tick(this.context, running);
            this.faults.succeeded(AiFaultSink.BEHAVIOUR, this.behaviour.name());
        } catch (final Throwable failure) {
            report(failure);
            doStop(level, entity, gameTime);
        }
    }

    @Override
    public void doStop(@NotNull ServerLevel level, @NotNull LivingEntity entity, long gameTime) {
        // Status first, so a body that throws on the way out is still a stopped behaviour as far as the
        // brain is concerned. The alternative is a behaviour the brain believes is running and which
        // will never be ticked again.
        this.status = Behavior.Status.STOPPED;
        try {
            this.behaviour.stop(this.context);
        } catch (final Throwable failure) {
            report(failure);
        }
    }

    @Override
    public @NotNull String debugString() {
        return this.behaviour.name();
    }

    @Override
    public @NotNull String toString() {
        return "AiBehaviourControl[" + this.behaviour.name() + ", " + this.status
                + (this.disabled ? ", disabled]" : "]");
    }

    /**
     * Whether the behaviour wants another tick, with its own answer contained.
     *
     * <p>A test that throws is treated as "no". It is the one body call where the failure has an obvious
     * safe answer, and taking it means a behaviour with a broken continuation test stops instead of
     * running forever.
     */
    private boolean wantsToContinue(long running) {
        final long limit = this.behaviour.maxDurationTicks();
        if (limit != AiBehaviour.UNBOUNDED && running >= limit) {
            return false;
        }
        try {
            final boolean answer = this.behaviour.shouldContinue(this.context, running);
            this.faults.succeeded(AiFaultSink.BEHAVIOUR, this.behaviour.name());
            return answer;
        } catch (final Throwable failure) {
            report(failure);
            return false;
        }
    }

    /** Reports to the sink and records its verdict. */
    private void report(@NotNull Throwable failure) {
        if (!this.faults.failed(AiFaultSink.BEHAVIOUR, this.behaviour.name(), failure)) {
            this.disabled = true;
        }
    }
}
