package pandastic.studio.models.api.ai;

import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Something a creature periodically notices, written into memory for behaviours to read.
 *
 * <h2>Why this is ours and not the server's sensor mechanism</h2>
 * The server has a sensor system and it is closed. A sensor is reached through a registry object whose
 * constructor is private, the registry is frozen before any plugin loads, and the brain's sensor map is
 * built from that collection and not exposed afterwards. There is no supported path to adding one, and
 * the unsupported paths all end with a sensor that runs inside the brain's tick with no way to remove it
 * — which is the same trap the brain's behaviour list has, one layer down.
 *
 * <p>So sensing here is a task of our own, dispatched through the creature's own scheduler at a period
 * the plan chooses, writing the memories it declares. That costs one scheduled task per sensor per
 * creature and buys three things the server's mechanism cannot give: a period an operator can tune, a
 * sensor that can be removed when the plan is uninstalled, and a scan whose region truncation is
 * reported rather than silently absorbed.
 *
 * <h2>The division of labour, and why it is worth keeping</h2>
 * A sensor <em>observes</em> and a behaviour <em>decides</em>. The reason to keep them apart is cost:
 * looking around is the expensive operation in this layer, a behaviour's {@link AiBehaviour#canStart}
 * runs every tick, and a sensor runs every few ticks. Five behaviours that each scan for players do
 * five scans a tick; one sensor that writes "nearest player" does one scan every tenth tick and the
 * five behaviours read a memory.
 *
 * <p>It also puts the stale-data question in one place. A memory written by a sensor is by definition
 * up to a period old, so give it an expiry and let it lapse — a behaviour reading an absent memory
 * knows it has not been told recently, while a behaviour reading a memory with no expiry cannot tell
 * the difference between "seen just now" and "seen before the player logged out".
 *
 * <h2>Threading</h2>
 * {@link #sense} runs on the thread that owns the creature, from a task dispatched through the
 * creature's own scheduler — which follows it across regions and worlds, unlike a region-scheduled task
 * pinned to wherever it was posted from. The {@link AiContext} is valid for the duration of the call
 * and no longer.
 */
public interface AiSensor {

    /**
     * A short name for diagnostics.
     *
     * @return the name, non-blank
     */
    @NotNull String name();

    /**
     * Memories this sensor writes.
     *
     * <p>Every one is automatically part of the plan, which is what makes the writes take effect: a
     * memory the brain was not built with accepts a write and discards it. Declaring a memory here and
     * never writing it is harmless; writing one that is not declared anywhere is the failure this
     * method exists to make impossible.
     *
     * @return the memories written; immutable, and the same set every call
     */
    default @NotNull Set<AiMemory<?>> writtenMemories() {
        return Set.of();
    }

    /**
     * Looks, and writes what was found.
     *
     * <p>Called every {@link AiSensorBinding#periodTicks()} while the plan is installed and the creature
     * exists. Never concurrently with itself, and never concurrently with a behaviour of the same
     * creature.
     *
     * @param context the creature and what it can see, valid only for this call
     */
    void sense(@NotNull AiContext context);
}
