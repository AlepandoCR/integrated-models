package pandastic.studio.models.internal.sim;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Binds {@link SimulationLogger} to the plugin's Adventure logger.
 *
 * <p>Built from {@code plugin.getComponentLogger()}. One method, no state and no decisions, so that the
 * only part of the reporting path that cannot be unit-tested is the part with nothing in it to test —
 * the rate limiting that actually matters lives in {@link RateLimitedLogger}.
 */
public final class ComponentSimulationLogger implements SimulationLogger {

    private final @NotNull ComponentLogger delegate;

    public ComponentSimulationLogger(@NotNull ComponentLogger delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void error(@NotNull String message, @NotNull Throwable thrown) {
        this.delegate.error(Component.text(message, NamedTextColor.RED), thrown);
    }
}
