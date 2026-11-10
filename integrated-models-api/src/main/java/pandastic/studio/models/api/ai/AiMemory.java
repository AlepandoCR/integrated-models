package pandastic.studio.models.api.ai;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One thing a creature can remember, and the type of the thing it remembers.
 *
 * <h2>The asymmetry this type exists to make unrepresentable</h2>
 * The server's memory API fails in two different directions and neither of them is loud. Writing a
 * memory the brain was not built with <em>silently does nothing</em> — the write is accepted, the value
 * is discarded, and the next read says the creature never knew. Reading one <em>throws</em>, from
 * inside the brain's own tick. So the same mistake presents as "my behaviour never fires" in one place
 * and as a stack trace with the server's class names in it in the other, and neither message mentions
 * the memory.
 *
 * <p>The fix is structural rather than defensive: a memory is a value a plan has to carry before
 * anything can be done with it. {@link AiPlan} collects every memory its behaviours, sensors and
 * conditions mention, the installer builds the brain around exactly that set, and the view a behaviour
 * body is handed refuses a memory the plan does not contain. There is no call sequence that reaches
 * either failure.
 *
 * <h2>Two origins, and the type system keeps them apart</h2>
 * <ul>
 *   <li>A <strong>defined</strong> memory is ours. It exists because this plan says so, it holds a
 *       value we chose the type of, and it is not written to disk — the creature forgets it when the
 *       chunk unloads. That is deliberate: a memory type can only be serialised if it is in the
 *       server's registry, the registry is frozen long before a plugin loads, and a memory that
 *       persists by accident is a memory that survives a plan being changed.</li>
 *   <li>A <strong>vanilla</strong> memory is the server's. {@link #vanilla(String)} returns an
 *       {@code AiMemory<Void>}, which makes it <em>readable for presence and nothing else</em> —
 *       {@link AiMemoryView#set} cannot be called on it, because {@link Void} has no instances, so the
 *       restriction costs a caller a compile error instead of a runtime one.</li>
 * </ul>
 *
 * <p>Presence-only is not a shortcut. A vanilla memory's value is a server-internal object — a walk
 * target, a global position, a visibility cache — and handing one of those across this interface would
 * put the server's internals into the published jar, which is the one thing this module may not
 * contain. A plan that wants to know "is this creature in combat" asks whether
 * {@code vanilla("attack_target")} is present, and a plan that wants to <em>set</em> a destination
 * publishes an {@link AiIntent}, which carries the same information in a form that crosses threads
 * safely.
 *
 * <h2>Why the value types are a closed set</h2>
 * See {@link #VALUE_TYPES}. A brain will store any object at all, which is exactly the problem.
 *
 * @param <ValueType> what this memory holds; {@link Void} for a presence-only vanilla reference
 */
public final class AiMemory<ValueType> {

    /**
     * The value types a defined memory may hold.
     *
     * <p>Closed, and the reason is lifetime rather than taste. A memory outlives the tick that wrote it
     * — that is the entire point of one — and on a regionised server the thread that reads it later is
     * not guaranteed to be the thread that wrote it. Every type here is immutable and carries no
     * reference to a world, an entity or a chunk, so a value read in a later tick or from the
     * simulation thread is the value that was stored. A Bukkit {@code Entity} in a memory is a
     * reference that outlives the region that produced it, and the failure is a torn read of a
     * position rather than anything that looks like a lifetime bug.
     *
     * <p>{@link AiPoint} is in the set for the one case the primitives cannot express without a caller
     * inventing an encoding: remembering where something was.
     *
     * <p><strong>Not configurable.</strong> This is a statement about which objects are safe to hold
     * across a tick boundary, not a limit anyone would want raised.
     */
    public static final @NotNull Set<Class<?>> VALUE_TYPES = Set.of(
            Boolean.class,
            Integer.class,
            Long.class,
            Float.class,
            Double.class,
            String.class,
            UUID.class,
            AiPoint.class
    );

    /**
     * Expiry value meaning "until something erases it".
     *
     * <p>A structural zero rather than a tunable default: the server's own distinction is between a
     * memory with an expiry and a memory without one, and zero is how "without one" is spelled.
     */
    public static final long NEVER_EXPIRES = 0L;

    /** Where a memory came from, which decides what may be done with it. */
    public enum Origin {

        /** Declared by a plan. Typed, readable, writable, and never written to disk. */
        DEFINED,

        /** The server's own. Presence-readable only; see the class documentation. */
        VANILLA
    }

    private final @NotNull String key;
    private final @NotNull Class<ValueType> valueType;
    private final @NotNull Origin origin;
    private final long defaultExpiryTicks;

    private AiMemory(
            @NotNull String key,
            @NotNull Class<ValueType> valueType,
            @NotNull Origin origin,
            long defaultExpiryTicks
    ) {
        this.key = key;
        this.valueType = valueType;
        this.origin = origin;
        this.defaultExpiryTicks = defaultExpiryTicks;
    }

    /**
     * A memory this plan owns, holding {@code valueType}, with no expiry.
     *
     * @param key       the identifier, in the server's identifier syntax; see {@link #getKey()}
     * @param valueType one of {@link #VALUE_TYPES}
     * @param <ValueType> the value type
     * @return the memory
     * @throws IllegalArgumentException if the key is not a valid identifier, or the value type is not
     *                                 one this layer can hold across a tick boundary
     */
    @Contract(value = "_, _ -> new", pure = true)
    public static <ValueType> @NotNull AiMemory<ValueType> defined(
            @NotNull String key,
            @NotNull Class<ValueType> valueType
    ) {
        return defined(key, valueType, NEVER_EXPIRES);
    }

    /**
     * A memory this plan owns, holding {@code valueType}, forgotten after
     * {@code defaultExpiryTicks} unless written again.
     *
     * <p>The expiry is a <em>default</em>, not a cap: {@link AiMemoryView#set(AiMemory, Object, long)}
     * overrides it per write. Its upper bound is configuration, checked when the plan is built, because
     * a memory that expires in a year is a memory that never expires and is cheaper to refuse than to
     * diagnose.
     *
     * @param key                the identifier, in the server's identifier syntax
     * @param valueType          one of {@link #VALUE_TYPES}
     * @param defaultExpiryTicks ticks a written value survives, or {@link #NEVER_EXPIRES}
     * @param <ValueType>        the value type
     * @return the memory
     * @throws IllegalArgumentException if the key is invalid, the value type is not permitted, or the
     *                                 expiry is negative
     */
    @Contract(value = "_, _, _ -> new", pure = true)
    public static <ValueType> @NotNull AiMemory<ValueType> defined(
            @NotNull String key,
            @NotNull Class<ValueType> valueType,
            long defaultExpiryTicks
    ) {
        Objects.requireNonNull(valueType, "valueType");
        final String normalised = requireIdentifier(key);
        if (!VALUE_TYPES.contains(valueType)) {
            throw new IllegalArgumentException("Memory '" + normalised + "' cannot hold "
                    + valueType.getName() + ". A memory outlives the tick that wrote it and may be"
                    + " read from a thread that did not write it, so only immutable values carrying no"
                    + " world, entity or chunk reference are accepted: " + describeValueTypes());
        }
        if (defaultExpiryTicks < 0L) {
            throw new IllegalArgumentException("Memory '" + normalised
                    + "' has a negative defaultExpiryTicks (" + defaultExpiryTicks
                    + "); use AiMemory.NEVER_EXPIRES for a memory with no expiry");
        }
        return new AiMemory<>(normalised, valueType, Origin.DEFINED, defaultExpiryTicks);
    }

    /**
     * A presence-only reference to one of the server's own memories.
     *
     * <p>Readable through {@link AiMemoryView#has(AiMemory)}, usable as an activity condition, and
     * usable in an activity's erase-on-stop set. Not writable and not readable for its value: see the
     * class documentation for why that is a property of the published jar rather than a limitation of
     * this method.
     *
     * @param key the server's identifier for the memory, such as {@code attack_target}. A bare path is
     *            taken as {@code minecraft:}-namespaced, because every memory the server ships is
     * @return the reference
     * @throws IllegalArgumentException if the key is not a valid identifier
     */
    @Contract(value = "_ -> new", pure = true)
    public static @NotNull AiMemory<Void> vanilla(@NotNull String key) {
        return new AiMemory<>(requireIdentifier(key), Void.class, Origin.VANILLA, NEVER_EXPIRES);
    }

    /**
     * The identifier, lower-cased and namespaced.
     *
     * <p>In the server's own identifier syntax — {@code namespace:path}, both parts restricted to
     * {@code [a-z0-9_.-]} with {@code /} additionally allowed in the path. That character set is a
     * protocol fact rather than a choice of ours: the key has to survive being turned into a server
     * identifier below the plugin's NMS seam, and a key that cannot is one this layer would otherwise
     * accept and then fail to install.
     *
     * @return the identifier
     */
    public @NotNull String getKey() {
        return this.key;
    }

    /** What this memory holds, or {@link Void} for a vanilla presence-only reference. */
    public @NotNull Class<ValueType> getValueType() {
        return this.valueType;
    }

    /** Where this memory came from. */
    public @NotNull Origin getOrigin() {
        return this.origin;
    }

    /** Whether this is a presence-only reference to one of the server's memories. */
    public boolean isVanilla() {
        return this.origin == Origin.VANILLA;
    }

    /** Ticks a written value survives by default, or {@link #NEVER_EXPIRES}. */
    public long getDefaultExpiryTicks() {
        return this.defaultExpiryTicks;
    }

    /**
     * Narrows {@code value} to this memory's value type.
     *
     * <p>Needed because an {@link AiMemoryView} stores heterogeneous memories in one map and gets back
     * an {@code Object}. The cast is {@link Class#cast(Object)} rather than an unchecked one, so a
     * value of the wrong type fails here, naming both types, instead of at the call site that assigned
     * it to a variable.
     *
     * @param value the value to narrow, or {@code null}
     * @return the value as this memory's type, or {@code null} if it was
     * @throws ClassCastException if {@code value} is not of this memory's value type
     */
    public ValueType cast(Object value) {
        return this.valueType.cast(value);
    }

    /**
     * Identity is the key.
     *
     * <p>Two memories with the same key are the same memory as far as the server is concerned — it
     * stores one slot per key — so making them equal here is what lets {@link AiPlan} detect that a
     * plan declared the same key with two different value types, which is the one way a typed read can
     * still come back wrong.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof AiMemory<?> memory && this.key.equals(memory.key);
    }

    @Override
    public int hashCode() {
        return this.key.hashCode();
    }

    @Override
    public @NotNull String toString() {
        return this.isVanilla()
                ? "vanilla memory '" + this.key + "'"
                : "memory '" + this.key + "' of " + this.valueType.getSimpleName();
    }

    /**
     * Validates and normalises an identifier.
     *
     * <p>Namespace defaulting matches the server's: a bare path becomes {@code minecraft:}-namespaced.
     * That is wanted for {@link #vanilla(String)}, where the caller is naming a server memory, and
     * harmless for a defined one as long as it is applied consistently — which is why it is applied
     * here, once, rather than at each call site.
     */
    private static @NotNull String requireIdentifier(@NotNull String key) {
        Objects.requireNonNull(key, "key");
        final String lowered = key.toLowerCase(Locale.ROOT);
        final int separator = lowered.indexOf(':');
        final String namespace = separator < 0 ? "minecraft" : lowered.substring(0, separator);
        final String path = separator < 0 ? lowered : lowered.substring(separator + 1);

        if (!isValid(namespace, false) || !isValid(path, true)) {
            throw new IllegalArgumentException("Memory key '" + key + "' is not a valid identifier."
                    + " A namespace accepts [a-z0-9_.-] and a path additionally accepts '/', both"
                    + " non-empty, because the key has to become a server identifier below the NMS"
                    + " seam and one that cannot would be accepted here and refused there");
        }
        return namespace + ':' + path;
    }

    private static boolean isValid(@NotNull String part, boolean allowSlash) {
        if (part.isEmpty()) {
            return false;
        }
        for (int index = 0; index < part.length(); index++) {
            final char character = part.charAt(index);
            final boolean permitted = (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '_' || character == '.' || character == '-'
                    || (allowSlash && character == '/');
            if (!permitted) {
                return false;
            }
        }
        return true;
    }

    /** The permitted value types, sorted, for a message a caller can act on. */
    private static @NotNull String describeValueTypes() {
        return String.join(", ", valueTypeNames());
    }

    /**
     * The permitted value types as a stable, sorted list.
     *
     * <p>Exposed so a loader reading a plan out of a file can render the same list in its own
     * diagnostic rather than reproducing it.
     *
     * @return the simple names of {@link #VALUE_TYPES}, sorted
     */
    public static @NotNull List<String> valueTypeNames() {
        return VALUE_TYPES.stream().map(Class::getSimpleName).sorted().toList();
    }
}
