package pandastic.studio.models.api.input;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A condition over a player's input, composed rather than computed.
 *
 * <h2>Why a type and not a predicate</h2>
 * A {@code Predicate<InputTrace>} would express every combination in this file and would be opaque:
 * nothing could say what keys it looks at, so nothing could tell a consumer that its new binding fires
 * on every match of one it registered earlier. The whole point of a declarative shape is that it can be
 * <em>read</em> — {@link #requiredKeys()} and {@link #forbiddenKeys()} are what make
 * {@link #relationTo(InputCombination)} possible, and that is what turns a shadowing pair of bindings
 * from a mystery into a line at registration.
 *
 * <p>So the vocabulary is closed. Eight forms cover what a control scheme asks for:
 *
 * <ul>
 *   <li>{@link #held(InputKey)} — the key is down now;</li>
 *   <li>{@link #up(InputKey)} — the key is not down now, which is how a modifier is excluded;</li>
 *   <li>{@link #pressed(InputKey)} — it went down on this publication;</li>
 *   <li>{@link #released(InputKey)} — it came up on this publication;</li>
 *   <li>{@link #hold(InputKey)} — it has been down for at least the configured threshold;</li>
 *   <li>{@link #doubleTap(InputKey)} — it was just pressed and the previous press was inside the
 *       configured window;</li>
 *   <li>{@link #chord(InputKey...)} and {@link #allOf(List)} — every part at once;</li>
 *   <li>{@link #anyOf(List)} — at least one part.</li>
 * </ul>
 *
 * <h2>There is deliberately no general negation</h2>
 * {@link #up(InputKey)} negates a single key and nothing negates a compound. A negated compound has no
 * sound key requirement — {@code not(a and b)} requires neither {@code a} nor {@code b} to be anything
 * in particular — so admitting it would make {@link #requiredKeys()} a lie and silently turn shadow
 * detection into guesswork. Refusing the form keeps the analysis exact for the conjunctions it is exact
 * for, and a consumer that genuinely wants "not this whole thing" writes the complementary chord, which
 * is a statement a reader can check.
 *
 * <h2>Immutable and shareable</h2>
 * Every form is a record over keys and durations, so a combination can be built once as a constant and
 * matched from any thread. {@link #matches(InputTrace, InputTuning)} reads nothing but its two
 * arguments.
 */
public sealed interface InputCombination {

    /**
     * Duration meaning "use the operator's configured value".
     *
     * <p>Structure: a {@code -1} sentinel, like {@code RigBone.NO_PARENT}. It is what lets
     * {@link #hold(InputKey)} and {@link #doubleTap(InputKey)} exist without writing a duration into a
     * source file — the value arrives from {@link InputTuning} at match time — while the explicit
     * overloads stay available for a consumer stating its own.
     */
    long CONFIGURED = -1L;

    /**
     * Whether {@code trace} satisfies this combination.
     *
     * <p>Pure, and evaluated against one snapshot: a combination cannot observe anything the trace does
     * not carry, which is what stops a match from straddling two publications.
     *
     * @param trace  the input to judge
     * @param tuning the durations an unqualified hold or double tap resolves against
     * @return whether it matches
     */
    boolean matches(@NotNull InputTrace trace, @NotNull InputTuning tuning);

    /**
     * The keys that must be down for this combination to match, ever.
     *
     * <p>A necessary condition, not a sufficient one. For a conjunction of held and not-held keys it is
     * also sufficient; for the temporal and disjunctive forms it is strictly weaker, which is what
     * {@link #isConjunction()} declares and what makes {@link #relationTo(InputCombination)}
     * conservative.
     *
     * @return an unmodifiable set, possibly empty
     */
    @NotNull Set<InputKey> requiredKeys();

    /**
     * The keys that must be up for this combination to match, ever.
     *
     * @return an unmodifiable set, possibly empty
     */
    @NotNull Set<InputKey> forbiddenKeys();

    /**
     * Whether this combination is exactly "these keys down and these keys up".
     *
     * <p>True for {@link Held}, {@link Up} and an {@link All} of those. False for everything with a
     * time in it and for {@link Any}, because for those the key sets are necessary conditions rather
     * than the whole condition. Only when both sides are conjunctions can containment of the key sets
     * be read as containment of the matches.
     *
     * @return whether the key sets describe this combination completely
     */
    boolean isConjunction();

    /**
     * A readable form, for a diagnostic or a log line.
     *
     * @return the description, never blank
     */
    @NotNull String describe();

    /**
     * Whether any input at all could match this.
     *
     * <p>Two ways to write something that cannot: requiring and forbidding the same key, and asking for
     * a hold on a {@link InputKind#PULSE} key, which is never down for a measurable time. Both are
     * mistakes a consumer makes while composing, and both are reported at registration as
     * {@link InputIssue.Kind#UNSATISFIABLE} rather than becoming a handler that never runs.
     *
     * @return whether some input could satisfy it
     */
    default boolean isSatisfiable() {
        return Collections.disjoint(requiredKeys(), forbiddenKeys());
    }

    /**
     * This combination and {@code other}, both.
     *
     * @param other the other condition
     * @return the conjunction
     */
    @Contract(pure = true)
    default @NotNull InputCombination and(@NotNull InputCombination other) {
        return allOf(List.of(this, Objects.requireNonNull(other, "other")));
    }

    /**
     * This combination with {@code key} required to be up.
     *
     * <p>The readable way to write a modifier exclusion — {@code held(USE).without(SNEAK)} — and the
     * form that keeps the result a conjunction, so shadow detection stays exact.
     *
     * @param key the key that must not be down
     * @return the conjunction
     */
    @Contract(pure = true)
    default @NotNull InputCombination without(@NotNull InputKey key) {
        return and(up(key));
    }

    /**
     * This combination or {@code other}.
     *
     * @param other the alternative
     * @return the disjunction
     */
    @Contract(pure = true)
    default @NotNull InputCombination or(@NotNull InputCombination other) {
        return anyOf(List.of(this, Objects.requireNonNull(other, "other")));
    }

    /**
     * How this combination stands to {@code other}.
     *
     * <p>See {@link InputRelation} for what each answer means and why the analysis is deliberately
     * conservative. The relation is <em>oriented</em>: {@code a.relationTo(b)} being
     * {@link InputRelation#SHADOWS} means {@code a} fires whenever {@code b} does.
     *
     * @param other the combination to compare against
     * @return the relation
     */
    @Contract(pure = true)
    default @NotNull InputRelation relationTo(@NotNull InputCombination other) {
        Objects.requireNonNull(other, "other");
        if (equals(other)) {
            return InputRelation.EQUIVALENT;
        }

        final Set<InputKey> required = requiredKeys();
        final Set<InputKey> forbidden = forbiddenKeys();
        final Set<InputKey> otherRequired = other.requiredKeys();
        final Set<InputKey> otherForbidden = other.forbiddenKeys();

        // Sound for every form, because requiredKeys is a necessary condition: if this one needs a key
        // down that the other needs up, no single input satisfies both.
        if (!Collections.disjoint(required, otherForbidden)
                || !Collections.disjoint(forbidden, otherRequired)) {
            return InputRelation.INDEPENDENT;
        }

        if (!isConjunction() || !other.isConjunction()) {
            return InputRelation.OVERLAPS;
        }
        if (required.equals(otherRequired) && forbidden.equals(otherForbidden)) {
            return InputRelation.EQUIVALENT;
        }
        if (otherRequired.containsAll(required) && otherForbidden.containsAll(forbidden)) {
            return InputRelation.SHADOWS;
        }
        if (required.containsAll(otherRequired) && forbidden.containsAll(otherForbidden)) {
            return InputRelation.SHADOWED_BY;
        }
        return InputRelation.OVERLAPS;
    }

    // ----------------------------------------------------------------- factories

    /** The key is down. */
    @Contract(value = "_ -> new", pure = true)
    static @NotNull InputCombination held(@NotNull InputKey key) {
        return new Held(key);
    }

    /** The key is not down. */
    @Contract(value = "_ -> new", pure = true)
    static @NotNull InputCombination up(@NotNull InputKey key) {
        return new Up(key);
    }

    /** The key went down on this publication. */
    @Contract(value = "_ -> new", pure = true)
    static @NotNull InputCombination pressed(@NotNull InputKey key) {
        return new Pressed(key);
    }

    /** The key came up on this publication. */
    @Contract(value = "_ -> new", pure = true)
    static @NotNull InputCombination released(@NotNull InputKey key) {
        return new Released(key);
    }

    /** The key has been down for at least the operator's configured threshold. */
    @Contract(value = "_ -> new", pure = true)
    static @NotNull InputCombination hold(@NotNull InputKey key) {
        return new Hold(key, CONFIGURED);
    }

    /**
     * The key has been down for at least {@code minHeldNanos}.
     *
     * @param key          the key
     * @param minHeldNanos the threshold; the consumer's own statement, not the operator's
     */
    @Contract(value = "_, _ -> new", pure = true)
    static @NotNull InputCombination hold(@NotNull InputKey key, long minHeldNanos) {
        return new Hold(key, minHeldNanos);
    }

    /** The key was just pressed and the previous press was inside the configured window. */
    @Contract(value = "_ -> new", pure = true)
    static @NotNull InputCombination doubleTap(@NotNull InputKey key) {
        return new DoubleTap(key, CONFIGURED);
    }

    /**
     * The key was just pressed and the previous press was at most {@code windowNanos} earlier.
     *
     * @param key         the key
     * @param windowNanos the window; the consumer's own statement, not the operator's
     */
    @Contract(value = "_, _ -> new", pure = true)
    static @NotNull InputCombination doubleTap(@NotNull InputKey key, long windowNanos) {
        return new DoubleTap(key, windowNanos);
    }

    /**
     * Every one of {@code keys} is down at once.
     *
     * <p>The common case, and the one the owner asked for by name: {@code chord(SPRINT, JUMP)}.
     *
     * @param keys the keys; one key is allowed and gives the same thing {@link #held(InputKey)} does
     * @return the chord
     * @throws IllegalArgumentException if {@code keys} is empty
     */
    @Contract(pure = true)
    static @NotNull InputCombination chord(@NotNull InputKey @NotNull ... keys) {
        Objects.requireNonNull(keys, "keys");
        if (keys.length == 0) {
            // An empty chord matches everything, which as a binding means "run on every publication".
            // That is never what a caller meant to write, and it is the one condition that would make
            // every shadow report in the registry name it.
            throw new IllegalArgumentException("A chord needs at least one key");
        }
        final List<InputCombination> parts = new ArrayList<>(keys.length);
        for (final InputKey key : keys) {
            parts.add(new Held(key));
        }
        return parts.size() == 1 ? parts.getFirst() : new All(parts);
    }

    /**
     * Every one of {@code parts} matches.
     *
     * @param parts the conditions
     * @return the conjunction, or the single part when there is one
     * @throws IllegalArgumentException if {@code parts} is empty
     */
    @Contract(pure = true)
    static @NotNull InputCombination allOf(@NotNull List<@NotNull InputCombination> parts) {
        final List<InputCombination> copy = flatten(parts, true);
        return copy.size() == 1 ? copy.getFirst() : new All(copy);
    }

    /**
     * At least one of {@code parts} matches.
     *
     * @param parts the alternatives
     * @return the disjunction, or the single part when there is one
     * @throws IllegalArgumentException if {@code parts} is empty
     */
    @Contract(pure = true)
    static @NotNull InputCombination anyOf(@NotNull List<@NotNull InputCombination> parts) {
        final List<InputCombination> copy = flatten(parts, false);
        return copy.size() == 1 ? copy.getFirst() : new Any(copy);
    }

    /**
     * Copies {@code parts} and splices nested nodes of the same kind into it.
     *
     * <p>Flattening is not cosmetic: {@code a.and(b).and(c)} would otherwise nest, and two
     * combinations that describe the same condition would compare unequal — so a registry would report
     * them as overlapping rather than equivalent, which is the one answer that is actively misleading.
     *
     * @param parts       the parts to copy
     * @param conjunction whether to splice {@link All} ({@code true}) or {@link Any}
     * @return a fresh, flattened list
     */
    private static @NotNull List<InputCombination> flatten(
            @NotNull List<@NotNull InputCombination> parts,
            boolean conjunction
    ) {
        Objects.requireNonNull(parts, "parts");
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("A composed combination needs at least one part");
        }
        final List<InputCombination> copy = new ArrayList<>(parts.size());
        for (final InputCombination part : parts) {
            Objects.requireNonNull(part, "part");
            final List<InputCombination> nested = switch (part) {
                case All all when conjunction -> all.parts();
                case Any any when !conjunction -> any.parts();
                default -> List.of();
            };
            if (nested.isEmpty()) {
                if (!copy.contains(part)) {
                    copy.add(part);
                }
            } else {
                for (final InputCombination inner : nested) {
                    if (!copy.contains(inner)) {
                        copy.add(inner);
                    }
                }
            }
        }
        return List.copyOf(copy);
    }

    /** The one-key set, unmodifiable. */
    private static @NotNull Set<InputKey> only(@NotNull InputKey key) {
        return Collections.unmodifiableSet(EnumSet.of(Objects.requireNonNull(key, "key")));
    }

    /** {@code key}'s name as a description reads it. */
    private static @NotNull String name(@NotNull InputKey key) {
        return key.name().toLowerCase(Locale.ROOT);
    }

    /** {@code nanos}, or the configured value, as a description reads it. */
    private static @NotNull String duration(long nanos) {
        return nanos == CONFIGURED
                ? "configured"
                : (nanos / InputTuning.NANOS_PER_MILLISECOND) + "ms";
    }

    // -------------------------------------------------------------------- forms

    /**
     * The key is down.
     *
     * @param key the key
     */
    record Held(@NotNull InputKey key) implements InputCombination {

        public Held {
            Objects.requireNonNull(key, "key");
        }

        @Override
        public boolean matches(@NotNull InputTrace trace, @NotNull InputTuning tuning) {
            return trace.isDown(this.key);
        }

        @Override
        public @NotNull Set<InputKey> requiredKeys() {
            return only(this.key);
        }

        @Override
        public @NotNull Set<InputKey> forbiddenKeys() {
            return Set.of();
        }

        @Override
        public boolean isConjunction() {
            return true;
        }

        @Override
        public @NotNull String describe() {
            return name(this.key);
        }
    }

    /**
     * The key is not down.
     *
     * @param key the key
     */
    record Up(@NotNull InputKey key) implements InputCombination {

        public Up {
            Objects.requireNonNull(key, "key");
        }

        @Override
        public boolean matches(@NotNull InputTrace trace, @NotNull InputTuning tuning) {
            return !trace.isDown(this.key);
        }

        @Override
        public @NotNull Set<InputKey> requiredKeys() {
            return Set.of();
        }

        @Override
        public @NotNull Set<InputKey> forbiddenKeys() {
            return only(this.key);
        }

        @Override
        public boolean isConjunction() {
            return true;
        }

        @Override
        public @NotNull String describe() {
            return "!" + name(this.key);
        }
    }

    /**
     * The key went down on this publication.
     *
     * @param key the key
     */
    record Pressed(@NotNull InputKey key) implements InputCombination {

        public Pressed {
            Objects.requireNonNull(key, "key");
        }

        @Override
        public boolean matches(@NotNull InputTrace trace, @NotNull InputTuning tuning) {
            return trace.justPressed(this.key);
        }

        @Override
        public @NotNull Set<InputKey> requiredKeys() {
            return only(this.key);
        }

        @Override
        public @NotNull Set<InputKey> forbiddenKeys() {
            return Set.of();
        }

        @Override
        public boolean isConjunction() {
            // The key being down is necessary but not sufficient: a press is an edge, and a trace with
            // the key down for a while satisfies the key set and not this condition.
            return false;
        }

        @Override
        public @NotNull String describe() {
            return "press(" + name(this.key) + ')';
        }
    }

    /**
     * The key came up on this publication.
     *
     * <p>Note the key sets are the mirror of {@link Pressed}'s: a release is observed on a trace where
     * the key is <em>up</em>, so the key is forbidden rather than required.
     *
     * @param key the key
     */
    record Released(@NotNull InputKey key) implements InputCombination {

        public Released {
            Objects.requireNonNull(key, "key");
        }

        @Override
        public boolean matches(@NotNull InputTrace trace, @NotNull InputTuning tuning) {
            return trace.justReleased(this.key);
        }

        @Override
        public @NotNull Set<InputKey> requiredKeys() {
            return Set.of();
        }

        @Override
        public @NotNull Set<InputKey> forbiddenKeys() {
            return only(this.key);
        }

        @Override
        public boolean isConjunction() {
            return false;
        }

        @Override
        public @NotNull String describe() {
            return "release(" + name(this.key) + ')';
        }
    }

    /**
     * The key has been down for a while.
     *
     * @param key          the key
     * @param minHeldNanos the threshold, or {@link #CONFIGURED}
     */
    record Hold(@NotNull InputKey key, long minHeldNanos) implements InputCombination {

        public Hold {
            Objects.requireNonNull(key, "key");
            if (minHeldNanos != CONFIGURED && minHeldNanos <= 0L) {
                throw new IllegalArgumentException("minHeldNanos must be positive or "
                        + CONFIGURED + " but was " + minHeldNanos);
            }
        }

        @Override
        public boolean matches(@NotNull InputTrace trace, @NotNull InputTuning tuning) {
            final long threshold =
                    this.minHeldNanos == CONFIGURED ? tuning.holdThresholdNanos() : this.minHeldNanos;
            return trace.isDown(this.key) && trace.heldNanos(this.key) >= threshold;
        }

        @Override
        public @NotNull Set<InputKey> requiredKeys() {
            return only(this.key);
        }

        @Override
        public @NotNull Set<InputKey> forbiddenKeys() {
            return Set.of();
        }

        @Override
        public boolean isConjunction() {
            return false;
        }

        @Override
        public boolean isSatisfiable() {
            // A pulse is true for one publication, so it has no measurable duration and no threshold
            // can be reached. Saying so here is what turns "my handler never fires" into a line at
            // registration.
            return this.key.kind() != InputKind.PULSE;
        }

        @Override
        public @NotNull String describe() {
            return "hold(" + name(this.key) + ',' + duration(this.minHeldNanos) + ')';
        }
    }

    /**
     * The key was just pressed, and the press before it was recent.
     *
     * @param key         the key
     * @param windowNanos the window, or {@link #CONFIGURED}
     */
    record DoubleTap(@NotNull InputKey key, long windowNanos) implements InputCombination {

        public DoubleTap {
            Objects.requireNonNull(key, "key");
            if (windowNanos != CONFIGURED && windowNanos <= 0L) {
                throw new IllegalArgumentException("windowNanos must be positive or "
                        + CONFIGURED + " but was " + windowNanos);
            }
        }

        @Override
        public boolean matches(@NotNull InputTrace trace, @NotNull InputTuning tuning) {
            final long window =
                    this.windowNanos == CONFIGURED ? tuning.doubleTapWindowNanos() : this.windowNanos;
            return trace.justPressed(this.key) && trace.lastPressGapNanos(this.key) <= window;
        }

        @Override
        public @NotNull Set<InputKey> requiredKeys() {
            return only(this.key);
        }

        @Override
        public @NotNull Set<InputKey> forbiddenKeys() {
            return Set.of();
        }

        @Override
        public boolean isConjunction() {
            return false;
        }

        @Override
        public @NotNull String describe() {
            return "doubleTap(" + name(this.key) + ',' + duration(this.windowNanos) + ')';
        }
    }

    /**
     * Every part matches.
     *
     * @param parts the parts, flattened and deduplicated by {@link #allOf(List)}
     */
    record All(@NotNull List<@NotNull InputCombination> parts) implements InputCombination {

        public All {
            parts = List.copyOf(Objects.requireNonNull(parts, "parts"));
            if (parts.size() < 2) {
                // Below two this is a wrapper that changes equality without changing meaning, which
                // would make two descriptions of one condition compare unequal. The factories collapse
                // it; a direct constructor call must not reintroduce it.
                throw new IllegalArgumentException(
                        "An All needs at least two parts; use the single part directly");
            }
        }

        @Override
        public boolean matches(@NotNull InputTrace trace, @NotNull InputTuning tuning) {
            for (final InputCombination part : this.parts) {
                if (!part.matches(trace, tuning)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public @NotNull Set<InputKey> requiredKeys() {
            return union(this.parts, true);
        }

        @Override
        public @NotNull Set<InputKey> forbiddenKeys() {
            return union(this.parts, false);
        }

        @Override
        public boolean isConjunction() {
            for (final InputCombination part : this.parts) {
                if (!part.isConjunction()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean isSatisfiable() {
            for (final InputCombination part : this.parts) {
                if (!part.isSatisfiable()) {
                    return false;
                }
            }
            return Collections.disjoint(requiredKeys(), forbiddenKeys());
        }

        @Override
        public @NotNull String describe() {
            final StringBuilder text = new StringBuilder();
            for (final InputCombination part : this.parts) {
                if (!text.isEmpty()) {
                    text.append('+');
                }
                text.append(part.describe());
            }
            return text.toString();
        }

        /** The union of the parts' required ({@code true}) or forbidden key sets. */
        private static @NotNull Set<InputKey> union(
                @NotNull List<InputCombination> parts,
                boolean required
        ) {
            final EnumSet<InputKey> keys = EnumSet.noneOf(InputKey.class);
            for (final InputCombination part : parts) {
                keys.addAll(required ? part.requiredKeys() : part.forbiddenKeys());
            }
            return Collections.unmodifiableSet(keys);
        }
    }

    /**
     * At least one part matches.
     *
     * <p>Its key sets are the <em>intersection</em> of the parts', not the union: only a key every
     * branch needs is one this condition necessarily needs. That is what keeps
     * {@link #relationTo(InputCombination)} sound in the presence of alternatives.
     *
     * @param parts the alternatives, flattened and deduplicated by {@link #anyOf(List)}
     */
    record Any(@NotNull List<@NotNull InputCombination> parts) implements InputCombination {

        public Any {
            parts = List.copyOf(Objects.requireNonNull(parts, "parts"));
            if (parts.size() < 2) {
                throw new IllegalArgumentException(
                        "An Any needs at least two parts; use the single part directly");
            }
        }

        @Override
        public boolean matches(@NotNull InputTrace trace, @NotNull InputTuning tuning) {
            for (final InputCombination part : this.parts) {
                if (part.matches(trace, tuning)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public @NotNull Set<InputKey> requiredKeys() {
            return intersection(this.parts, true);
        }

        @Override
        public @NotNull Set<InputKey> forbiddenKeys() {
            return intersection(this.parts, false);
        }

        @Override
        public boolean isConjunction() {
            return false;
        }

        @Override
        public boolean isSatisfiable() {
            for (final InputCombination part : this.parts) {
                if (part.isSatisfiable()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public @NotNull String describe() {
            final StringBuilder text = new StringBuilder("any(");
            for (int index = 0; index < this.parts.size(); index++) {
                if (index > 0) {
                    text.append(", ");
                }
                text.append(this.parts.get(index).describe());
            }
            return text.append(')').toString();
        }

        /** The intersection of the parts' required ({@code true}) or forbidden key sets. */
        private static @NotNull Set<InputKey> intersection(
                @NotNull List<InputCombination> parts,
                boolean required
        ) {
            EnumSet<InputKey> keys = null;
            for (final InputCombination part : parts) {
                final Set<InputKey> own = required ? part.requiredKeys() : part.forbiddenKeys();
                if (keys == null) {
                    keys = EnumSet.noneOf(InputKey.class);
                    keys.addAll(own);
                } else {
                    keys.retainAll(own);
                }
            }
            return keys == null ? Set.of() : Collections.unmodifiableSet(keys);
        }
    }
}
