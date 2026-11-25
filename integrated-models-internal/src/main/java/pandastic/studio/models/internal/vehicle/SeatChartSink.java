package pandastic.studio.models.internal.vehicle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.jetbrains.annotations.NotNull;

/**
 * Where a seat chart is written.
 *
 * <h2>Why a seam rather than a logger</h2>
 * The same reason {@link pandastic.studio.models.internal.sim.SimulationLogger} is one: the vehicle layer
 * is exercised with no plugin, no server and no logging backend, and a chart is the one thing in that
 * layer whose <em>output</em> is the behaviour under test. A test holds a sink that keeps the components
 * and asserts on them; production holds {@link #component(ComponentLogger)}.
 *
 * <h2>Two severities, and the rule is per chart rather than per line</h2>
 * A chart with an unusable seat goes to {@link #warn}, every line of it, and a chart whose every seat
 * resolved goes to {@link #info}. Mixing the two inside one chart was the first attempt and it is worse
 * than either: a console prefixes each line with its own level, so a box drawn half at INFO and half at
 * WARN arrives with its borders broken and the thing that was meant to stand out buried in the middle of
 * it. One severity for the whole chart keeps the box a box and makes a vehicle with a broken seat show up
 * in a console filtered to warnings.
 */
public interface SeatChartSink {

    /** One line of a chart whose every declared seat resolved to a locator. */
    void info(@NotNull Component line);

    /** One line of a chart that has at least one seat nobody can sit on. */
    void warn(@NotNull Component line);

    /**
     * A sink that writes nothing.
     *
     * <p>Not only a test double: it is also what the operator's {@code logSeatChart: false} amounts to
     * from this layer's point of view, and what a vehicle built by a caller with nowhere to report gets.
     */
    @NotNull SeatChartSink NONE = new SeatChartSink() {

        @Override
        public void info(@NotNull Component line) {
            // Nothing to write to.
        }

        @Override
        public void warn(@NotNull Component line) {
            // Nothing to write to.
        }

        @Override
        public @NotNull String toString() {
            return "SeatChartSink.NONE";
        }
    };

    /**
     * The production sink: the plugin's own component logger.
     *
     * <p>A component logger rather than {@code java.util.logging} or a string, because the chart's colour
     * is how it carries meaning — a seat that failed to resolve is red in a terminal that supports it and
     * still legible in one that does not.
     *
     * @param logger from {@code plugin.getComponentLogger()}
     */
    static @NotNull SeatChartSink component(@NotNull ComponentLogger logger) {
        return new SeatChartSink() {

            @Override
            public void info(@NotNull Component line) {
                logger.info(line);
            }

            @Override
            public void warn(@NotNull Component line) {
                logger.warn(line);
            }

            @Override
            public @NotNull String toString() {
                return "SeatChartSink[component]";
            }
        };
    }
}
