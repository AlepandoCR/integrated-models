package pandastic.studio.models.internal.nms;

import org.bukkit.Input;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.vehicle.RiderIntent;

/**
 * Reading what the controlling player is asking a ridden model to do.
 *
 * <h2>Why this is not a Bukkit-level concern</h2>
 * The client sends its movement intent — forward, strafe, jump, sneak, sprint — as player input, and the
 * server keeps the latest value on the player. There is no event for "the rider is holding W": the input
 * arrives every client tick, and the event the platform does publish is edge-triggered, so a control
 * scheme built on it has to reconstruct held state from transitions and is wrong forever the first time
 * it misses one. Reading the retained value is both cheaper and the only way to see a held key rather
 * than a key transition.
 *
 * <h2>The fact that shapes it</h2>
 * Rider input is <em>intent</em>, not motion. It says what the player wants, while the steering scheme,
 * the solver and the gait above the seam decide what the model actually does with it. So this seam is
 * read-only and returns the seven flags as an integer: anything that turns intent into displacement
 * belongs to {@link MovementBridge}, anything that turns it into a stride length belongs to the
 * locomotion layer, and both of those must stay free of platform types.
 *
 * <p>An integer rather than a value object, because an adapter sees this module's interfaces through a
 * {@code compileOnly} dependency and therefore cannot see the api module at all — so no method on any of
 * these seams may name an api type in its signature. The masks the body below uses are
 * {@link RiderIntent}'s, which is legal exactly because they are compile-time constants: javac inlines
 * them, and the compiled interface carries no reference to the class they came from.
 *
 * <h2>Threading</h2>
 * The value belongs to the player's entity, so it is readable only from the region that owns the player.
 * In the ridden case that is the same region that owns the vehicle, because the platform force-resyncs a
 * passenger that drifts more than a chunk from its root — so a task on the root entity's own scheduler
 * may read it. Nothing else may: the simulation loop runs off the tick and must go through the same
 * immutable publication the pose pipeline's driver sample already uses.
 *
 * <h2>Why the default is a real implementation and not a refusal</h2>
 * This is the one seam in the set whose subject the platform already exposes: the retained input is
 * reachable through an ordinary Bukkit accessor, so the default below <em>is</em> the production
 * behaviour and no adapter has to override it. That is not an argument against the seam. It keeps the
 * caller from naming a platform type on a path that will one day want the Mojang-mapped read — the
 * server keeps the same seven flags in a record it hands out through a wrapper, and an adapter that
 * cares about the per-tick-per-rider allocation can replace the body without the vehicle layer noticing
 * — and it keeps "no adapter at all" a supported state, since a server on an unsupported Minecraft
 * version still has riders.
 */
public interface RiderInputBridge {

    /**
     * The bitfield for a player who is asking for nothing.
     *
     * <p>Structural: it is the empty set of flags, not a chosen value.
     */
    int NO_INPUT = 0;

    /**
     * The seven input flags {@code player} is currently holding, packed as the client's own bitfield.
     *
     * <p>Must be called from the region that owns {@code player}; see the class note.
     *
     * <p>The bit positions are the client packet's own, which is also the order the platform's accessors
     * appear in. {@link RiderIntent} declares them as named masks and is the only thing that decodes
     * this value.
     *
     * @param player whose input to read
     * @return the packed flags, or {@link #NO_INPUT} when the player is asking for nothing
     */
    default int currentInput(@NotNull Player player) {
        final Input input = player.getCurrentInput();
        int bits = NO_INPUT;
        if (input.isForward()) {
            bits |= RiderIntent.FORWARD_BIT;
        }
        if (input.isBackward()) {
            bits |= RiderIntent.BACKWARD_BIT;
        }
        if (input.isLeft()) {
            bits |= RiderIntent.LEFT_BIT;
        }
        if (input.isRight()) {
            bits |= RiderIntent.RIGHT_BIT;
        }
        if (input.isJump()) {
            bits |= RiderIntent.JUMP_BIT;
        }
        if (input.isSneak()) {
            bits |= RiderIntent.SNEAK_BIT;
        }
        if (input.isSprint()) {
            bits |= RiderIntent.SPRINT_BIT;
        }
        return bits;
    }
}
