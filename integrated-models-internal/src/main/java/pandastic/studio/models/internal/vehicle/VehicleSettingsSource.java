package pandastic.studio.models.internal.vehicle;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Where the vehicle layer gets its current {@link VehicleSettings} from.
 *
 * <p>The read half of the configuration layer's snapshot publication, narrowed so nothing in this
 * package has to name that layer. A live implementation is a lambda over the live snapshot; a caller
 * with a fixed configuration, and every test, uses {@link #fixed}.
 *
 * <h2>Read once per step, and once again when the task is dispatched</h2>
 * Two different reads with two different rules, and both matter.
 *
 * <p>The control body reads this <strong>once per step</strong> and uses the result for the whole step.
 * Reading it per value would let a reload land between the clamp on the elapsed time and the threshold
 * that decides whether the resulting delta is a teleport, which is the same straddling hazard the
 * simulation's one-snapshot-per-step rule exists to prevent.
 *
 * <p>{@link VehicleSettings#controlPeriodTicks()} is the exception and cannot be honoured from inside
 * the body at all: a repeating task's rate belongs to the platform, and the only way to change it is to
 * cancel and dispatch again — which a task cannot do to itself. So the period is read when the task is
 * dispatched, and again by the explicit reload path that compares the configured period against the
 * dispatched one.
 *
 * <p>Implementations must be safe to call from any thread: a vehicle is created from whichever thread
 * owns its root entity, and a reload is published from a pool thread.
 */
@FunctionalInterface
public interface VehicleSettingsSource {

    /** The settings in force right now. Cheap: expected to be one volatile read and one allocation. */
    @NotNull VehicleSettings current();

    /**
     * A source that always answers {@code settings}.
     *
     * <p>Where it earns its keep is in tests: a fixed source built from deliberately unusual thresholds
     * is what distinguishes a control loop that reads its configuration from one that ignores it and
     * agrees with the default by accident.
     */
    static @NotNull VehicleSettingsSource fixed(@NotNull VehicleSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return () -> settings;
    }
}
