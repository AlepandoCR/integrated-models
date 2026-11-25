package pandastic.studio.models.internal.spi;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@link ModelRenderBackendLookup} found, together with enough of what it did not find to explain
 * itself.
 *
 * <h2>Why the misses are part of the value</h2>
 * "No rendering backend" has three causes that look identical from the outside and need three different
 * responses: no backend module was built into the jar at all, a backend shipped and failed to link
 * because the renderer plugin it compiles against is not installed, or a backend shipped and threw while
 * constructing itself. A bare {@link Optional} collapses all three, and the operator who then asks why
 * nothing renders gets no better answer than the log line gave. Carrying the rejections costs one list
 * per server start and turns that question into a readable sentence.
 *
 * <p>Immutable, platform-free and allocated once at enable, so it is safe to keep and to log from
 * anywhere.
 *
 * @param backend      the chosen backend, or {@code null} when none could be built
 * @param rendererName the renderer the chosen backend drives, or {@code null} when there is none
 * @param rejections   one human-readable line per provider that was seen and not used, in discovery
 *                     order
 */
public record ModelRenderBackendResolution(
        @Nullable ModelRenderBackend backend,
        @Nullable String rendererName,
        @NotNull List<String> rejections) {

    /**
     * Copies the rejection list defensively: the caller that built it keeps a mutable {@code ArrayList}
     * while it is collecting, and this value outlives that loop.
     */
    public ModelRenderBackendResolution {
        rejections = List.copyOf(Objects.requireNonNull(rejections, "rejections"));
    }

    /** Whether a backend was found and built. */
    public boolean isPresent() {
        return this.backend != null;
    }

    /**
     * The backend, if any.
     *
     * <p>Separate from {@link #backend()} rather than replacing it, because a record's accessor has to
     * return the component's own type for the pattern-matching and {@code equals} contracts to stay
     * honest.
     */
    public @NotNull Optional<ModelRenderBackend> asOptional() {
        return Optional.ofNullable(this.backend);
    }

    /**
     * The backend, or a failure carrying {@link #describe()}.
     *
     * <p>Unlike the NMS seam's equivalent, <strong>plugin enable does use this</strong> — indirectly,
     * through {@link ModelRenderBackends#require()}. There is no degraded mode: a model plugin that
     * cannot render is not a reduced feature set, it is the whole feature set. Enable checks
     * {@link #isPresent()} first precisely so that the operator reads {@link #describe()} once, at
     * startup, rather than this exception from inside whichever call site happened to come first.
     *
     * @throws IllegalStateException if no backend was built
     */
    public @NotNull ModelRenderBackend require() {
        final ModelRenderBackend found = this.backend;
        if (found == null) {
            throw new IllegalStateException(describe());
        }
        return found;
    }

    /**
     * One line, suitable for a log message, naming what was found or every provider that was passed over
     * and why.
     */
    public @NotNull String describe() {
        final StringBuilder message = new StringBuilder();
        if (this.backend != null) {
            message.append("Rendering backend active: ").append(this.rendererName);
        } else if (this.rejections.isEmpty()) {
            message.append("No rendering backend was found. No ")
                    .append(ModelRenderBackendProvider.class.getName())
                    .append(" is on the classpath at all, which means this jar was assembled without a ")
                    .append("backend module — the shaded plugin jar is built by ")
                    .append("integrated-models-bettermodel, so a jar produced by any other module will ")
                    .append("look exactly like this. Integrated Models cannot render anything and will ")
                    .append("not enable");
        } else {
            message.append("No rendering backend could be built, so Integrated Models cannot render ")
                    .append("anything and will not enable");
        }
        if (!this.rejections.isEmpty()) {
            message.append(". Passed over: ").append(String.join("; ", this.rejections));
        }
        return message.toString();
    }
}
