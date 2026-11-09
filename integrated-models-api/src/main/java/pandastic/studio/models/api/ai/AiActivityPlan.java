package pandastic.studio.models.api.ai;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One activity slot, filled: what runs in it, when the slot is allowed to be active, and what the
 * creature forgets when it leaves.
 *
 * <h2>The three parts, and why they are not one</h2>
 * <ul>
 *   <li><strong>Prioritised behaviours.</strong> The brain walks them in order and starts the first
 *       one that can. Lower number first, which is the server's convention and the opposite of what
 *       "priority" usually suggests — so it is worth saying out loud rather than discovering.</li>
 *   <li><strong>Conditions.</strong> Checked before the slot is allowed to be active at all, and
 *       checked again while it is. They are the only way to stop an activity from outside the
 *       behaviours in it, which is what makes "leave combat when the target is gone" a property of the
 *       slot rather than something every combat behaviour has to agree about.</li>
 *   <li><strong>Memories erased on stop.</strong> Run when the slot stops being active. The mechanism
 *       that stops a stale decision from surviving into the next activity — a chase target left behind
 *       by a fight that ended means the next fight starts by running at whatever was there last
 *       time.</li>
 * </ul>
 *
 * <h2>Why the behaviours are sorted here and not left to the server</h2>
 * They are handed to the server as a list and it respects the order given. Sorting at build time means
 * the order a reader sees in the plan is the order that runs, and it means two plans that declared the
 * same behaviours in different source order are the same plan. Ties keep declaration order, so a
 * deliberate pair at one priority stays in the order it was written.
 *
 * <p>Immutable once built, like everything else in this package, so one plan can be installed on any
 * number of creatures without the installs being able to interfere.
 */
public final class AiActivityPlan {

    /**
     * A behaviour and where it sits in its slot's order.
     *
     * @param priority  lower runs first; bounded by {@link AiBounds#maxPriority()}
     * @param behaviour what to run
     */
    public record PrioritisedBehaviour(int priority, @NotNull AiBehaviour behaviour) {

        public PrioritisedBehaviour {
            Objects.requireNonNull(behaviour, "behaviour");
            if (priority < 0) {
                throw new IllegalArgumentException("Behaviour '" + behaviour.name()
                        + "' has priority " + priority
                        + "; the server orders behaviours by ascending priority from zero");
            }
        }

        @Override
        public @NotNull String toString() {
            return this.priority + ":" + this.behaviour.name();
        }
    }

    /**
     * A memory that has to be in a particular state for the slot to run.
     *
     * @param memory the memory to look at
     * @param status what it has to look like
     */
    public record Condition(@NotNull AiMemory<?> memory, @NotNull AiMemoryStatus status) {

        public Condition {
            Objects.requireNonNull(memory, "memory");
            Objects.requireNonNull(status, "status");
        }

        @Override
        public @NotNull String toString() {
            return this.memory.getKey() + " " + this.status;
        }
    }

    private final @NotNull AiActivity activity;
    private final @NotNull List<PrioritisedBehaviour> behaviours;
    private final @NotNull Set<Condition> conditions;
    private final @NotNull Set<AiMemory<?>> memoriesToEraseOnStop;

    private AiActivityPlan(
            @NotNull AiActivity activity,
            @NotNull List<PrioritisedBehaviour> behaviours,
            @NotNull Set<Condition> conditions,
            @NotNull Set<AiMemory<?>> memoriesToEraseOnStop
    ) {
        this.activity = activity;
        this.behaviours = behaviours;
        this.conditions = conditions;
        this.memoriesToEraseOnStop = memoriesToEraseOnStop;
    }

    /**
     * A builder for the {@code activity} slot.
     *
     * <p>The slot is fixed at the start rather than set on the builder, because every validation
     * message this type produces wants to name it and a slot set last would leave the earlier messages
     * unable to.
     *
     * @param activity which slot this fills
     * @return the builder
     */
    @Contract(value = "_ -> new", pure = true)
    public static @NotNull Builder builder(@NotNull AiActivity activity) {
        return new Builder(activity);
    }

    /** Which slot this fills. */
    public @NotNull AiActivity getActivity() {
        return this.activity;
    }

    /** The behaviours, ascending by priority, ties in declaration order. Immutable. */
    public @NotNull List<PrioritisedBehaviour> getBehaviours() {
        return this.behaviours;
    }

    /** What has to be true for this slot to run. Immutable, possibly empty. */
    public @NotNull Set<Condition> getConditions() {
        return this.conditions;
    }

    /** What the creature forgets when this slot stops. Immutable, possibly empty. */
    public @NotNull Set<AiMemory<?>> getMemoriesToEraseOnStop() {
        return this.memoriesToEraseOnStop;
    }

    /**
     * Every mention of a memory in this slot, from any of its three parts, <strong>including
     * repeats</strong>.
     *
     * <p>What {@link AiPlan} unions to decide which memories a creature's brain is built with. Gathered
     * here rather than in the plan so that adding a fourth place a memory can appear is a change to one
     * method.
     *
     * <h2>Why a list and not a set</h2>
     * Because two {@link AiMemory} values with one key are <em>equal</em> — deliberately, since the
     * server gives them one slot — so a set would silently keep whichever it saw first and discard the
     * other. That discarded one is precisely what the plan has to notice: a behaviour requiring
     * {@code cooldown} as a {@code Long} and another using it as an {@code Integer} would share a slot
     * and whichever read second would get a {@link ClassCastException}. Collapsing them here would make
     * that collision undetectable, which is the only remaining way a typed read can come back wrong.
     *
     * @return every mention, in a stable order, with duplicates intact
     */
    public @NotNull List<AiMemory<?>> referencedMemories() {
        final List<AiMemory<?>> referenced = new ArrayList<>();
        for (final PrioritisedBehaviour entry : this.behaviours) {
            referenced.addAll(entry.behaviour().requiredMemories());
            referenced.addAll(entry.behaviour().usedMemories());
        }
        for (final Condition condition : this.conditions) {
            referenced.add(condition.memory());
        }
        referenced.addAll(this.memoriesToEraseOnStop);
        return referenced;
    }

    @Override
    public @NotNull String toString() {
        return "AiActivityPlan[" + this.activity + ", " + this.behaviours.size() + " behaviour(s), "
                + this.conditions.size() + " condition(s)]";
    }

    /**
     * Collects one slot's contents and checks what can be checked without seeing the whole plan.
     *
     * <p>The bounds that depend on configuration are not checked here — they are checked by
     * {@link AiPlan.Builder}, which is where the {@link AiBounds} are. This builder refuses only what is
     * wrong on its own terms: a duplicate behaviour, a blank name, a negative priority.
     */
    public static final class Builder {

        private final @NotNull AiActivity activity;
        private final @NotNull List<PrioritisedBehaviour> behaviours = new ArrayList<>();
        private final @NotNull Set<Condition> conditions = new LinkedHashSet<>();
        private final @NotNull Set<AiMemory<?>> memoriesToEraseOnStop = new LinkedHashSet<>();

        private Builder(@NotNull AiActivity activity) {
            this.activity = Objects.requireNonNull(activity, "activity");
        }

        /**
         * Adds {@code behaviour} at {@code priority}.
         *
         * <p>Lower priorities run first. Two behaviours may share a priority — the server permits it and
         * it is sometimes what you want for a pair that cannot both start — and they keep declaration
         * order.
         *
         * @param priority  the order key, from zero
         * @param behaviour what to run
         * @return this builder
         * @throws IllegalArgumentException if the behaviour's name is blank, or the same behaviour
         *                                 instance is added twice
         */
        @Contract("_, _ -> this")
        public @NotNull Builder behaviour(int priority, @NotNull AiBehaviour behaviour) {
            Objects.requireNonNull(behaviour, "behaviour");
            if (behaviour.name().isBlank()) {
                throw new IllegalArgumentException("A behaviour in the " + this.activity
                        + " activity has a blank name. The name is the only thing a failure report can"
                        + " say about it, so a blank one makes that report useless");
            }
            for (final PrioritisedBehaviour existing : this.behaviours) {
                if (existing.behaviour() == behaviour) {
                    // Not equals(): two behaviours of the same class are a legitimate pair, while the
                    // same instance twice is a copy-paste that would be ticked twice and stopped once.
                    throw new IllegalArgumentException("Behaviour '" + behaviour.name()
                            + "' is already in the " + this.activity + " activity at priority "
                            + existing.priority() + ". The same instance in one slot would be started"
                            + " and ticked twice while holding one set of state");
                }
            }
            this.behaviours.add(new PrioritisedBehaviour(priority, behaviour));
            return this;
        }

        /**
         * Requires {@code memory} to be in {@code status} for this slot to run.
         *
         * @param memory the memory to look at
         * @param status what it has to look like
         * @return this builder
         * @throws IllegalArgumentException if the same memory already has a different condition, which
         *                                 would be two requirements the brain evaluates independently
         *                                 and that cannot both hold
         */
        @Contract("_, _ -> this")
        public @NotNull Builder requires(@NotNull AiMemory<?> memory, @NotNull AiMemoryStatus status) {
            Objects.requireNonNull(memory, "memory");
            Objects.requireNonNull(status, "status");
            for (final Condition existing : this.conditions) {
                if (existing.memory().equals(memory) && existing.status() != status) {
                    throw new IllegalArgumentException("The " + this.activity + " activity already"
                            + " requires " + memory.getKey() + " to be " + existing.status()
                            + ", so it cannot also require it to be " + status
                            + "; the slot would never be active");
                }
            }
            this.conditions.add(new Condition(memory, status));
            return this;
        }

        /**
         * Forgets {@code memory} when this slot stops.
         *
         * @param memory what to erase
         * @return this builder
         */
        @Contract("_ -> this")
        public @NotNull Builder erasesOnStop(@NotNull AiMemory<?> memory) {
            this.memoriesToEraseOnStop.add(Objects.requireNonNull(memory, "memory"));
            return this;
        }

        /**
         * Builds the slot.
         *
         * @return the immutable slot
         * @throws IllegalArgumentException if no behaviour was added — an activity the brain can enter
         *                                 and that then does nothing is a state the creature can get
         *                                 stuck in with nothing to point at
         */
        @Contract(value = "-> new", pure = true)
        public @NotNull AiActivityPlan build() {
            if (this.behaviours.isEmpty()) {
                throw new IllegalArgumentException("The " + this.activity + " activity has no"
                        + " behaviours. The brain can still make it active, in which case the creature"
                        + " does nothing until a condition changes — and nothing reports it");
            }
            final List<PrioritisedBehaviour> sorted = new ArrayList<>(this.behaviours);
            // Stable, so a deliberate pair at one priority keeps the order it was declared in.
            sorted.sort(Comparator.comparingInt(PrioritisedBehaviour::priority));
            return new AiActivityPlan(this.activity, List.copyOf(sorted),
                    Set.copyOf(this.conditions), Set.copyOf(this.memoriesToEraseOnStop));
        }
    }
}
