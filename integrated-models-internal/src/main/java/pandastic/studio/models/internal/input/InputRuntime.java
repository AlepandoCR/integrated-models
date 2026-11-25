package pandastic.studio.models.internal.input;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.input.InputCapability;
import pandastic.studio.models.api.input.PlayerInputController;
import pandastic.studio.models.internal.nms.InputCaptureBridge;
import pandastic.studio.models.internal.nms.NmsBridge;
import pandastic.studio.models.internal.sim.SimulationClock;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The input layer, assembled: the capture sources this server can have, the controller they feed, and
 * the player lifecycle that attaches and forgets.
 *
 * <h2>What it decides</h2>
 * Exactly one thing that nothing else can: which capture sources exist on this server. The platform
 * source always does. The packet source does when there is an NMS adapter whose capture bridge reports
 * itself available <em>and</em> the operator has not switched it off — three conditions with three
 * different operator responses, which is why the reason is a sentence rather than a boolean.
 *
 * <p>Everything downstream is then a function of the resulting capability set:
 * {@link PlayerInputController#capabilities()} publishes it, {@link PlayerInputController#bind} checks
 * a binding against it, and {@link #unavailableReason()} is what {@link #start()} logs once.
 *
 * <h2>Why the degradation is logged once and not refused</h2>
 * Because the half that survives is the larger half. Without the packet source a consumer still sees
 * every movement key, every action input, and can still stop an action; what it loses is stopping a
 * movement key and seeing an input before the server does. Refusing to build the layer over that would
 * take working functionality away to punish a missing optional one — the same judgement the AI layer
 * makes about a missing adapter, and the same shape of answer.
 *
 * <h2>Shutdown</h2>
 * {@link #shutdown()} stops the sources, unregisters the lifecycle listener, and closes every binding.
 * It has to run before the plugin's classloader closes: a packet handler left in a player's pipeline
 * holds our classes, and the first lazy class load after that close fails in a stack trace that points
 * nowhere near here. That is the same hazard {@code ModelRuntime.shutdown()} exists to avoid, and this
 * belongs in the same sequence.
 */
public final class InputRuntime implements Listener {

    private final @NotNull Plugin plugin;
    private final @NotNull ComponentLogger logger;
    private final @NotNull PlayerInputControllerImpl controller;
    private final @NotNull List<InputCaptureSource> sources;
    private final @Nullable String unavailableReason;

    private boolean started;

    private InputRuntime(
            @NotNull Plugin plugin,
            @NotNull ComponentLogger logger,
            @NotNull PlayerInputControllerImpl controller,
            @NotNull List<InputCaptureSource> sources,
            @Nullable String unavailableReason
    ) {
        this.plugin = plugin;
        this.logger = logger;
        this.controller = controller;
        this.sources = List.copyOf(sources);
        this.unavailableReason = unavailableReason;
    }

    /**
     * Assembles the layer against the real server.
     *
     * <p>Nothing is started and no listener is registered; see {@link #start()}. A caller that decides
     * not to enable has nothing to unwind.
     *
     * @param plugin   the plugin whose listeners and schedulers this layer uses
     * @param logger   where the one capability line goes
     * @param clock    the monotonic clock every observation is stamped with
     * @param settings where the tuning, the switches and the limits come from
     * @param bridge   the NMS adapter, or {@code null} when this server has none
     * @return the assembled layer
     */
    public static @NotNull InputRuntime create(
            @NotNull Plugin plugin,
            @NotNull ComponentLogger logger,
            @NotNull SimulationClock clock,
            @NotNull InputSettingsSource settings,
            @Nullable NmsBridge bridge
    ) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(settings, "settings");

        final List<InputCaptureSource> sources = new ArrayList<>(2);
        sources.add(new PlatformInputCaptureSource(plugin, clock, settings));

        final String reason = packetSource(bridge, settings, clock, sources);

        return create(
                plugin,
                logger,
                clock,
                settings,
                sources,
                reason,
                RegionDispatcher.entityScheduler(plugin),
                InputFaultSink.throttled(settings, clock,
                        (message, failure) -> logger.error(
                                Component.text(message, NamedTextColor.RED), failure)));
    }

    /**
     * Assembles the layer over explicit seams.
     *
     * <p>The test seam, and the one place the whole object graph is visible: capture sources, the
     * region hop and the fault policy are all supplied, so the dispatcher, the conflict reporting and
     * the degraded capability set can be exercised with no server anywhere.
     *
     * @param plugin            the plugin the lifecycle listener is registered for
     * @param logger            where the one capability line goes
     * @param clock             the monotonic clock
     * @param settings          where the configuration comes from
     * @param sources           the capture sources, in the order they are started
     * @param unavailableReason the sentence naming what is missing, or {@code null}
     * @param regions           how a handler gets onto the player's own thread
     * @param faults            where a handler's exception goes
     * @return the assembled layer
     */
    public static @NotNull InputRuntime create(
            @NotNull Plugin plugin,
            @NotNull ComponentLogger logger,
            @NotNull SimulationClock clock,
            @NotNull InputSettingsSource settings,
            @NotNull List<InputCaptureSource> sources,
            @Nullable String unavailableReason,
            @NotNull RegionDispatcher regions,
            @NotNull InputFaultSink faults
    ) {
        final EnumSet<InputCapability> capabilities = EnumSet.noneOf(InputCapability.class);
        for (final InputCaptureSource source : sources) {
            capabilities.addAll(source.capabilities());
        }

        final PlayerInputControllerImpl controller = new PlayerInputControllerImpl(
                settings, clock, regions, faults, capabilities, unavailableReason);
        return new InputRuntime(plugin, logger, controller, sources, unavailableReason);
    }

    /**
     * Adds the packet source if this server can have one, and returns the reason if it cannot.
     *
     * <p>Three refusals, three sentences. The operator reading the log needs to know which of them it
     * was, because the response to each is different: rebuild with the module, upgrade or wait for an
     * adapter, or turn the key back on.
     *
     * <p>Package-private rather than private because it is the whole of the degraded-path decision and
     * the only part of this class that can be exercised without a server: a test hands it a null
     * bridge, a declining one and a disabling configuration, and asserts on the three sentences and on
     * whether a source was added.
     */
    static @Nullable String packetSource(
            @Nullable NmsBridge bridge,
            @NotNull InputSettingsSource settings,
            @NotNull SimulationClock clock,
            @NotNull List<InputCaptureSource> sources
    ) {
        if (!settings.current().packetSourceEnabled()) {
            return "Packet-level input capture is switched off in input.yml"
                    + " (packetSourceEnabled: false). Movement and action inputs are still observed"
                    + " through the platform's own api; a movement input cannot be suppressed.";
        }
        if (bridge == null) {
            return "Packet-level input capture is unavailable: this server has no NMS adapter, so"
                    + " either the adapter module was left out of the build"
                    + " (-PintegratedModels.nms=false) or none shipped for this Minecraft version."
                    + " Movement and action inputs are still observed through the platform's own api;"
                    + " a movement input cannot be suppressed.";
        }

        final InputCaptureBridge capture = bridge.inputCapture();
        if (!capture.isAvailable()) {
            return "Packet-level input capture is unavailable: " + capture.describe()
                    + ". Movement and action inputs are still observed through the platform's own api.";
        }

        sources.add(new BridgeInputCaptureSource(capture, clock));
        return null;
    }

    /** What a consumer reaches through the api. */
    public @NotNull PlayerInputController controller() {
        return this.controller;
    }

    /**
     * Why this server's input capture is less than complete, if it is.
     *
     * @return the sentence, or {@code null} when nothing is missing
     */
    public @Nullable String unavailableReason() {
        return this.unavailableReason;
    }

    /** What the started sources between them can do. */
    public @NotNull Set<InputCapability> capabilities() {
        return this.controller.capabilities();
    }

    /**
     * Starts every source, registers the player lifecycle, and says once what this server can do.
     *
     * <p>Attaching the players who are <em>already</em> online is not an edge case: a plugin that is
     * reloaded, or installed with players on, would otherwise capture nothing from them until they
     * rejoined. Each attach is dispatched on that player's own entity scheduler, because reaching a
     * player's connection goes through the player and a player may only be touched from the region
     * that owns it.
     */
    public synchronized void start() {
        if (this.started) {
            return;
        }
        this.started = true;

        for (final InputCaptureSource source : this.sources) {
            source.start(this.controller);
        }
        this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);

        for (final Player player : this.plugin.getServer().getOnlinePlayers()) {
            player.getScheduler().run(this.plugin, ignored -> attach(player), null);
        }

        final StringBuilder line = new StringBuilder("Input capture: ");
        for (int index = 0; index < this.sources.size(); index++) {
            if (index > 0) {
                line.append(" + ");
            }
            line.append(this.sources.get(index).name());
        }
        this.logger.info(Component.text(line.toString()));

        // Logged once, and as a warning rather than an error: what is missing is a capability, not a
        // fault, and an operator asking why a movement key cannot be suppressed has no other way to
        // find out which of the three reasons it was.
        if (this.unavailableReason != null) {
            this.logger.warn(Component.text(this.unavailableReason, NamedTextColor.YELLOW));
        }
    }

    /**
     * Stops every source, unregisters the lifecycle listener, and closes every binding.
     *
     * <p>Idempotent, and does not throw. Must run before the plugin's classloader closes; see the
     * class note.
     *
     * @return how many bindings were closed
     */
    public synchronized int shutdown() {
        if (!this.started) {
            return 0;
        }
        this.started = false;

        HandlerList.unregisterAll(this);
        for (final InputCaptureSource source : this.sources) {
            source.stop();
        }
        return this.controller.shutdown();
    }

    /**
     * A player became observable.
     *
     * <p>{@link EventPriority#MONITOR} because this changes nothing about the join and wants to run
     * after anything that might cancel the player's arrival in some other sense. Attaching is
     * idempotent per source, which is what makes a double join — a relog inside one tick — harmless.
     *
     * @param event the join
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        attach(event.getPlayer());
    }

    /**
     * A player left.
     *
     * <p>Both halves matter. Detaching removes the packet handler, without which a disconnecting
     * player's pipeline keeps a reference to our classes. Forgetting drops the published state, which
     * would otherwise hold whatever was down at the moment the connection dropped — a client that goes
     * away mid-key never sends the release.
     *
     * @param event the quit
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        for (final InputCaptureSource source : this.sources) {
            source.detach(player);
        }
        this.controller.forget(player.getUniqueId());
    }

    private void attach(@NotNull Player player) {
        for (final InputCaptureSource source : this.sources) {
            source.attach(player);
        }
    }
}
