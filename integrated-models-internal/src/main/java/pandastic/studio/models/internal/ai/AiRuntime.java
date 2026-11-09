package pandastic.studio.models.internal.ai;

import org.bukkit.entity.Mob;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.ai.CreatureAi;
import pandastic.studio.models.internal.nms.NmsBridge;
import pandastic.studio.models.internal.nms.NmsBridgeResolution;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The AI layer, assembled, or the reason there is none.
 *
 * <h2>Why this exists as one object</h2>
 * Because assembling the layer has an order and a precondition, and both are easy to get wrong from a
 * lifecycle method. The precondition is the NMS adapter: without one there is no AI at all, and that has
 * to be a reported state rather than a failure, because the rendering pipeline does not need NMS and
 * must keep working without it. The order is that the fault sink has to exist before the service, the
 * service before any install, and the whole thing has to be torn down before the plugin's classloader
 * closes.
 *
 * <h2>What a caller has to do, and the one ordering that is not optional</h2>
 * <ol>
 *   <li>Build one of these during enable, after configuration is live, with the resolution the NMS
 *       lookup produced.</li>
 *   <li>Publish {@link #creatureAi()} if it is present, and log {@link #unavailableReason()} once if it
 *       is not. Do not refuse to enable over a missing adapter.</li>
 *   <li><strong>Call {@link #shutdown()} during disable, before the plugin's own tasks are
 *       abandoned.</strong> Every installation holds repeating tasks dispatched through a creature's
 *       scheduler, and those are <em>never</em> cancelled for a plugin on disable — unlike main-scheduler
 *       tasks, which the platform sweeps. A sensor whose handle was dropped keeps running after the
 *       classloader has closed and fails on the first lazy class load after that, during shutdown,
 *       pointing nowhere near the creature that leaked it.</li>
 * </ol>
 *
 * <h2>Why there are two factories</h2>
 * The short one builds the real per-creature host over the creature's own scheduler; the long one takes
 * any host factory. That is the whole test seam of this layer: {@link AiHost} is the only platform
 * surface the AI package has, so substituting it makes every ordering decision in
 * {@link CreatureAiImpl} and {@link AiControllerImpl} — install, teardown, sensor dispatch, who may read
 * a memory — exercisable with no server anywhere.
 *
 * <h2>Nothing here is tunable</h2>
 * Every bound, period and threshold arrives through {@link AiSettingsSource}, read live. This class
 * holds no number.
 */
public final class AiRuntime {

    private final @Nullable CreatureAiImpl creatureAi;
    private final @NotNull String unavailableReason;

    private AiRuntime(@Nullable CreatureAiImpl creatureAi, @NotNull String unavailableReason) {
        this.creatureAi = creatureAi;
        this.unavailableReason = unavailableReason;
    }

    /**
     * Assembles the layer against whatever adapter the lookup found.
     *
     * <p>Never throws and never refuses: a server with no adapter gets a runtime whose
     * {@link #creatureAi()} is empty and whose {@link #unavailableReason()} explains why, which is the
     * state the plugin is written against.
     *
     * @param plugin     the plugin sensor tasks are scheduled under, which is what lets the platform
     *                   attribute and cancel them
     * @param resolution what the NMS lookup found, including why it found nothing
     * @param settings   where the bounds and the fault policy come from, read live
     * @param reporter   where a report that got past the throttle goes. A seam rather than a logger so
     *                   that the throttling is testable and so that this package names no logging type
     * @return the runtime
     */
    public static @NotNull AiRuntime create(
            @NotNull Plugin plugin,
            @NotNull NmsBridgeResolution resolution,
            @NotNull AiSettingsSource settings,
            @NotNull ThrottledFaultSink.Reporter reporter
    ) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(resolution, "resolution");
        return create(plugin, resolution, settings, reporter, mob -> new EntityAiHost(plugin, mob));
    }

    /**
     * Assembles the layer with an explicit host factory.
     *
     * <p>The test seam. Everything about installing, tearing down, dispatching sensors and refusing a
     * memory read can be exercised against a substitute host with no server in sight; see
     * {@link AiHost} for why that one interface is the whole platform surface.
     *
     * @param plugin     unused by the layer itself, and required anyway so that the two factories cannot
     *                   diverge in what they demand of a caller
     * @param resolution what the NMS lookup found
     * @param settings   where the bounds and the fault policy come from
     * @param reporter   where a report that got past the throttle goes
     * @param hosts      how to build the per-creature thread seam
     * @return the runtime
     */
    public static @NotNull AiRuntime create(
            @NotNull Plugin plugin,
            @NotNull NmsBridgeResolution resolution,
            @NotNull AiSettingsSource settings,
            @NotNull ThrottledFaultSink.Reporter reporter,
            @NotNull Function<Mob, AiHost> hosts
    ) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(reporter, "reporter");
        Objects.requireNonNull(hosts, "hosts");

        final NmsBridge bridge = resolution.bridge();
        if (bridge == null) {
            return new AiRuntime(null, resolution.describe());
        }

        // System.nanoTime rather than a clock seam, because this is the one assembled, production path:
        // the seam is on the constructor of the sink itself, which is where a test reaches it.
        final ThrottledFaultSink faults =
                new ThrottledFaultSink(reporter, settings, System::nanoTime);
        return new AiRuntime(
                new CreatureAiImpl(bridge.brains(), settings, faults, hosts),
                "");
    }

    /**
     * The service, if this server has an adapter.
     *
     * <p>Empty is a supported state and not an error; see {@link #unavailableReason()}.
     *
     * @return the service, or empty
     */
    public @NotNull Optional<CreatureAi> creatureAi() {
        return Optional.ofNullable(this.creatureAi);
    }

    /**
     * Why there is no service, as one sentence naming the server version and every adapter that was
     * passed over.
     *
     * <p>Blank when there is one. Worth logging once at enable even then is debatable; worth logging
     * once when there is not is not — an operator asking why the AI layer is off has no other way to
     * find out.
     *
     * @return the explanation, or an empty string
     */
    public @NotNull String unavailableReason() {
        return this.unavailableReason;
    }

    /**
     * Uninstalls every live plan.
     *
     * <p>Must be called during disable. See the class documentation for why the sensor tasks are the
     * part that matters: the platform will not cancel them, and the teardown of each installation
     * cancels its own synchronously, so this returns with nothing of ours still scheduled.
     *
     * @return how many installations were uninstalled
     */
    public int shutdown() {
        return this.creatureAi == null ? 0 : this.creatureAi.uninstallAll();
    }

    @Override
    public @NotNull String toString() {
        return this.creatureAi == null
                ? "AiRuntime[unavailable: " + this.unavailableReason + "]"
                : "AiRuntime[" + this.creatureAi.installationCount() + " installation(s)]";
    }
}
