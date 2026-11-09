package pandastic.studio.models.nms;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.ai.AiMemory;
import pandastic.studio.models.api.ai.AiMemoryStatus;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns an {@link AiMemory} into the server's own memory slot key.
 *
 * <h2>The registry trap</h2>
 * The memory registry is a <em>defaulted</em> one, which means a lookup of a key it does not have
 * returns the default value — the server's own {@code dummy} memory — instead of nothing. So a plan
 * naming {@code attack_targets} instead of {@code attack_target} would resolve, install, and share one
 * slot with every other mistyped key on the server. The lookup here therefore asks
 * {@code containsKey} first and refuses, which converts a typo into a sentence at install time.
 *
 * <h2>Why a plugin-defined memory needs no registration</h2>
 * A memory slot key is an ordinary object with a public constructor; the registry only matters for
 * <em>serialisation</em>, because a brain writes to disk the memories whose keys it can name. Ours
 * cannot be named, so they are not written — which is the behaviour this layer wants anyway, and is
 * documented as such on {@link AiMemory}. Registering them would mean mutating a frozen registry, which
 * is both unsupported and would make a creature's memories survive a plan being changed.
 *
 * <h2>Why defined keys are cached for the life of the server</h2>
 * One key has to mean one slot, or nothing works. If a fresh slot object were created per installation
 * then two creatures running the same plan would have unrelated slots — harmless, since a slot lives in
 * one brain — but <em>reinstalling</em> a plan on one creature would lose every memory it held, because
 * the memories carried across the rebuild are keyed by the old objects and the new brain would be
 * asking with new ones. Caching by key makes a reinstall keep what the creature knew, which is the
 * behaviour a plan author expects and the one the server's own model implies.
 *
 * <p>The cache is bounded by the number of distinct keys any plan on the server has ever declared,
 * which is bounded by the plans themselves. There is nothing to evict: a slot object is two fields and
 * a plan that stops using a key is not a reason to forget what it meant.
 */
final class MemoryTypes {

    /**
     * Plugin-defined slots, by identifier. Concurrent because installs happen on whichever region owns
     * the creature.
     */
    private static final @NotNull Map<String, MemoryModuleType<?>> DEFINED = new ConcurrentHashMap<>();

    private MemoryTypes() {
        throw new AssertionError("No instances.");
    }

    /**
     * The slot key for {@code memory}.
     *
     * @param memory the plan's memory
     * @return the server's slot key
     * @throws IllegalStateException if {@code memory} names one of the server's own memories and the
     *                               server does not have it — a typo, or a key that existed in an
     *                               earlier version
     */
    static @NotNull MemoryModuleType<?> of(@NotNull AiMemory<?> memory) {
        if (!memory.isVanilla()) {
            // Optional.empty() for the codec: no codec means the slot cannot serialise, which is
            // exactly the contract AiMemory.defined documents.
            return DEFINED.computeIfAbsent(memory.getKey(),
                    ignored -> new MemoryModuleType<>(Optional.empty()));
        }

        final Identifier identifier = Identifier.tryParse(memory.getKey());
        if (identifier == null || !BuiltInRegistries.MEMORY_MODULE_TYPE.containsKey(identifier)) {
            throw new IllegalStateException("This server has no memory called '" + memory.getKey()
                    + "'. The memory registry answers its default value for an unknown key rather than"
                    + " nothing, so an unchecked lookup would have shared one slot with every other"
                    + " mistyped key instead of failing");
        }
        return BuiltInRegistries.MEMORY_MODULE_TYPE.getValue(identifier);
    }

    /**
     * The server's condition value for {@code status}.
     *
     * <p>Exhaustive over the api's enum with no default branch, for the same reason
     * {@link VanillaActivities} is: a value added to the api without a mapping here should be a compile
     * error in this module rather than a condition that silently never holds.
     *
     * @param status the api's condition
     * @return the server's condition
     */
    static @NotNull MemoryStatus of(@NotNull AiMemoryStatus status) {
        return switch (status) {
            case VALUE_PRESENT -> MemoryStatus.VALUE_PRESENT;
            case VALUE_ABSENT -> MemoryStatus.VALUE_ABSENT;
            case REGISTERED -> MemoryStatus.REGISTERED;
        };
    }
}
