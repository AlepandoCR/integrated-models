package pandastic.studio.models.internal.input;

import org.bukkit.Input;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.input.InputCapability;
import pandastic.studio.models.api.input.InputDispatch;
import pandastic.studio.models.api.input.InputKey;
import pandastic.studio.models.internal.sim.SimulationClock;

import java.util.Objects;
import java.util.Set;

/**
 * The capture source that needs nothing but the platform's own api.
 *
 * <h2>Why this is the default and not the fallback</h2>
 * Because for the movement family it is not a degraded anything: the client sends its movement intent
 * as a bitfield, the server retains the latest value on the player, and the platform publishes both a
 * change notification and an accessor for the retained value. A packet interceptor sees exactly the
 * same seven bits. What it does not give is cancellation and earliness, and those are the only two
 * reasons the other source exists.
 *
 * <h2>What it observes, and what it can stop</h2>
 * <table>
 *   <caption>Capability by input</caption>
 *   <tr><th>Input</th><th>Platform notification</th><th>Can this source stop it</th></tr>
 *   <tr><td>the seven movement keys</td><td>the input change event</td>
 *       <td><strong>no</strong> — that event is not cancellable</td></tr>
 *   <tr><td>attack</td><td>the interact event, left click</td><td>yes</td></tr>
 *   <tr><td>use</td><td>the interact and interact-entity events, right click</td><td>yes</td></tr>
 *   <tr><td>drop</td><td>the drop event</td><td>yes</td></tr>
 *   <tr><td>swap hands</td><td>the swap event</td><td>yes</td></tr>
 *   <tr><td>hotbar slot</td><td>the item-held event</td><td>yes</td></tr>
 * </table>
 *
 * <p>So the asymmetry is the opposite of the obvious guess: the family that needs server internals to
 * be <em>suppressed</em> is the one the platform exposes most directly, and the family the platform
 * exposes only as five unrelated events is the one it lets us cancel. Both halves of that are why
 * {@link InputCapability} is declared per family per verb rather than as one "has NMS" flag.
 *
 * <h2>Why {@link EventPriority#LOWEST} and {@code ignoreCancelled = false}</h2>
 * Lowest, because a handler that suppresses has to decide before anything downstream reacts to the
 * input; a monitor-priority listener could observe and not cancel. Not ignoring cancelled events,
 * because a cancelled action still happened as an <em>input</em> — the player pressed the button — and
 * a control scheme reading "did the rider click" should not depend on whether an unrelated protection
 * plugin allowed the click to have an effect.
 *
 * <h2>Threading</h2>
 * Every event here fires on the thread that owns the player, so every delivery is
 * {@link InputDispatch#PLAYER_REGION} and a handler may touch that player inline. That is the whole
 * reason this source needs no argument about regions: the platform has already put us on the right
 * thread.
 */
public final class PlatformInputCaptureSource implements InputCaptureSource, Listener {

    private final @NotNull Plugin plugin;
    private final @NotNull SimulationClock clock;
    private final @NotNull InputSettingsSource settings;

    private volatile @Nullable InputSink sink;

    /**
     * Builds the source.
     *
     * <p>Registers nothing: {@link #start(InputSink)} does, so an assembled but unstarted runtime has
     * no listener to unwind.
     *
     * @param plugin   the plugin the listener is registered for
     * @param clock    the monotonic clock an observation is stamped with
     * @param settings where the action-capture switch is read from, per observation, because an
     *                 operator switching it off must take effect without a restart
     */
    public PlatformInputCaptureSource(
            @NotNull Plugin plugin,
            @NotNull SimulationClock clock,
            @NotNull InputSettingsSource settings
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * The movement bits {@code input} describes, in {@link InputKey}'s layout.
     *
     * <p>The one translation in the whole input layer, and it is here on purpose: everything above it
     * is a function of our own bitfield, so a control scheme can be exercised without a platform
     * implementation of the seven-accessor interface.
     *
     * @param input the platform's retained input
     * @return the packed bits
     */
    public static int movementBits(@NotNull Input input) {
        Objects.requireNonNull(input, "input");
        int bits = 0;
        if (input.isForward()) {
            bits |= InputKey.FORWARD.mask();
        }
        if (input.isBackward()) {
            bits |= InputKey.BACKWARD.mask();
        }
        if (input.isLeft()) {
            bits |= InputKey.LEFT.mask();
        }
        if (input.isRight()) {
            bits |= InputKey.RIGHT.mask();
        }
        if (input.isJump()) {
            bits |= InputKey.JUMP.mask();
        }
        if (input.isSneak()) {
            bits |= InputKey.SNEAK.mask();
        }
        if (input.isSprint()) {
            bits |= InputKey.SPRINT.mask();
        }
        return bits;
    }

    @Override
    public @NotNull String name() {
        return "platform events";
    }

    @Override
    public @NotNull Set<InputCapability> capabilities() {
        return Set.of(
                InputCapability.OBSERVE_MOVEMENT,
                InputCapability.OBSERVE_ACTIONS,
                InputCapability.SUPPRESS_ACTIONS);
    }

    @Override
    public void start(@NotNull InputSink target) {
        this.sink = Objects.requireNonNull(target, "sink");
        this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(this);
        this.sink = null;
    }

    /**
     * Reads the retained input for a player who has just become observable.
     *
     * <p>The one place the retained accessor is used rather than the change notification, and it is
     * necessary: the notification only fires on a <em>change</em>, so a player who joins already
     * holding forward would not be observed until they let go. Must run on that player's own region
     * thread, which is where a join handler already is.
     *
     * @param player the player to sync
     */
    @Override
    public void attach(@NotNull Player player) {
        final InputSink target = this.sink;
        if (target == null) {
            return;
        }
        target.movement(
                InputDelivery.observing(player, this.clock.nanoTime(), InputDispatch.PLAYER_REGION),
                movementBits(player.getCurrentInput()));
    }

    // -------------------------------------------------------------------- movement

    /**
     * The seven held keys changed.
     *
     * <p>Not cancellable, so the delivery carries no suppressor. A binding that declared suppression
     * for a movement key was told so at registration.
     *
     * @param event the platform's input change
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInput(@NotNull PlayerInputEvent event) {
        final InputSink target = this.sink;
        if (target == null) {
            return;
        }
        target.movement(
                InputDelivery.observing(
                        event.getPlayer(), this.clock.nanoTime(), InputDispatch.PLAYER_REGION),
                movementBits(event.getInput()));
    }

    // --------------------------------------------------------------------- actions

    /**
     * A click at air or at a block.
     *
     * <p>Both halves of the mouse arrive here: left click is {@link InputKey#ATTACK}, right click is
     * {@link InputKey#USE}. The physical action — stepping on a pressure plate — is not an input and is
     * ignored.
     *
     * @param event the platform's interact event
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        final InputKey key = switch (event.getAction()) {
            case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> InputKey.ATTACK;
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> InputKey.USE;
            default -> null;
        };
        if (key != null) {
            pulse(event.getPlayer(), key, event);
        }
    }

    /** A right click on an entity, which is a {@link InputKey#USE} that the interact event does not carry. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(@NotNull PlayerInteractEntityEvent event) {
        pulse(event.getPlayer(), InputKey.USE, event);
    }

    /** The drop key. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(@NotNull PlayerDropItemEvent event) {
        pulse(event.getPlayer(), InputKey.DROP_ITEM, event);
    }

    /** The swap-offhand key. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHands(@NotNull PlayerSwapHandItemsEvent event) {
        pulse(event.getPlayer(), InputKey.SWAP_HANDS, event);
    }

    /**
     * The selected hotbar slot moved.
     *
     * <p>Carries the new slot as well as the moment, which is why it does not go through
     * {@link #pulse}: both halves have to land in one publication so a handler reading the slot off the
     * snapshot cannot see the pulse before the number.
     *
     * @param event the platform's item-held event
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onItemHeld(@NotNull PlayerItemHeldEvent event) {
        final InputSink target = this.sink;
        if (target == null || !this.settings.current().actionCaptureEnabled()) {
            return;
        }
        target.hotbarSlot(delivery(event.getPlayer(), event), event.getNewSlot());
    }

    /** Pushes one momentary input whose platform event is cancellable. */
    private void pulse(@NotNull Player player, @NotNull InputKey key, @NotNull Cancellable event) {
        final InputSink target = this.sink;
        if (target == null || !this.settings.current().actionCaptureEnabled()) {
            return;
        }
        target.pulse(delivery(player, event), key);
    }

    /**
     * A delivery whose suppressor cancels {@code event}.
     *
     * <p>The suppressor answers {@code true} unconditionally, which is honest here in a way it would
     * not be for a packet: cancelling a platform event always takes effect from the platform's side.
     * Whether a <em>later</em> listener un-cancels it is outside anything this source can promise, and
     * running at the lowest priority is what makes that unlikely rather than what makes it impossible.
     */
    private @NotNull InputDelivery delivery(@NotNull Player player, @NotNull Cancellable event) {
        return new InputDelivery(player, this.clock.nanoTime(), InputDispatch.PLAYER_REGION, () -> {
            event.setCancelled(true);
            return true;
        });
    }
}
