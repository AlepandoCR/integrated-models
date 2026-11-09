package pandastic.studio.models.internal.ai;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Where the AI layer gets its current {@link AiSettings} from.
 *
 * <p>The read half of the configuration layer's snapshot publication, narrowed so nothing in this
 * package has to name that layer. A live implementation is a lambda over the live snapshot; a caller
 * with a fixed configuration, and every test, uses {@link #fixed}.
 *
 * <h2>Read once per operation, and which operation differs per caller</h2>
 * <ul>
 *   <li><strong>Building a plan</strong> reads it once, through
 *       {@link pandastic.studio.models.api.ai.CreatureAi#getBounds()}. A plan validated against one set
 *       of bounds and then installed is not re-validated, which is correct: the bounds that mattered
 *       are the ones the programmer was told about.</li>
 *   <li><strong>Installing</strong> reads it once and the resulting bounds are fixed for that
 *       installation's life. A context that re-read them could shrink a scan radius halfway through a
 *       body, and a body comparing two scans in one tick needs them taken under one ceiling.</li>
 *   <li><strong>The fault sink</strong> reads it per decision, which is the one place a live read is
 *       worth it: this is the component an operator reaches for <em>while</em> something is going
 *       wrong, so a throttle turned down during an incident should take effect without every
 *       installation being rebuilt.</li>
 * </ul>
 *
 * <p>Implementations must be safe to call from any thread: an install happens on whichever region owns
 * the creature, a plan is built from wherever a plugin builds it, and a reload is published from a pool
 * thread.
 */
@FunctionalInterface
public interface AiSettingsSource {

    /** The settings in force right now. Cheap: expected to be one volatile read and one allocation. */
    @NotNull AiSettings current();

    /**
     * A source that always answers {@code settings}.
     *
     * <p>Where it earns its keep is in tests: a fixed source built from deliberately unusual bounds is
     * what distinguishes a layer that reads its configuration from one that ignores it and agrees with
     * the default by accident.
     *
     * @param settings what to answer
     * @return the source
     */
    static @NotNull AiSettingsSource fixed(@NotNull AiSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return () -> settings;
    }
}
