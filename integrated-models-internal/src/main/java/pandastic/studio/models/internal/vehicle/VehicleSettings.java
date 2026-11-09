package pandastic.studio.models.internal.vehicle;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.vehicle.VehicleBounds;

import java.util.Objects;

/**
 * Everything the vehicle layer reads out of configuration, as one immutable snapshot.
 *
 * <p>Same shape and same contract as {@link pandastic.studio.models.internal.render.RenderSettings},
 * {@link pandastic.studio.models.internal.sim.SimulationSettings} and
 * {@link pandastic.studio.models.internal.binding.BindingSettings}: a value a consumer reads once and
 * uses for a whole operation, never a setting per call site. No field has a default here, because a
 * default would be the hardcoded value this type exists to remove.
 *
 * <p>Not to be confused with {@link pandastic.studio.models.internal.config.VehicleSettings}, which is
 * the <em>declaration</em> of {@code vehicle.yml} — the keys, the comments and the defaults. This is the
 * assembled, already-validated bundle. The project has the same deliberate shadow for render, simulation
 * and AI.
 *
 * @param bounds                 the ranges a profile is accepted within. Held rather than read per
 *                               creation because a profile's validation has to be against one coherent
 *                               set of ranges, and the file could reload between two components of it
 * @param controlPeriodTicks     ticks between two decisions about where a vehicle is. Read when the
 *                               control task is dispatched and never inside it, for the reason
 *                               {@link pandastic.studio.models.internal.binding.BindingSettingsSource}
 *                               gives: a repeating task's rate belongs to the platform
 * @param maxStepTicks           longest gap one step will integrate across. The guard on a server that
 *                               stalled — integrating the whole gap faithfully would move a vehicle
 *                               through whatever was in the way
 * @param maxLocalStepBlocks     furthest a single in-place position write may reach, above which a step
 *                               goes through the asynchronous teleport
 * @param restSpeedBlocksPerTick speed below which a coasting vehicle is snapped to a stop, so that
 *                               friction's geometric tail stops costing a position write per tick
 */
public record VehicleSettings(
        @NotNull VehicleBounds bounds,
        long controlPeriodTicks,
        float maxStepTicks,
        float maxLocalStepBlocks,
        float restSpeedBlocksPerTick
) {

    public VehicleSettings {
        Objects.requireNonNull(bounds, "bounds");
        if (controlPeriodTicks < 1L) {
            // The platform treats any period below one tick as one, so a zero or negative figure does
            // not do what a file saying it would mean — it silently becomes one. Refusing it is what
            // makes the configured value and the scheduled value the same number.
            throw new IllegalArgumentException(
                    "controlPeriodTicks must be at least 1 but was " + controlPeriodTicks
                            + "; rider input only changes once per client tick, so there is nothing"
                            + " below one tick to decide on");
        }
        requirePositive(maxStepTicks, "maxStepTicks");
        if (maxStepTicks < controlPeriodTicks) {
            // Not a tidiness check. With the ceiling below the period, every ordinary step is clamped,
            // so every vehicle on the server covers less ground than its profile says — silently, with
            // nothing wrong except the speed.
            throw new IllegalArgumentException("maxStepTicks must be at least controlPeriodTicks, which"
                    + " is " + controlPeriodTicks + ", but was " + maxStepTicks
                    + "; otherwise every ordinary step is clamped and every vehicle runs slow");
        }
        requirePositive(maxLocalStepBlocks, "maxLocalStepBlocks");
        requirePositive(restSpeedBlocksPerTick, "restSpeedBlocksPerTick");
    }

    private static void requirePositive(float value, String name) {
        if (!Float.isFinite(value) || value <= 0F) {
            throw new IllegalArgumentException(name + " must be finite and positive but was " + value);
        }
    }
}
