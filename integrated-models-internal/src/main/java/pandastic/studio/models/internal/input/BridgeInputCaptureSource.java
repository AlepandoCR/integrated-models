package pandastic.studio.models.internal.input;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.input.InputCapability;
import pandastic.studio.models.api.input.InputDispatch;
import pandastic.studio.models.internal.nms.InputCaptureBridge;
import pandastic.studio.models.internal.sim.SimulationClock;

import java.util.Objects;
import java.util.Set;

/**
 * The capture source that reaches the client's movement packet, wrapped so nothing above it names the
 * server-internals seam.
 *
 * <h2>Why a wrapper rather than the bridge being a source</h2>
 * Because the bridge lives below the NMS seam and this is where the decisions live. The bridge knows
 * how to put a handler on a connection; it does not know what a binding is, what a snapshot is, or
 * what the operator configured — and it must not, because nothing below that seam reads configuration.
 * So the bridge calls back with seven bits and a player, and everything that happens next happens here.
 *
 * <h2>What it adds, and what it does not</h2>
 * It adds {@link InputCapability#SUPPRESS_MOVEMENT} and
 * {@link InputCapability#OBSERVE_BEFORE_SERVER}. It deliberately does <em>not</em> claim
 * {@link InputCapability#OBSERVE_MOVEMENT} as its own contribution — it does observe movement, and so
 * does the platform source, so claiming it would make the capability set unable to distinguish "this
 * server can suppress" from "this server can see". The union of the two sources' sets is what the
 * controller publishes, and the platform source is always in it.
 *
 * <p>Both sources therefore see every movement change. That costs one state comparison per change:
 * whichever arrives second finds the published state unchanged and publishes nothing. The packet
 * handler is always the one that arrives first, since it runs before the server has even applied the
 * input.
 *
 * <h2>Threading</h2>
 * {@link #attach(Player)} runs on the thread that owns the player. The callback runs on that player's
 * network thread, so every delivery it makes is {@link InputDispatch#NETWORK} — a handler reached that
 * way may read the snapshot and nothing about the world. That is not a limitation this class could
 * paper over: it is the thread the packet arrived on.
 */
public final class BridgeInputCaptureSource implements InputCaptureSource {

    private final @NotNull InputCaptureBridge bridge;
    private final @NotNull SimulationClock clock;

    private volatile @Nullable InputSink sink;

    /**
     * Wraps {@code bridge}.
     *
     * @param bridge the server-internals capture seam; may be a declining one, in which case this
     *               source should not have been built — see
     *               {@link InputRuntime} for where that is decided
     * @param clock  the monotonic clock an observation is stamped with
     */
    public BridgeInputCaptureSource(
            @NotNull InputCaptureBridge bridge,
            @NotNull SimulationClock clock
    ) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public @NotNull String name() {
        return "connection packets";
    }

    @Override
    public @NotNull Set<InputCapability> capabilities() {
        return Set.of(InputCapability.SUPPRESS_MOVEMENT, InputCapability.OBSERVE_BEFORE_SERVER);
    }

    @Override
    public void start(@NotNull InputSink target) {
        this.sink = Objects.requireNonNull(target, "sink");
    }

    @Override
    public void stop() {
        this.sink = null;
    }

    @Override
    public void attach(@NotNull Player player) {
        Objects.requireNonNull(player, "player");
        this.bridge.attach(player, this::onMovementInput);
    }

    @Override
    public void detach(@NotNull Player player) {
        this.bridge.detach(Objects.requireNonNull(player, "player"));
    }

    /**
     * The network-thread callback.
     *
     * <p>The suppressor is {@code () -> true} because the answer this method returns <em>is</em> the
     * suppression: the bridge drops the packet when it gets {@code true} back, so a handler's request
     * is honoured by definition rather than attempted and reported. That is the one place in the input
     * layer where suppression is unconditional, and it is why this source may declare the capability.
     *
     * @return whether a handler asked for the packet to be dropped
     */
    private boolean onMovementInput(@NotNull Player player, int movementBits) {
        final InputSink target = this.sink;
        if (target == null) {
            // Stopped between the connection's last packet and this one. Not an error: the handler is
            // removed per player and a packet already in the pipeline still arrives.
            return false;
        }
        return target.movement(
                new InputDelivery(player, this.clock.nanoTime(), InputDispatch.NETWORK, () -> true),
                movementBits);
    }
}
