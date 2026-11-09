package pandastic.studio.models.internal.sim;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Collects whatever got past the rate limiter. */
final class RecordingLogger implements SimulationLogger {

    private final @NotNull List<String> messages = new ArrayList<>();
    private final @NotNull List<Throwable> throwables = new ArrayList<>();

    @Override
    public void error(@NotNull String message, @NotNull Throwable thrown) {
        this.messages.add(message);
        this.throwables.add(thrown);
    }

    @NotNull List<String> messages() {
        return this.messages;
    }

    @NotNull List<Throwable> throwables() {
        return this.throwables;
    }

    int count() {
        return this.messages.size();
    }

    @NotNull String last() {
        return this.messages.get(this.messages.size() - 1);
    }
}
