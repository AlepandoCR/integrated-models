package pandastic.studio.models.nms;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.internal.nms.BrainInstallation;
import pandastic.studio.models.internal.nms.MemoryStore;

import java.util.Map;
import java.util.Objects;

/**
 * A rebuilt brain while it is installed, and the restoration when it is not.
 *
 * <h2>Uninstalling, in the order the server requires</h2>
 * <ol>
 *   <li><strong>Read the memories out</strong> with {@code pack()}, before anything is stopped. A
 *       behaviour's stop callback is entitled to erase the memory it was using, and the restored brain
 *       should get the state the creature had rather than the state its last behaviour tidied up
 *       into.</li>
 *   <li><strong>Stop everything the brain is running.</strong> Every running behaviour's stop callback
 *       fires, which is the only notification a plan's bodies get that they are finished.</li>
 *   <li><strong>Build the species' own brain</strong> around the packed memories, through the protected
 *       factory the species overrides. This is what makes an uninstall a restoration rather than a
 *       replacement with something generic: a villager gets a villager's brain back.</li>
 *   <li><strong>Install it.</strong> Last, so nothing observes a creature with no brain.</li>
 * </ol>
 *
 * <p>What is not restored is the goal selectors, when the plan cleared them. Goals are registered in the
 * creature's constructor and nothing can re-run that. The flag is carried so that {@link #describe()}
 * says so, rather than letting an operator assume the creature went back to normal.
 *
 * <h2>Threading</h2>
 * Owning-thread only, and {@link #uninstall()} asserts it rather than trusting the caller: replacing the
 * brain field from a foreign region thread interleaves with the creature's tick and the platform has no
 * mode that would catch it.
 */
final class FoliaBrainInstallation implements BrainInstallation {

    private final @NotNull LivingEntity entity;
    private final @NotNull String planName;
    private final boolean clearedGoals;
    private final @NotNull MemoryStore store;

    /**
     * Nulled by {@link #uninstall()}, which makes it both the installed flag and the idempotence latch.
     *
     * <p>Volatile because {@link #isInstalled()} is documented as safe from anywhere — the controller
     * above the seam reports installation state to callers on arbitrary threads — while the write
     * happens on the creature's own thread.
     */
    private volatile @Nullable Brain<LivingEntity> brain;

    /**
     * @param entity       the creature
     * @param planName     for diagnostics only
     * @param clearedGoals whether installing emptied the goal selectors, which cannot be undone
     * @param slots        the plan's memories, resolved to server slot keys, by api key
     * @param brain        the brain that was installed
     */
    FoliaBrainInstallation(
            @NotNull LivingEntity entity,
            @NotNull String planName,
            boolean clearedGoals,
            @NotNull Map<String, MemoryModuleType<?>> slots,
            @NotNull Brain<LivingEntity> brain
    ) {
        this.entity = Objects.requireNonNull(entity, "entity");
        this.planName = Objects.requireNonNull(planName, "planName");
        this.clearedGoals = clearedGoals;
        this.brain = Objects.requireNonNull(brain, "brain");
        // Gated on this installation's own latch, so a sensor body that was in flight when the plan was
        // uninstalled writes nowhere instead of writing into the restored vanilla brain.
        this.store = new BrainMemoryStore(entity, slots, this::isInstalled);
    }

    @Override
    public @NotNull MemoryStore memories() {
        return this.store;
    }

    @Override
    public boolean isInstalled() {
        return this.brain != null;
    }

    @Override
    public void uninstall() {
        final Brain<LivingEntity> installed = this.brain;
        if (installed == null) {
            return;
        }
        if (!Bukkit.isOwnedByCurrentRegion(this.entity.getBukkitEntity())) {
            throw new IllegalStateException("Plan '" + this.planName + "' cannot be uninstalled from "
                    + describe() + " on this thread: replacing a creature's brain interleaves with its"
                    + " own tick, and the platform has no mode that would catch it");
        }
        if (!(this.entity.level() instanceof ServerLevel level)) {
            // Unreachable on a server, and asserted rather than assumed: stopping a brain needs the
            // server-side level, and a level that is not one would be a different problem entirely.
            throw new IllegalStateException("Plan '" + this.planName + "' cannot be uninstalled from "
                    + describe() + ": its level is not a server level");
        }

        // The latch, set before the work, so a failure part-way through does not invite a second attempt
        // on a creature whose brain has already been stopped.
        this.brain = null;

        // Packed before stopAll, so the restored brain gets what the creature knew rather than what its
        // last behaviour tidied up into on the way out.
        final Brain.Packed packed = installed.pack();
        installed.stopAll(level, this.entity);
        BrainReflection.setBrain(this.entity, BrainReflection.makeSpeciesBrain(this.entity, packed));
    }

    @Override
    public @NotNull String describe() {
        return this.entity.getClass().getSimpleName() + " " + this.entity.getUUID()
                + " under plan '" + this.planName + "'"
                + (this.clearedGoals ? " (goals were cleared and cannot be restored)" : "");
    }

    @Override
    public @NotNull String toString() {
        return "FoliaBrainInstallation[" + describe() + ", installed=" + isInstalled() + "]";
    }
}
