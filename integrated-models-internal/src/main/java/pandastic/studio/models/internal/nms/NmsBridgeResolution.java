package pandastic.studio.models.internal.nms;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@link NmsBridgeLookup} found, together with enough of what it did not find to explain
 * itself.
 *
 * <h2>Why the misses are part of the value</h2>
 * "No NMS adapter" has three causes that look identical from the outside and need three different
 * responses: the module was excluded from the build, an adapter shipped but is for another
 * Minecraft version, or an adapter shipped for the right version and failed to link. Returning a
 * bare {@link Optional} collapses them, and the operator who then asks why the AI layer is off gets
 * no better answer than the log line gave. Carrying the rejections costs one list per server start
 * and turns that question into a readable sentence.
 *
 * <p>Immutable, platform-free and allocated once at enable, so it is safe to keep and to log from
 * anywhere.
 *
 * @param bridge           the chosen adapter, or {@code null} when none matched
 * @param minecraftVersion the version the lookup was asked about, as the server reported it
 * @param rejections       one human-readable line per provider that was seen and not chosen, in
 *                         discovery order
 */
public record NmsBridgeResolution(
        @Nullable NmsBridge bridge,
        @NotNull String minecraftVersion,
        @NotNull List<String> rejections) {

    /**
     * Copies the rejection list defensively: the caller that built it keeps a mutable
     * {@code ArrayList} while it is collecting, and this value outlives that loop.
     */
    public NmsBridgeResolution {
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        rejections = List.copyOf(Objects.requireNonNull(rejections, "rejections"));
    }

    /** Whether an adapter was found for the requested version. */
    public boolean isPresent() {
        return this.bridge != null;
    }

    /**
     * The adapter, if any.
     *
     * <p>Separate from {@link #bridge()} rather than replacing it, because a record's accessor has
     * to return the component's own type for the pattern-matching and {@code equals} contracts to
     * stay honest.
     */
    @NotNull
    public Optional<NmsBridge> asOptional() {
        return Optional.ofNullable(this.bridge);
    }

    /**
     * The adapter, or a failure carrying {@link #describe()}.
     *
     * <p>For the one caller that genuinely cannot continue — a command or an explicit AI request.
     * Plugin enable must not use it: the rendering pipeline does not need NMS, so refusing to enable
     * over a missing adapter would take down working functionality to punish a missing optional one.
     *
     * @throws IllegalStateException if no adapter matched
     */
    @NotNull
    public NmsBridge require() {
        final NmsBridge found = this.bridge;
        if (found == null) {
            throw new IllegalStateException(describe());
        }
        return found;
    }

    /**
     * One line, suitable for a log message, naming the version asked about and every provider that
     * was passed over and why.
     */
    @NotNull
    public String describe() {
        final StringBuilder message = new StringBuilder();
        if (this.bridge != null) {
            message.append("NMS adapter active for Minecraft ").append(this.minecraftVersion);
        } else if (this.rejections.isEmpty()) {
            message.append("No NMS adapter was found for Minecraft ")
                    .append(this.minecraftVersion)
                    .append(": no provider is on the classpath at all, so the NMS module was most ")
                    .append("likely left out of the build (-PintegratedModels.nms=false). ")
                    .append("Model rendering is unaffected; the AI layer is unavailable");
        } else {
            message.append("No NMS adapter matches Minecraft ")
                    .append(this.minecraftVersion)
                    .append(". Model rendering is unaffected; the AI layer is unavailable");
        }
        if (!this.rejections.isEmpty()) {
            message.append(". Passed over: ").append(String.join("; ", this.rejections));
        }
        return message.toString();
    }
}
