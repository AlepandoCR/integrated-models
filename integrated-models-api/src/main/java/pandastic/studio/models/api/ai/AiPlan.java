package pandastic.studio.models.api.ai;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A whole creature's behaviour, validated, immutable, and installable on any number of creatures.
 *
 * <h2>Why a plan rather than a sequence of calls on a live creature</h2>
 * Because the server will not let us take one behaviour away again. A brain exposes adding and it
 * exposes clearing <em>everything</em>; there is no removal of a single entry, and the list it would
 * have to be removed from is private. So the only sanctioned way to change what a creature does is to
 * rebuild its brain from scratch — which is what vanilla itself does when a villager changes
 * profession.
 *
 * <p>Once rebuilding is the mechanism, a plan is the natural unit: the installer needs the complete
 * picture before it can build anything, so there is no point in an API that accepts it a piece at a
 * time and then has to guess when the caller has finished. The second benefit falls out of the first —
 * everything is checked while nothing has been touched yet, so a plan that is wrong is a message rather
 * than a creature left half-reprogrammed.
 *
 * <h2>Memories are derived, not declared twice</h2>
 * A write to a memory the brain was not built with is accepted and discarded; a read of one throws. So
 * the set of memories the brain is built with has to be exactly the set the plan can touch, and asking
 * the caller to list them separately would be asking them to keep two lists in step. Instead the plan
 * unions what its behaviours require and use, what its conditions look at, and what its activities
 * erase. {@link Builder#declareMemory(AiMemory)} exists for the remainder: a memory read or written
 * inside a body and mentioned nowhere else.
 *
 * <h2>The checks, and what each one is protecting against</h2>
 * Every one of these is a failure that is silent at runtime, which is the only reason to spend a check
 * on it:
 * <ul>
 *   <li><strong>Two memories with one key.</strong> The server keys a slot by identifier, so a plan
 *       declaring {@code cooldown} as a {@code Long} in one behaviour and an {@code Integer} in another
 *       gets one slot and a {@link ClassCastException} from whichever reads second.</li>
 *   <li><strong>A default activity that is also core.</strong> Core activities run alongside whatever
 *       is active; the default is what runs when nothing else qualifies. An activity in both is asked
 *       to be a fallback for itself, and the brain's own bookkeeping erases the other activities'
 *       memories when it switches — including the ones it is about to need.</li>
 *   <li><strong>A core or default activity with no slot.</strong> Naming a slot the plan never filled
 *       leaves the creature pointed at an activity with no behaviours in it, which looks exactly like a
 *       creature whose conditions are never met.</li>
 *   <li><strong>Every configured bound.</strong> See {@link AiBounds} for why they exist at all.</li>
 * </ul>
 *
 * <h2>Thread-safe because it is finished</h2>
 * A built plan has no mutable state, so it may be held in a static field, installed from any thread,
 * and shared between creatures. The things that are per-creature — memories, intents, which behaviour
 * is running — belong to the {@link AiController} that an install returns.
 */
public final class AiPlan {

    private final @NotNull String name;
    private final @NotNull List<AiActivityPlan> activities;
    private final @NotNull Set<AiActivity> coreActivities;
    private final @NotNull AiActivity defaultActivity;
    private final @NotNull List<AiSensorBinding> sensors;
    private final @NotNull Set<AiMemory<?>> memories;
    private final boolean clearsVanillaGoals;

    private AiPlan(
            @NotNull String name,
            @NotNull List<AiActivityPlan> activities,
            @NotNull Set<AiActivity> coreActivities,
            @NotNull AiActivity defaultActivity,
            @NotNull List<AiSensorBinding> sensors,
            @NotNull Set<AiMemory<?>> memories,
            boolean clearsVanillaGoals
    ) {
        this.name = name;
        this.activities = activities;
        this.coreActivities = coreActivities;
        this.defaultActivity = defaultActivity;
        this.sensors = sensors;
        this.memories = memories;
        this.clearsVanillaGoals = clearsVanillaGoals;
    }

    /**
     * A builder validating against {@code bounds}.
     *
     * <p>The bounds are taken at the start rather than at {@code build()} so that a builder cannot be
     * handed around and then validated against a different set than the one its caller was reading.
     * Take them from {@link CreatureAi#getBounds()}, which reads the live configuration.
     *
     * @param name   what to call this plan in diagnostics; the only thing a validation message can say
     *               about it
     * @param bounds the operator's limits
     * @return the builder
     */
    @Contract(value = "_, _ -> new", pure = true)
    public static @NotNull Builder builder(@NotNull String name, @NotNull AiBounds bounds) {
        return new Builder(name, bounds);
    }

    /** What this plan is called, for diagnostics. */
    public @NotNull String getName() {
        return this.name;
    }

    /** The filled activity slots, in declaration order. Immutable. */
    public @NotNull List<AiActivityPlan> getActivities() {
        return this.activities;
    }

    /** Slots that run alongside whatever else is active. Immutable, possibly empty. */
    public @NotNull Set<AiActivity> getCoreActivities() {
        return this.coreActivities;
    }

    /** The slot the creature falls back to when no other one's conditions are met. */
    public @NotNull AiActivity getDefaultActivity() {
        return this.defaultActivity;
    }

    /** The sensors, in declaration order. Immutable, possibly empty. */
    public @NotNull List<AiSensorBinding> getSensors() {
        return this.sensors;
    }

    /**
     * Every memory this creature's brain has to be built with.
     *
     * <p>The union described in the class documentation, in a stable order so that two installs of one
     * plan build the same brain.
     *
     * @return the memories; immutable
     */
    public @NotNull Set<AiMemory<?>> getMemories() {
        return this.memories;
    }

    /**
     * Whether installing this plan empties the creature's vanilla goal selectors.
     *
     * <h2>Why this is a choice and not always done</h2>
     * A goal and a brain are two independent drivers competing for one creature, so leaving vanilla
     * goals in place means whatever the plan decides is fought every tick by something that does not
     * know the plan exists. That argues for always clearing.
     *
     * <p>Against it: clearing is <strong>not reversible</strong>. Goals are registered in the
     * creature's constructor and there is no supported way to re-run that, so uninstalling a plan
     * restores the brain and cannot restore the goals. A creature whose plan has been uninstalled is
     * therefore permanently goal-less, which is right for a creature that exists to carry a model and
     * wrong for a villager somebody wanted back.
     *
     * <p>Default on, because the first case is what this layer is for and a creature fighting itself
     * is the harder failure to recognise.
     *
     * @return whether to clear the goal selectors at install
     */
    public boolean clearsVanillaGoals() {
        return this.clearsVanillaGoals;
    }

    /**
     * The slot for {@code activity}, or {@code null}.
     *
     * @param activity the slot to look up
     * @return the filled slot, or {@code null} if this plan does not fill it
     */
    public @Nullable AiActivityPlan activity(@NotNull AiActivity activity) {
        for (final AiActivityPlan filled : this.activities) {
            if (filled.getActivity() == activity) {
                return filled;
            }
        }
        return null;
    }

    /**
     * Re-checks this plan against {@code bounds}, which may not be the ones it was built with.
     *
     * <p>Needed because a plan is built once and may be installed much later — after a reload that
     * tightened something. Without this, a plan built against yesterday's configuration would keep
     * installing against limits the operator has since removed, and the only way to find out would be
     * the server getting slower.
     *
     * <p>Deliberately numeric only. The structural checks the builder makes — a default activity that is
     * also core, two memories sharing a key, an activity with no behaviours — are properties of the plan
     * rather than of the bounds, so they cannot have changed and re-running them would be work that can
     * never fail.
     *
     * @param bounds the limits to check against
     * @throws IllegalArgumentException if the plan exceeds any of them, with a message naming the key in
     *                                 {@code ai.yml} to change
     */
    public void requireWithin(@NotNull AiBounds bounds) {
        Objects.requireNonNull(bounds, "bounds");
        if (this.activities.size() > bounds.maxActivities()) {
            throw new IllegalArgumentException(refusal("fills " + this.activities.size()
                    + " activity slots", "maxActivities", bounds.maxActivities()));
        }
        if (this.sensors.size() > bounds.maxSensors()) {
            throw new IllegalArgumentException(refusal("has " + this.sensors.size() + " sensors",
                    "maxSensors", bounds.maxSensors()));
        }
        if (this.memories.size() > bounds.maxMemoriesPerPlan()) {
            throw new IllegalArgumentException(refusal("needs " + this.memories.size()
                    + " memory slots", "maxMemoriesPerPlan", bounds.maxMemoriesPerPlan()));
        }
        for (final AiActivityPlan activity : this.activities) {
            final int behaviours = activity.getBehaviours().size();
            if (behaviours > bounds.maxBehavioursPerActivity()) {
                throw new IllegalArgumentException(refusal("gives the " + activity.getActivity()
                                + " activity " + behaviours + " behaviours",
                        "maxBehavioursPerActivity", bounds.maxBehavioursPerActivity()));
            }
            for (final AiActivityPlan.PrioritisedBehaviour entry : activity.getBehaviours()) {
                if (entry.priority() > bounds.maxPriority()) {
                    throw new IllegalArgumentException(refusal("gives behaviour '"
                                    + entry.behaviour().name() + "' priority " + entry.priority(),
                            "maxPriority", bounds.maxPriority()));
                }
            }
        }
        for (final AiSensorBinding binding : this.sensors) {
            if (binding.periodTicks() < bounds.minSensorPeriodTicks()
                    || binding.periodTicks() > bounds.maxSensorPeriodTicks()) {
                throw new IllegalArgumentException("Plan '" + this.name + "' runs sensor '"
                        + binding.sensor().name() + "' every " + binding.periodTicks()
                        + " ticks, outside the configured range " + bounds.minSensorPeriodTicks() + ".."
                        + bounds.maxSensorPeriodTicks() + " in ai.yml");
            }
        }
        for (final AiMemory<?> memory : this.memories) {
            if (memory.getDefaultExpiryTicks() > bounds.maxMemoryExpiryTicks()) {
                throw new IllegalArgumentException(refusal("remembers '" + memory.getKey() + "' for "
                                + memory.getDefaultExpiryTicks() + " ticks",
                        "maxMemoryExpiryTicks", bounds.maxMemoryExpiryTicks()));
            }
        }
    }

    /** One sentence naming the plan, what it asked for, and the key that refused it. */
    private @NotNull String refusal(@NotNull String asked, @NotNull String key, long limit) {
        return "Plan '" + this.name + "' " + asked + ", above the configured " + key + " of " + limit
                + " in ai.yml";
    }

    /**
     * Whether this plan declares {@code memory}.
     *
     * <p>What an {@link AiMemoryView} checks before every read and write, which is what turns the
     * server's two silent memory failures into one exception naming the plan.
     *
     * @param memory the memory to look for
     * @return whether it is part of this plan
     */
    public boolean declares(@NotNull AiMemory<?> memory) {
        return this.memories.contains(memory);
    }

    @Override
    public @NotNull String toString() {
        return "AiPlan[" + this.name + ", " + this.activities.size() + " activity slot(s), "
                + this.sensors.size() + " sensor(s), " + this.memories.size() + " memory slot(s)]";
    }

    /**
     * Collects a plan and refuses to produce one that would fail silently.
     *
     * <p>Not thread-safe, and does not need to be: a builder belongs to the one method assembling a
     * plan. What comes out of it is immutable and shareable.
     */
    public static final class Builder {

        private final @NotNull String name;
        private final @NotNull AiBounds bounds;

        /** Keyed by slot, so a second fill of one slot is caught where it happens. */
        private final @NotNull Map<AiActivity, AiActivityPlan> activities = new EnumMap<>(AiActivity.class);

        private final @NotNull Set<AiActivity> coreActivities = new LinkedHashSet<>();
        private final @NotNull List<AiSensorBinding> sensors = new ArrayList<>();

        /**
         * A list rather than a set, for the same reason
         * {@link AiActivityPlan#referencedMemories()} returns one: two memories with one key are equal,
         * so a set would discard the second and make a key collision undetectable.
         */
        private final @NotNull List<AiMemory<?>> declaredMemories = new ArrayList<>();
        private @Nullable AiActivity defaultActivity;
        private boolean clearsVanillaGoals = true;

        private Builder(@NotNull String name, @NotNull AiBounds bounds) {
            this.name = Objects.requireNonNull(name, "name");
            this.bounds = Objects.requireNonNull(bounds, "bounds");
            if (name.isBlank()) {
                throw new IllegalArgumentException("A plan needs a name: it is the only thing a"
                        + " validation message or an install failure can say about it");
            }
        }

        /**
         * Fills one activity slot.
         *
         * @param activity the slot
         * @return this builder
         * @throws IllegalArgumentException if the slot is already filled, or the slot holds more
         *                                 behaviours than configuration permits
         */
        @Contract("_ -> this")
        public @NotNull Builder activity(@NotNull AiActivityPlan activity) {
            Objects.requireNonNull(activity, "activity");
            final AiActivityPlan previous = this.activities.get(activity.getActivity());
            if (previous != null) {
                throw new IllegalArgumentException("Plan '" + this.name + "' already fills the "
                        + activity.getActivity() + " activity. There is one slot per activity, so the"
                        + " second fill would replace the first rather than adding to it");
            }
            final int behaviours = activity.getBehaviours().size();
            if (behaviours > this.bounds.maxBehavioursPerActivity()) {
                throw new IllegalArgumentException("Plan '" + this.name + "' gives the "
                        + activity.getActivity() + " activity " + behaviours
                        + " behaviours, above the configured maxBehavioursPerActivity of "
                        + this.bounds.maxBehavioursPerActivity() + " in ai.yml. Every behaviour in an"
                        + " active slot is a start test per creature per tick");
            }
            for (final AiActivityPlan.PrioritisedBehaviour entry : activity.getBehaviours()) {
                if (entry.priority() > this.bounds.maxPriority()) {
                    throw new IllegalArgumentException("Behaviour '" + entry.behaviour().name()
                            + "' in the " + activity.getActivity() + " activity of plan '" + this.name
                            + "' claims priority " + entry.priority()
                            + ", above the configured maxPriority of " + this.bounds.maxPriority()
                            + " in ai.yml");
                }
            }
            this.activities.put(activity.getActivity(), activity);
            return this;
        }

        /**
         * Declares {@code activity} as one that runs alongside whatever else is active.
         *
         * <p>Conventionally {@link AiActivity#CORE} and conventionally exactly one, but neither is
         * enforced: a plan whose creature should always be watching for danger and always be breathing
         * has two reasonable core slots.
         *
         * @param activity the slot to make core
         * @return this builder
         */
        @Contract("_ -> this")
        public @NotNull Builder coreActivity(@NotNull AiActivity activity) {
            this.coreActivities.add(Objects.requireNonNull(activity, "activity"));
            return this;
        }

        /**
         * Sets the slot the creature falls back to.
         *
         * <p>Required: a brain with no default activity has no state to be in when nothing qualifies,
         * and the symptom is a creature that stops moving the first time a condition lapses. Vanilla
         * mobs set {@link AiActivity#IDLE} and so should most plans.
         *
         * @param activity the fallback slot, which must be filled and must not also be core
         * @return this builder
         */
        @Contract("_ -> this")
        public @NotNull Builder defaultActivity(@NotNull AiActivity activity) {
            this.defaultActivity = Objects.requireNonNull(activity, "activity");
            return this;
        }

        /**
         * Runs {@code sensor} every {@code periodTicks}.
         *
         * @param sensor      what to run
         * @param periodTicks ticks between runs; bounded by configuration
         * @return this builder
         * @throws IllegalArgumentException if the period is outside the configured range, or the plan
         *                                 already has as many sensors as configuration permits
         */
        @Contract("_, _ -> this")
        public @NotNull Builder sensor(@NotNull AiSensor sensor, long periodTicks) {
            Objects.requireNonNull(sensor, "sensor");
            if (sensor.name().isBlank()) {
                throw new IllegalArgumentException("A sensor in plan '" + this.name
                        + "' has a blank name, which leaves a failure report with nothing to say");
            }
            if (this.sensors.size() >= this.bounds.maxSensors()) {
                throw new IllegalArgumentException("Plan '" + this.name + "' already has "
                        + this.sensors.size() + " sensors, the configured maxSensors in ai.yml."
                        + " Each one is a retained scheduled task per creature");
            }
            if (periodTicks < this.bounds.minSensorPeriodTicks()
                    || periodTicks > this.bounds.maxSensorPeriodTicks()) {
                throw new IllegalArgumentException("Sensor '" + sensor.name() + "' in plan '"
                        + this.name + "' asks for a period of " + periodTicks
                        + " ticks, outside the configured range " + this.bounds.minSensorPeriodTicks()
                        + ".." + this.bounds.maxSensorPeriodTicks() + " in ai.yml");
            }
            this.sensors.add(new AiSensorBinding(sensor, periodTicks));
            return this;
        }

        /**
         * Declares a memory that nothing else in the plan mentions.
         *
         * <p>For a memory a body reads or writes without declaring it in
         * {@link AiBehaviour#usedMemories()}. Harmless to call for one that is already covered.
         *
         * @param memory the memory to include in the creature's brain
         * @return this builder
         */
        @Contract("_ -> this")
        public @NotNull Builder declareMemory(@NotNull AiMemory<?> memory) {
            this.declaredMemories.add(Objects.requireNonNull(memory, "memory"));
            return this;
        }

        /**
         * Whether installing empties the creature's vanilla goal selectors.
         *
         * @param clear {@code true} to clear them; see {@link AiPlan#clearsVanillaGoals()} for why this
         *              is irreversible
         * @return this builder
         */
        @Contract("_ -> this")
        public @NotNull Builder clearVanillaGoals(boolean clear) {
            this.clearsVanillaGoals = clear;
            return this;
        }

        /**
         * Validates everything and builds the plan.
         *
         * @return the immutable plan
         * @throws IllegalArgumentException with a message naming the plan, the part at fault and, for a
         *                                 bound, the key in {@code ai.yml} to change
         */
        @Contract(value = "-> new", pure = true)
        public @NotNull AiPlan build() {
            if (this.activities.isEmpty()) {
                throw new IllegalArgumentException("Plan '" + this.name + "' fills no activity slots,"
                        + " so the creature would have a brain that does nothing. Add at least one"
                        + " activity and make it the default");
            }
            if (this.activities.size() > this.bounds.maxActivities()) {
                throw new IllegalArgumentException("Plan '" + this.name + "' fills "
                        + this.activities.size() + " activity slots, above the configured maxActivities"
                        + " of " + this.bounds.maxActivities() + " in ai.yml");
            }

            final AiActivity fallback = this.defaultActivity;
            if (fallback == null) {
                throw new IllegalArgumentException("Plan '" + this.name + "' has no default activity."
                        + " A brain with no fallback has no state to be in when no slot's conditions"
                        + " hold, and the creature simply stops");
            }
            if (!this.activities.containsKey(fallback)) {
                throw new IllegalArgumentException("Plan '" + this.name + "' names " + fallback
                        + " as its default activity but never fills that slot, which leaves the"
                        + " creature pointed at an activity with no behaviours in it");
            }
            if (this.coreActivities.contains(fallback)) {
                throw new IllegalArgumentException("Plan '" + this.name + "' makes " + fallback
                        + " both core and default. A core activity runs alongside whatever is active"
                        + " and the default is what runs when nothing else qualifies, so an activity"
                        + " in both is being asked to be its own fallback");
            }
            for (final AiActivity core : this.coreActivities) {
                if (!this.activities.containsKey(core)) {
                    throw new IllegalArgumentException("Plan '" + this.name + "' declares " + core
                            + " as a core activity but never fills that slot");
                }
            }

            final Set<AiMemory<?>> memories = unionMemories();
            if (memories.size() > this.bounds.maxMemoriesPerPlan()) {
                throw new IllegalArgumentException("Plan '" + this.name + "' needs " + memories.size()
                        + " memory slots, above the configured maxMemoriesPerPlan of "
                        + this.bounds.maxMemoriesPerPlan() + " in ai.yml. Each one is a slot in every"
                        + " creature's brain");
            }

            return new AiPlan(this.name, List.copyOf(this.activities.values()),
                    Set.copyOf(this.coreActivities), fallback, List.copyOf(this.sensors),
                    Set.copyOf(memories), this.clearsVanillaGoals);
        }

        /**
         * Gathers every memory the plan mentions and refuses two spellings of one key.
         *
         * <p>The key collision is checked here rather than in {@link AiMemory} because it is a property
         * of a set of memories and not of one: {@code AiMemory} equality is by key precisely so that the
         * server's one-slot-per-key rule is reflected, which means a {@link Set} silently keeps
         * whichever of the two it saw first. Catching it needs a map from key to the memory that claimed
         * it.
         */
        private @NotNull Set<AiMemory<?>> unionMemories() {
            final Map<String, AiMemory<?>> byKey = new LinkedHashMap<>();
            final List<AiMemory<?>> mentioned = new ArrayList<>(this.declaredMemories);
            for (final AiActivityPlan activity : this.activities.values()) {
                mentioned.addAll(activity.referencedMemories());
            }
            for (final AiSensorBinding binding : this.sensors) {
                mentioned.addAll(binding.sensor().writtenMemories());
            }

            for (final AiMemory<?> memory : mentioned) {
                if (memory.getDefaultExpiryTicks() > this.bounds.maxMemoryExpiryTicks()) {
                    throw new IllegalArgumentException("Memory '" + memory.getKey() + "' in plan '"
                            + this.name + "' expires after " + memory.getDefaultExpiryTicks()
                            + " ticks, above the configured maxMemoryExpiryTicks of "
                            + this.bounds.maxMemoryExpiryTicks() + " in ai.yml");
                }
                final AiMemory<?> claimed = byKey.putIfAbsent(memory.getKey(), memory);
                if (claimed == null || sameShape(claimed, memory)) {
                    continue;
                }
                throw new IllegalArgumentException("Plan '" + this.name + "' uses the key '"
                        + memory.getKey() + "' for two different memories: " + claimed + " and "
                        + memory + ". The server keys one slot per identifier, so both would share it"
                        + " and whichever read second would get the other's value");
            }
            return new LinkedHashSet<>(byKey.values());
        }

        /** Whether two memories claiming one key agree about everything that key implies. */
        private static boolean sameShape(@NotNull AiMemory<?> first, @NotNull AiMemory<?> second) {
            return first.getValueType() == second.getValueType()
                    && first.getOrigin() == second.getOrigin()
                    && first.getDefaultExpiryTicks() == second.getDefaultExpiryTicks();
        }
    }
}
