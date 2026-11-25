package pandastic.studio.models.nms;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.internal.nms.BrainBridge;
import pandastic.studio.models.internal.nms.InputCaptureBridge;
import pandastic.studio.models.internal.nms.MovementBridge;
import pandastic.studio.models.internal.nms.NavigationBridge;
import pandastic.studio.models.internal.nms.NmsBridge;
import pandastic.studio.models.internal.nms.RiderInputBridge;

/**
 * The Folia adapter: four seam implementations, held and handed out.
 *
 * <h2>Why the four are separate objects rather than this class implementing all four</h2>
 * The seams are empty in this phase, so collapsing them into one object would cost nothing today and
 * would be very hard to undo later: once a caller has an {@code NmsBridge} that happens to be a
 * {@code BrainBridge}, the brain and movement surfaces share a namespace, share state, and stop
 * being separately replaceable. Keeping them apart now is what lets the AI phase rewrite the
 * movement seam without reading the brain one.
 *
 * <h2>Allocated once, at enable, and immutable</h2>
 * One instance serves every model on the server. The seams hold no per-entity state — a bridge that
 * remembered which mob it last touched would have to be synchronised, and on Folia it would be
 * synchronised across region threads, which is the one thing this project spends its design budget
 * avoiding. Per-entity state belongs above the seam, in the model that owns the entity.
 */
public final class FoliaNmsBridge implements NmsBridge {

    private final @NotNull BrainBridge brains = new FoliaBrainBridge();
    private final @NotNull NavigationBridge navigation = new FoliaNavigationBridge();
    private final @NotNull RiderInputBridge riderInput = new FoliaRiderInputBridge();
    private final @NotNull MovementBridge movement = new FoliaMovementBridge();

    /**
     * Packet-level input capture.
     *
     * <p>One instance for the whole server, like the other four, and for the stated reason — it holds
     * no per-entity state. What it does hold is a map from channel to the handler installed on it,
     * which is per <em>connection</em> rather than per entity and is exactly the thing that cannot be
     * recomputed: a handler has to be removed by identity.
     */
    private final @NotNull InputCaptureBridge inputCapture = new FoliaPacketInputCapture();

    /**
     * Package-private: a bridge is reached through {@link FoliaNmsBridgeProvider}, so that the
     * version check in the lookup cannot be bypassed by constructing one directly.
     */
    FoliaNmsBridge() {
        // Intentionally empty; the seams are final fields.
    }

    @Override
    @NotNull
    public String minecraftVersion() {
        return NmsTarget.MINECRAFT_VERSION;
    }

    @Override
    @NotNull
    public BrainBridge brains() {
        return this.brains;
    }

    @Override
    @NotNull
    public NavigationBridge navigation() {
        return this.navigation;
    }

    @Override
    @NotNull
    public RiderInputBridge riderInput() {
        return this.riderInput;
    }

    @Override
    @NotNull
    public MovementBridge movement() {
        return this.movement;
    }

    @Override
    @NotNull
    public InputCaptureBridge inputCapture() {
        return this.inputCapture;
    }
}
