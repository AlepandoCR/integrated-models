package pandastic.studio.models.api.ai;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A sensor and how often it runs.
 *
 * <h2>Why the period is here and not on the sensor</h2>
 * Because it is not a property of the observation. The same "nearest player" sensor wants a short period
 * on a guard that has to react and a long one on an ornament that only needs to know whether anybody is
 * in the room, and a server with four hundred creatures wants both longer than a server with four. A
 * period written into the sensor would make that a code change; written into the binding it is part of
 * the plan, and its bounds come from {@code ai.yml}.
 *
 * <p>It is also the single biggest cost lever in this layer. A sensor runs on the region thread and
 * looking around is the expensive thing it does, so the period is multiplied by the creature count to
 * get the per-tick bill. Halving it doubles the bill; there is no other number here with that property.
 *
 * @param sensor      what to run
 * @param periodTicks ticks between runs. Bounded by configuration at plan-build time rather than here,
 *                    because the useful range depends on how many creatures a server runs and a bound
 *                    written into this record would be the hardcoded tunable the project rule forbids
 */
public record AiSensorBinding(@NotNull AiSensor sensor, long periodTicks) {

    /**
     * Refuses a period below one tick.
     *
     * <p>Not a tuning decision: the platform treats any period under a tick as one tick, so a zero
     * would be accepted here and silently become one, which makes the configured number and the
     * scheduled number different. The bound that <em>is</em> a tuning decision — how much slower than
     * one tick a sensor may run — lives in configuration and is checked when the plan is built.
     */
    public AiSensorBinding {
        Objects.requireNonNull(sensor, "sensor");
        if (periodTicks < 1L) {
            throw new IllegalArgumentException("Sensor '" + sensor.name() + "' has periodTicks "
                    + periodTicks + "; the platform rounds anything below one tick up to one, so a"
                    + " value under one would not be the period that actually ran");
        }
    }

    @Override
    public @NotNull String toString() {
        return "sensor '" + this.sensor.name() + "' every " + this.periodTicks + " tick(s)";
    }
}
