package pandastic.studio.models.nms;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.ai.AiMemory;
import pandastic.studio.models.internal.nms.MemoryStore;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * The brain's four memory operations, with the registration question asked first.
 *
 * <h2>Why the brain is read off the creature rather than held</h2>
 * Because the store has to exist before the brain does. The activity list — and therefore every
 * behaviour adapter, and therefore the context they share, and therefore this store — is built
 * <em>inside</em> the brain's construction, so a store holding a brain reference could not be created
 * until after the thing it needs to hand to its own constituents already existed.
 *
 * <p>Reading it per call costs one field read and removes that ordering problem entirely. It also makes
 * the store correct across an uninstall for free: once the species' own brain is back in place, a slot
 * this plan defined is no longer registered on it, so every operation here answers the absent case.
 *
 * <h2>The two failures it has to avoid, and the one public method that avoids both</h2>
 * The server's memory API is asymmetric. Writing a slot the brain was not built with is accepted and
 * discarded; reading one throws, from inside whatever was calling it. Both are avoided by asking
 * {@code checkMemory(slot, REGISTERED)} first, which is the server's own public way of asking whether a
 * slot exists on this brain.
 *
 * <p>That check is not redundant with the guard above the seam. The guard refuses a memory the
 * <em>plan</em> does not declare, which covers the ordinary mistake. What it cannot cover is a plan
 * whose brain has since been replaced — by an uninstall, or by a second plan — being reached by a sensor
 * body that was already in flight when that happened.
 *
 * <h2>Threading</h2>
 * Owning-thread only, every method, and unchecked here: the guard is
 * {@code GuardedMemoryView}, above the seam, where the failure can be reported against a plan.
 */
final class BrainMemoryStore implements MemoryStore {

    private final @NotNull LivingEntity entity;

    /** The plan's memories, resolved to slot keys once at install, by api key. */
    private final @NotNull Map<String, MemoryModuleType<?>> slots;

    /**
     * Whether this store should still do anything.
     *
     * <p>A supplier rather than a flag, so the installation that owns the store can gate it on its own
     * latch without the store having to be told twice. The brainless view used during construction
     * passes a constant {@code true}, because at that point there is nothing to have been torn down.
     */
    private final @NotNull BooleanSupplier live;

    BrainMemoryStore(
            @NotNull LivingEntity entity,
            @NotNull Map<String, MemoryModuleType<?>> slots,
            @NotNull BooleanSupplier live
    ) {
        this.entity = Objects.requireNonNull(entity, "entity");
        this.slots = Map.copyOf(slots);
        this.live = Objects.requireNonNull(live, "live");
    }

    @Override
    public @Nullable Object read(@NotNull AiMemory<?> memory) {
        if (memory.isVanilla()) {
            // A vanilla memory's value is a server-internal object and must not cross the seam. The api
            // makes this unreachable by typing such a reference as AiMemory<Void>; this is the belt to
            // that braces, and the reason read() is allowed to answer null rather than throw.
            return null;
        }
        final Brain<LivingEntity> brain = registeredBrainFor(memory);
        if (brain == null) {
            return null;
        }
        final Optional<Object> held = brain.getMemory(slotOf(memory));
        return held.orElse(null);
    }

    @Override
    public void write(@NotNull AiMemory<?> memory, @NotNull Object value, long expiryTicks) {
        final Brain<LivingEntity> brain = registeredBrainFor(memory);
        if (brain == null) {
            return;
        }
        if (expiryTicks == AiMemory.NEVER_EXPIRES) {
            brain.setMemory(slotOf(memory), value);
        } else {
            brain.setMemoryWithExpiry(slotOf(memory), value, expiryTicks);
        }
    }

    @Override
    public boolean present(@NotNull AiMemory<?> memory) {
        final Brain<LivingEntity> brain = registeredBrainFor(memory);
        return brain != null && brain.hasMemoryValue(slotOf(memory));
    }

    @Override
    public void erase(@NotNull AiMemory<?> memory) {
        final Brain<LivingEntity> brain = registeredBrainFor(memory);
        if (brain == null) {
            return;
        }
        brain.eraseMemory(slotOf(memory));
    }

    @Override
    public @NotNull String toString() {
        return "BrainMemoryStore[" + this.entity.getUUID() + ", " + this.slots.size() + " slot(s)]";
    }

    /**
     * The creature's brain, if this store is live and the brain has {@code memory}'s slot.
     *
     * <p>Every operation funnels through here so that the registration check cannot be forgotten by a
     * fifth one. The cast to {@code Brain<LivingEntity>} is unchecked and safe: the parameter is the
     * species, it is erased at runtime, and it exists only to relate the brain's methods to the entity
     * they are called with — which here is the brain's own owner.
     *
     * @return the brain, or {@code null} when nothing should happen
     */
    @SuppressWarnings("unchecked")
    private @Nullable Brain<LivingEntity> registeredBrainFor(@NotNull AiMemory<?> memory) {
        if (!this.live.getAsBoolean() || !this.slots.containsKey(memory.getKey())) {
            return null;
        }
        final Brain<LivingEntity> brain = (Brain<LivingEntity>) this.entity.getBrain();
        return brain.checkMemory(slotOf(memory), MemoryStatus.REGISTERED) ? brain : null;
    }

    /**
     * The slot for {@code memory}, narrowed to carry an {@code Object}.
     *
     * <p>Only called after {@link #registeredBrainFor} has confirmed the slot exists, so the lookup
     * cannot miss. The cast is unchecked and safe for the same reason a slot key carries no runtime type
     * information at all: it holds an optional codec, which this layer never installs, so its type
     * parameter relates the argument and result types of the brain's accessors and nothing else.
     * Narrowing it to {@code Object} therefore cannot be wrong at runtime, and the real type discipline
     * is enforced above the seam, where the value is narrowed with {@code Class::cast} against the type
     * the plan declared.
     */
    @SuppressWarnings("unchecked")
    private @NotNull MemoryModuleType<Object> slotOf(@NotNull AiMemory<?> memory) {
        return (MemoryModuleType<Object>) this.slots.get(memory.getKey());
    }
}
