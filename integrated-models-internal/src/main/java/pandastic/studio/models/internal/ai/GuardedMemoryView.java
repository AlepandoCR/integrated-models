package pandastic.studio.models.internal.ai;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.ai.AiBounds;
import pandastic.studio.models.api.ai.AiMemory;
import pandastic.studio.models.api.ai.AiMemoryView;
import pandastic.studio.models.api.ai.AiPlan;
import pandastic.studio.models.internal.nms.MemoryStore;

import java.util.Objects;
import java.util.Optional;

/**
 * Every rule about reading and writing a memory, in one place, above the NMS seam.
 *
 * <h2>The two failures it converts</h2>
 * The server's memory API fails in opposite directions and neither is loud. A write to a memory the
 * brain was not built with is accepted and discarded, so the mistake presents as a behaviour that never
 * fires. A read of one throws, from inside the brain's tick, with the server's class names in the
 * message and no mention of the memory. This class turns both into one {@link IllegalArgumentException}
 * naming the plan and the key, raised before the seam is reached.
 *
 * <p>Which it can do because {@link AiPlan} already derived the exact set the brain was built with. The
 * membership test is therefore not a heuristic — it is the same set, so a memory that passes here is a
 * memory the brain has, and one that fails is one no amount of retrying would have made work.
 *
 * <h2>The thread check, and why it is here rather than only documented</h2>
 * Reading a creature's memory from a thread that does not own it is undefined: the brain's memory map is
 * a plain map, written by the creature's own tick. There is no strict checking mode to turn on, so the
 * read succeeds, returns something, and nothing anywhere says it was torn.
 *
 * <p>Inside a behaviour or sensor body the check can never fail, because the layer arranged the thread
 * before calling. It exists for the other caller: a plugin holding an {@link AiController} and reading a
 * memory from an event handler for a different entity, a scheduled task, or the simulation thread. That
 * is a mistake a library cannot prevent and can at least refuse.
 *
 * <h2>Why the expiry ceiling is enforced on the write and not only on the plan</h2>
 * A memory's <em>default</em> expiry is checked when the plan is built, but
 * {@link #set(AiMemory, Object, long)} lets a body choose a different one per write — and a body is
 * free to compute it. A cooldown of "the remaining daylight in ticks" is a reasonable thing to write
 * and an unreasonable thing to trust, so the operator's ceiling applies to both paths. The alternative
 * is a memory that outlives every plan that referenced it.
 *
 * <h2>Allocation</h2>
 * One {@link Optional} per read, and nothing else. A view is built once per installation and every
 * field is final, so the per-tick cost of this class is the membership test — a hash lookup in a set
 * sized by the plan.
 */
public final class GuardedMemoryView implements AiMemoryView {

    private final @NotNull AiPlan plan;
    private final @NotNull MemoryStore store;
    private final @NotNull AiHost host;
    private final @NotNull AiBounds bounds;

    /**
     * @param plan   the installed plan, whose memory set is the only one this view admits
     * @param store  the brain-backed access this view guards
     * @param host   who gets to ask; consulted per call, because a creature can change region
     * @param bounds the operator's limits, for the per-write expiry ceiling
     */
    public GuardedMemoryView(
            @NotNull AiPlan plan,
            @NotNull MemoryStore store,
            @NotNull AiHost host,
            @NotNull AiBounds bounds
    ) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.store = Objects.requireNonNull(store, "store");
        this.host = Objects.requireNonNull(host, "host");
        this.bounds = Objects.requireNonNull(bounds, "bounds");
    }

    @Override
    public <ValueType> @NotNull Optional<ValueType> get(@NotNull AiMemory<ValueType> memory) {
        admit(memory, "reading");
        // Class::cast rather than an unchecked cast: the store is untyped by necessity and a value of
        // the wrong type fails here, naming both types, instead of at whichever call site assigned it.
        return Optional.ofNullable(memory.cast(this.store.read(memory)));
    }

    @Override
    public <ValueType> void set(@NotNull AiMemory<ValueType> memory, @NotNull ValueType value) {
        set(memory, value, memory.getDefaultExpiryTicks());
    }

    @Override
    public <ValueType> void set(
            @NotNull AiMemory<ValueType> memory,
            @NotNull ValueType value,
            long expiryTicks
    ) {
        admit(memory, "writing");
        Objects.requireNonNull(value, "value");
        if (expiryTicks < 0L) {
            throw new IllegalArgumentException("Memory '" + memory.getKey() + "' of plan '"
                    + this.plan.getName() + "' was written with a negative expiry (" + expiryTicks
                    + "); use AiMemory.NEVER_EXPIRES for a value that stays until something erases it");
        }
        if (expiryTicks > this.bounds.maxMemoryExpiryTicks()) {
            throw new IllegalArgumentException("Memory '" + memory.getKey() + "' of plan '"
                    + this.plan.getName() + "' was written with an expiry of " + expiryTicks
                    + " ticks, above the configured maxMemoryExpiryTicks of "
                    + this.bounds.maxMemoryExpiryTicks() + " in ai.yml");
        }
        this.store.write(memory, value, expiryTicks);
    }

    @Override
    public boolean has(@NotNull AiMemory<?> memory) {
        admit(memory, "testing");
        return this.store.present(memory);
    }

    @Override
    public void clear(@NotNull AiMemory<?> memory) {
        admit(memory, "erasing");
        this.store.erase(memory);
    }

    @Override
    public @NotNull String toString() {
        return "GuardedMemoryView[" + this.plan.getName() + " on " + this.host.describe() + "]";
    }

    /**
     * Refuses the two things that cannot work, with a message that says which one it was.
     *
     * <p>Order matters: plan membership is checked first because it is a programming error that holds
     * regardless of thread, and reporting the thread instead would send the reader looking for a
     * concurrency problem that is not there.
     *
     * @param memory    the memory being reached for
     * @param operation a gerund naming what was attempted, so both messages read as sentences
     */
    private void admit(@NotNull AiMemory<?> memory, @NotNull String operation) {
        Objects.requireNonNull(memory, "memory");
        if (!this.plan.declares(memory)) {
            throw new IllegalArgumentException("Plan '" + this.plan.getName()
                    + "' does not declare memory '" + memory.getKey() + "', so " + operation
                    + " it cannot work: the creature's brain was built with the plan's memories and"
                    + " only those. A write to any other is accepted and discarded, and a read of one"
                    + " throws from inside the creature's tick. Name it in the behaviour's"
                    + " usedMemories(), the sensor's writtenMemories(), or the plan's declareMemory()");
        }
        if (!this.host.isOwnedByCurrentThread()) {
            throw new IllegalStateException("Memory '" + memory.getKey() + "' of plan '"
                    + this.plan.getName() + "' is not available for " + operation
                    + " from this thread: " + this.host.describe() + " is owned by another region, and"
                    + " its memory map is written by its own tick with no synchronisation. Schedule"
                    + " onto the creature's own scheduler, or read the published intent instead, which"
                    + " is safe anywhere");
        }
    }
}
