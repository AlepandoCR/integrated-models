package pandastic.studio.models.internal.nms;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Applying a decided movement to a live entity, and answering who the server thinks is driving it.
 *
 * <h2>Why applying movement is its own seam</h2>
 * Because there are several ways to move an entity and they are not interchangeable. Setting velocity,
 * driving the travel vector, writing a position in place and teleporting all produce different
 * client-side interpolation and different collision behaviour, and the one that is correct depends on
 * whether the model is walking, being ridden, or being corrected after a desync. A single
 * {@code moveTo(...)} on {@link NavigationBridge} would hide that choice behind a method name that does
 * not mention it.
 *
 * <h2>The constraint that drives it</h2>
 * The visual model and the entity are two different things. The entity carries the hitbox, the collision
 * and the position the server replicates; the model is display entities posed on top of it. A movement
 * applied here changes what the pose is layered onto, so it has to be applied <em>before</em> the pose
 * for that step is emitted, or the two disagree by one frame and the model appears to drag behind its own
 * hitbox. That ordering is a property of the pipeline, not of this interface, which is why the seam only
 * applies movement and never decides when.
 *
 * <h2>Why the authority question lives here too</h2>
 * {@link #isClientSteered(Entity, Player)} looks like a different subject and is the same one. Whether a
 * movement written here <em>survives</em> depends entirely on whether the server has handed this
 * vehicle's simulation to its rider's client: if it has, the client keeps sending positions, the server
 * accepts them, and every delta applied through this seam is overwritten within a tick. There is no way
 * to detect that from the outside — the symptom is rubber-banding, not an error — and the only API that
 * can answer it is the one behind this seam. So the question belongs beside the operation whose
 * correctness depends on it.
 *
 * <h2>Threading</h2>
 * The owning region's thread only, and this is the seam where getting it wrong is worst: a position write
 * from a foreign region thread is undefined rather than merely racy, and Folia will not stop it. A
 * teleport across a region boundary is additionally asynchronous — it completes on the destination region
 * later — so an implementation must treat "the entity is now there" as something to be observed rather
 * than assumed. Teleporting is deliberately <em>not</em> on this seam: the platform's own
 * {@code teleportAsync} is the supported path, it needs no Mojang mapping, and it is the one operation
 * here that must keep working when no adapter is present at all.
 *
 * <h2>Every method has a default, and that is load-bearing</h2>
 * An adapter compiled before these methods existed must keep compiling and keep loading, because it is
 * the thing that carries the brain layer. So the defaults describe a bridge that supports none of this:
 * {@link #supportsLocalMove()} is false, {@link #moveLocal} refuses, and
 * {@link #isClientSteered(Entity, Player)} declines to answer. A caller is required to handle all three,
 * which it has to anyway — the whole bridge can be absent on a Minecraft version no shipped adapter was
 * built for.
 */
public interface MovementBridge {

    /**
     * Whether {@link #moveLocal} does anything on this adapter.
     *
     * <p>Asked once and remembered, not asked per step. A caller that finds it false has to route every
     * displacement through the platform's asynchronous teleport, which works but costs a scheduled
     * transaction per move — so knowing early lets it say so in a log line instead of surprising an
     * operator with the frame rate.
     *
     * @return whether an in-place, collision-aware move is available
     */
    default boolean supportsLocalMove() {
        return false;
    }

    /**
     * Moves {@code entity} by a displacement, with collision.
     *
     * <p>Collision is the point. A write that sets a position puts a vehicle inside a wall and leaves the
     * server to push it out on the next tick, if it ever does; a move that resolves the displacement
     * against the blocks it passes gets the step-up, the slab climb and the wall stop that make a vehicle
     * feel like it is on the ground. That is also why the displacement may be only partly applied — the
     * entity ends up where the world let it go, not where it was asked to.
     *
     * <p><strong>Legal only inside the chunks the calling region already owns.</strong> A ticking region
     * cannot expand its owned chunk set mid-tick, so a caller has to decide beforehand that the
     * displacement is small enough; this seam cannot make that decision because the threshold is
     * configuration.
     *
     * @param entity what to move, owned by the calling thread
     * @param deltaX world x displacement, in blocks
     * @param deltaY world y displacement, in blocks
     * @param deltaZ world z displacement, in blocks
     * @return whether the move was applied at all. {@code false} from an adapter that does not support
     *         it, and from one that found the entity gone
     */
    default boolean moveLocal(
            @NotNull Entity entity,
            double deltaX,
            double deltaY,
            double deltaZ
    ) {
        return false;
    }

    /**
     * Whether the server would let {@code rider}'s client simulate {@code vehicle}'s movement.
     *
     * <p>The single most consequential question in the vehicle layer. A {@code true} answer means the
     * server treats the rider as the vehicle's controlling passenger, which means the client runs the
     * physics, sends the resulting positions, and the server validates rather than decides. A
     * server-authoritative vehicle on such a root entity cannot be made to work: every displacement the
     * server writes is a correction the client has already drawn past, and the result is permanent
     * rubber-banding that looks like a network problem.
     *
     * <p>Answered for a specific rider rather than for the entity alone, because the server's own answer
     * is. A horse is only steered by a passenger that is riding it, is the first passenger, and meets the
     * conditions its class imposes; asking about the type would be asking a different, weaker question.
     *
     * @param vehicle the prospective root entity, owned by the calling thread
     * @param rider   the prospective rider
     * @return whether the client would be authoritative. {@code false} from an adapter that cannot
     *         answer, in which case the caller is on its own — see
     *         {@code pandastic.studio.models.internal.vehicle.ClientSteeredRoots} for the fallback this
     *         project uses and for why it exists
     */
    default boolean isClientSteered(@NotNull Entity vehicle, @NotNull Player rider) {
        return false;
    }
}
