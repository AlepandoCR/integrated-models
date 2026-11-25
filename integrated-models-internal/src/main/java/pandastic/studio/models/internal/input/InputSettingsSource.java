package pandastic.studio.models.internal.input;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Where the input layer gets its current {@link InputSettings} from.
 *
 * <p>The read half of the configuration layer's snapshot publication, narrowed so nothing in this
 * package has to name that layer. A live implementation is a lambda over the live snapshot; a caller
 * with a fixed configuration, and every test, uses {@link #fixed}.
 *
 * <h2>Read once per delivery, never per value</h2>
 * One input delivery is one decision: which bindings match, whether a still-matching one is due to
 * repeat, and whether a handler may suppress. Reading this source twice inside that decision lets a
 * reload land between the two reads and judge one input against two generations of configuration —
 * the same hazard the simulation's settings source and the driver sample both document.
 *
 * <h2>Identity stability</h2>
 * Nothing in this package compares an {@link InputSettings} by identity today, and an implementation
 * still owes a value whose identity is stable for as long as the configuration behind it is. The
 * project has shipped the other mistake once: a source allocating per call satisfies the declaration
 * and silently destroys a consumer that compares, and the consumer that compares is one refactor away
 * in a class that does not mention this one. The live implementation memoises against the published
 * snapshot, exactly as every other settings source here does.
 *
 * <p>Implementations must be safe to call from any thread: an input is observed on a player's region
 * thread or on a network thread, and a reload is published from a pool thread.
 */
@FunctionalInterface
public interface InputSettingsSource {

    /** The settings in force right now. Cheap: expected to be one volatile read. */
    @NotNull InputSettings current();

    /**
     * A source that always answers {@code settings}.
     *
     * <p>Where it earns its keep is in tests: a fixed source built from a deliberately unusual repeat
     * interval is what distinguishes a dispatcher that reads its configuration from one that ignores it
     * and agrees with the default by accident.
     *
     * @param settings the settings to answer with
     * @return the source
     */
    static @NotNull InputSettingsSource fixed(@NotNull InputSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return () -> settings;
    }
}
