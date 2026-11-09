package pandastic.studio.models.nms;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.ActivityData;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.sensing.SensorType;
import net.minecraft.world.entity.schedule.Activity;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.Mob;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.ai.AiActivity;
import pandastic.studio.models.api.ai.AiActivityPlan;
import pandastic.studio.models.api.ai.AiContext;
import pandastic.studio.models.api.ai.AiMemory;
import pandastic.studio.models.api.ai.AiPlan;
import pandastic.studio.models.internal.nms.BrainBridge;
import pandastic.studio.models.internal.nms.BrainInstallRequest;
import pandastic.studio.models.internal.nms.BrainInstallation;
import pandastic.studio.models.internal.nms.MemoryStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rebuilds a creature's brain around a plan, the way the server rebuilds one itself.
 *
 * <h2>The sequence, and which part of it is vanilla's</h2>
 * The server's own precedent is a villager changing profession: stop everything the brain is running,
 * read its state back out with {@code pack()}, build a new brain through a provider, and register the
 * new behaviour on it. There is no alternative — a brain exposes adding behaviour and clearing
 * <em>all</em> of it, and the list a single entry would have to come out of is private. So:
 *
 * <ol>
 *   <li><strong>Refuse what cannot work</strong> — the wrong thread, a dead creature, a species whose
 *       tick does not tick its brain. All three before anything is touched.</li>
 *   <li><strong>Resolve the plan's memories to slot keys.</strong> Before the rebuild, because a plan
 *       naming a memory this server does not have must fail with the creature still holding the brain
 *       it had rather than half-way through a replacement.</li>
 *   <li><strong>Stop the old brain</strong>, which fires the stop callback of everything it was
 *       running, including a previous plan's bodies.</li>
 *   <li><strong>Pack it</strong>, carrying the creature's memories forward.</li>
 *   <li><strong>Build the new brain</strong> from a provider of our own, seeded with the plan's memory
 *       slots and the plan's activities, and with <strong>no sensors</strong>.</li>
 *   <li><strong>Configure it</strong> — core activities, default activity, activate the default — while
 *       it is still ours alone.</li>
 *   <li><strong>Install it</strong>, and only then clear the goal selectors. Clearing is last because it
 *       is the one step that cannot be undone if a later one fails.</li>
 * </ol>
 *
 * <h2>Why a provider of ours and not the species' own</h2>
 * Vanilla's rebuild uses the species' static provider, which keeps its sensors and its memory set and
 * then has its activities registered on top. Using it here would leave the plan's activities competing
 * with the species' own for the same slots, and whichever filled a slot first would win — a modelled
 * villager would still be trying to find a job site. A provider of ours produces a brain containing the
 * plan and nothing else, which is what a plugin asking to program a creature means.
 *
 * <p>The price is that the species' own behaviour is gone until the plan is uninstalled. That is both
 * intended and recoverable: uninstalling goes back through the species' own brain factory, so the
 * creature returns to being itself.
 *
 * <h2>Why no sensors</h2>
 * A sensor is reached through a registry object with a private constructor, in a registry frozen before
 * any plugin loads. Constructing one reflectively would work, and would produce something that runs
 * inside the brain's tick with no way to remove it and no way to tune its rate. The plan's own sensors
 * run above this seam instead, on the creature's own scheduler, at a period an operator sets — which
 * also lets them report a scan the region truncated instead of silently absorbing it.
 *
 * <h2>The unchecked cast, and why it cannot be wrong</h2>
 * {@code Brain}'s type parameter is the species, so a {@code Brain<?>} read off a creature cannot be
 * narrowed without one. The cast here is to {@code Brain<LivingEntity>} and it is safe because the
 * parameter is erased at runtime and exists only to relate the brain's methods to the entity they are
 * called with — which is always the brain's own owner. A behaviour that narrows the entity it is handed
 * therefore narrows it correctly, because it genuinely is that species.
 *
 * <h2>Threading</h2>
 * Owning-thread only, and asserted rather than trusted. Registration is the one operation in this area
 * with no thread checking anywhere — the goal selector holds a plain set and the brain's maps are plain
 * maps — so a registration from a foreign region thread does not throw, it interleaves with the
 * creature's tick.
 */
public final class FoliaBrainBridge implements BrainBridge {

    /**
     * Package-private: a bridge is reached through {@link FoliaNmsBridgeProvider}, so the version check
     * in the lookup cannot be bypassed by constructing one directly.
     */
    FoliaBrainBridge() {
        // Nothing to set up. The reflection this adapter needs is resolved by BrainReflection's static
        // initialiser, on first use, which is during an install rather than during plugin enable.
    }

    @Override
    public boolean supports(@NotNull Mob mob) {
        return BrainSpecies.isBrainDriven(handleOf(mob));
    }

    @Override
    public @NotNull List<String> supportedSpecies() {
        return BrainSpecies.names();
    }

    @Override
    public @NotNull BrainInstallation install(@NotNull BrainInstallRequest request) {
        final Mob bukkitMob = request.mob();
        final AiPlan plan = request.plan();

        if (!Bukkit.isOwnedByCurrentRegion(bukkitMob)) {
            throw new IllegalStateException("Plan '" + plan.getName() + "' cannot be installed on "
                    + bukkitMob.getType() + " " + bukkitMob.getUniqueId()
                    + " from this thread: registering behaviour is not thread-checked anywhere, so a"
                    + " registration from a foreign region interleaves with the creature's own tick"
                    + " instead of failing");
        }

        final LivingEntity entity = handleOf(bukkitMob);
        if (!BrainSpecies.isBrainDriven(entity)) {
            throw new IllegalStateException("Plan '" + plan.getName() + "' cannot be installed on "
                    + entity.getClass().getSimpleName() + ": its tick does not tick its brain, so the"
                    + " install would succeed and the plan would never be asked anything. The species"
                    + " that work are: " + String.join(", ", BrainSpecies.names()));
        }
        if (!bukkitMob.isValid()) {
            throw new IllegalStateException("Plan '" + plan.getName() + "' cannot be installed on "
                    + entity.getUUID() + ": the creature is no longer valid");
        }
        if (!(entity.level() instanceof ServerLevel level)) {
            throw new IllegalStateException("Plan '" + plan.getName() + "' cannot be installed on "
                    + entity.getUUID() + ": its level is not a server level");
        }

        // Step 2. Before the creature is touched, so a plan naming a memory this server does not have
        // fails with the brain it already had.
        final Map<String, MemoryModuleType<?>> slots = resolveSlots(plan);

        // Steps 3 and 4. The cast is explained in the class documentation.
        @SuppressWarnings("unchecked")
        final Brain<LivingEntity> previous = (Brain<LivingEntity>) entity.getBrain();
        previous.stopAll(level, entity);
        final Brain.Packed packed = previous.pack();

        // Step 5. No sensors; see the class documentation. The activity supplier is called once, from
        // inside makeBrain, which is exactly where the per-creature behaviour adapters belong.
        //
        // The three-argument provider is deprecated in favour of the forms that pass no memory
        // collection at all and let the brain derive one, and it is still the only correct choice here.
        // What the brain derives is the union of each sensor's requirements — we have no sensors — and
        // each behaviour's getRequiredMemories(). A behaviour's *required* memories are also its
        // preconditions: the brain refuses to start it while any of them is empty. So deriving the set
        // would mean a plan could only register a memory by making it a precondition of some behaviour,
        // and a plan's memories are mostly things it writes rather than things it waits for — a
        // cooldown a behaviour sets would gate the behaviour that sets it.
        //
        // Passing the set explicitly is therefore the only way to give the brain exactly the plan's
        // memories, which is a correctness requirement rather than a preference: a write to a slot the
        // brain was not built with is accepted and discarded, and a read of one throws. Note that
        // addActivity registers required memories only — not an activity's conditions and not its
        // erase-on-stop set — so the derived form would leave both of those unregistered too.
        @SuppressWarnings("deprecation")
        final Brain.Provider<LivingEntity> provider = Brain.provider(
                slots.values(),
                List.<SensorType<? extends Sensor<? super LivingEntity>>>of(),
                owner -> activities(plan, slots, request, owner));
        final Brain<LivingEntity> fresh = provider.makeBrain(entity, packed);

        // Step 6, while the brain is still unreachable from the creature. useDefaultActivity() needs the
        // default to have been set, and both need the activities to exist — which they do, because the
        // provider built them.
        fresh.setCoreActivities(coreActivities(plan));
        fresh.setDefaultActivity(VanillaActivities.of(plan.getDefaultActivity()));
        fresh.useDefaultActivity();

        // Step 7. The brain first, then the goals: clearing the selectors is the only irreversible part
        // of an install, so nothing that can still fail happens after it.
        BrainReflection.setBrain(entity, fresh);
        if (plan.clearsVanillaGoals()) {
            clearGoals(entity);
        }

        return new FoliaBrainInstallation(entity, plan.getName(), plan.clearsVanillaGoals(), slots,
                fresh);
    }

    /**
     * Every memory the plan declares, resolved to a server slot key, in the plan's own order.
     *
     * <p>A {@link LinkedHashMap} so two installs of one plan seed the brain's memory map identically.
     * Nothing depends on that today; it costs nothing and makes two brains built from one plan
     * comparable when something eventually does.
     */
    private static @NotNull Map<String, MemoryModuleType<?>> resolveSlots(@NotNull AiPlan plan) {
        final Map<String, MemoryModuleType<?>> slots = new LinkedHashMap<>();
        for (final AiMemory<?> memory : plan.getMemories()) {
            slots.put(memory.getKey(), MemoryTypes.of(memory));
        }
        return slots;
    }

    /**
     * The plan's activities, as the server's own activity records.
     *
     * <p>Called by the provider once per brain, which is once per installation — and that is exactly
     * where the behaviour adapters have to be created, because each one holds its own running state and
     * must not be shared between two creatures.
     *
     * <p>The context is built here too, through the request's factory, and shared by every behaviour of
     * this creature: it is immutable and every field in it is per-creature, so one is correct and a
     * second would only be a second allocation. The store it is built around reads the brain off the
     * creature at the moment of use, which is what lets it exist before the brain it will read does; see
     * {@link BrainMemoryStore}.
     *
     * @param owner the creature the provider is building a brain for, which is the entity the store has
     *              to read its brain from. Taken from the supplier's own argument rather than closed
     *              over, because the 26.3 provider is entity-aware precisely so that a brain's contents
     *              can depend on the creature getting it
     */
    private static @NotNull List<ActivityData<LivingEntity>> activities(
            @NotNull AiPlan plan,
            @NotNull Map<String, MemoryModuleType<?>> slots,
            @NotNull BrainInstallRequest request,
            @NotNull LivingEntity owner
    ) {
        // Ungated: at this point there is no installation to have been torn down, and the installation
        // built afterwards holds its own gated store for everything that happens later.
        final MemoryStore store = new BrainMemoryStore(owner, slots, () -> true);
        final AiContext context = request.contexts().create(store);

        final List<ActivityData<LivingEntity>> built = new ArrayList<>(plan.getActivities().size());
        for (final AiActivityPlan activity : plan.getActivities()) {
            final List<Pair<Integer, ? extends BehaviorControl<? super LivingEntity>>> entries =
                    new ArrayList<>(activity.getBehaviours().size());
            for (final AiActivityPlan.PrioritisedBehaviour entry : activity.getBehaviours()) {
                entries.add(Pair.of(entry.priority(), new AiBehaviourControl(
                        entry.behaviour(), context, request.faults(),
                        slotsFor(entry.behaviour().requiredMemories(), slots))));
            }
            built.add(new ActivityData<>(
                    VanillaActivities.of(activity.getActivity()),
                    ImmutableList.copyOf(entries),
                    conditions(activity, slots),
                    slotsFor(activity.getMemoriesToEraseOnStop(), slots)));
        }
        return built;
    }

    /**
     * The slot keys for a set of plan memories.
     *
     * <p>A memory with no slot is skipped rather than failing, and cannot occur: the plan derives its
     * memory set as the union of everything its parts mention, so every memory reachable from a
     * behaviour or an activity is in {@code slots}. The check is there so that a future fourth place a
     * memory can appear produces a brain missing one entry rather than an exception out of the provider.
     */
    private static @NotNull Set<MemoryModuleType<?>> slotsFor(
            @NotNull Set<AiMemory<?>> memories,
            @NotNull Map<String, MemoryModuleType<?>> slots
    ) {
        final Set<MemoryModuleType<?>> resolved = new LinkedHashSet<>();
        for (final AiMemory<?> memory : memories) {
            final MemoryModuleType<?> slot = slots.get(memory.getKey());
            if (slot != null) {
                resolved.add(slot);
            }
        }
        return resolved;
    }

    /** An activity's entry conditions, as the server's pairs. */
    private static @NotNull Set<Pair<MemoryModuleType<?>, MemoryStatus>> conditions(
            @NotNull AiActivityPlan activity,
            @NotNull Map<String, MemoryModuleType<?>> slots
    ) {
        final Set<Pair<MemoryModuleType<?>, MemoryStatus>> resolved = new LinkedHashSet<>();
        for (final AiActivityPlan.Condition condition : activity.getConditions()) {
            final MemoryModuleType<?> slot = slots.get(condition.memory().getKey());
            if (slot != null) {
                resolved.add(Pair.of(slot, MemoryTypes.of(condition.status())));
            }
        }
        return resolved;
    }

    /** The plan's core activities, as the server's constants. */
    private static @NotNull Set<Activity> coreActivities(@NotNull AiPlan plan) {
        final Set<Activity> core = new LinkedHashSet<>();
        for (final AiActivity activity : plan.getCoreActivities()) {
            core.add(VanillaActivities.of(activity));
        }
        return core;
    }

    /**
     * Empties both goal selectors.
     *
     * <p>Both, because the target selector is a second, independent selector and a creature that still
     * has goals in it keeps acquiring targets the plan knows nothing about. The predicate accepts
     * everything: there is no finer removal, and nothing here is in a position to judge which of a
     * species' goals would have been compatible with a plan it cannot read.
     *
     * <p>Irreversible. Goals are registered in the creature's constructor and nothing can re-run that,
     * which is why the plan gets to decide whether this happens at all.
     */
    private static void clearGoals(@NotNull LivingEntity entity) {
        if (!(entity instanceof net.minecraft.world.entity.Mob mob)) {
            // Every brain-driven species is a Mob, so this is unreachable. Checked rather than cast
            // because an unchecked cast here would be the one place this file assumed something it had
            // not verified, on the one path that cannot be undone.
            return;
        }
        mob.getGoalSelector().removeAllGoals(goal -> true);
        mob.targetSelector.removeAllGoals(goal -> true);
    }

    /**
     * The server-side entity behind a platform one.
     *
     * <p>Every brain-driven species is a living entity, so the narrowing cannot fail for anything this
     * bridge accepts. A creature that is somehow not a living entity is refused here rather than later,
     * because the alternative is a {@link ClassCastException} from the middle of an install.
     */
    private static @NotNull LivingEntity handleOf(@NotNull Mob mob) {
        final net.minecraft.world.entity.Entity handle = ((CraftEntity) mob).getHandle();
        if (handle instanceof LivingEntity living) {
            return living;
        }
        throw new IllegalStateException(
                "A " + mob.getType() + " is not a living entity, so it has no brain to replace");
    }
}
