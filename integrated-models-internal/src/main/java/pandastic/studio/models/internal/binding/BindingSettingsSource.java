package pandastic.studio.models.internal.binding;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Where the binding layer gets its current {@link BindingSettings} from.
 *
 * <p>The read half of the configuration layer's snapshot publication, narrowed so nothing in this
 * package has to name that layer. A live implementation is a lambda over the live snapshot; a caller
 * with a fixed configuration, and every test, uses {@link #fixed}.
 *
 * <h2>Read when a task is dispatched, never inside one</h2>
 * The contract here is tighter than the one on the simulation's source, and in the opposite direction.
 * The sampler's period cannot be re-read per execution: a repeating task's rate belongs to the platform
 * and the only way to change it is to cancel and dispatch again, which a task cannot do to itself from
 * inside its own body. So this source is read exactly twice in a model's life — once when the sampler is
 * dispatched, and once by {@link RiggedModelImpl#reload()} to find out whether the configured period has
 * moved away from the dispatched one.
 *
 * <p>That is also what keeps the sampling path allocation-free. A live settings source builds its value
 * from the snapshot on every call, so reading one per sample would put an allocation on a path that runs
 * once per tick per model on the region threads.
 *
 * <p>Implementations must be safe to call from any thread: a model is bound from whichever thread owns
 * its anchor, and a reload is published from a pool thread.
 */
@FunctionalInterface
public interface BindingSettingsSource {

    /** The settings in force right now. Cheap: expected to be one volatile read. */
    @NotNull BindingSettings current();

    /**
     * A source that always answers {@code settings}.
     *
     * <p>Where it earns its keep is in tests: a fixed source built from a deliberately unusual period is
     * what distinguishes a sampler that reads its configuration from one that ignores it and agrees with
     * the default by accident.
     */
    static @NotNull BindingSettingsSource fixed(@NotNull BindingSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return () -> settings;
    }
}
