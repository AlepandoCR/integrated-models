package pandastic.studio.models.api.decoration;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.binding.RiggedModel;

import java.util.UUID;

/**
 * A rigged model with nothing underneath it: a lantern, a banner, a statue.
 *
 * <h2>The cheapest rig there is, and why it is its own type</h2>
 * A decoration has no entity. That removes the hitbox, the entity tracker, the region ownership that
 * follows an entity around, and — the part that matters at scale — the per-tick read of a position that
 * never changes. What is left is a position this plugin owns outright, a set of viewers, and the ambient
 * field. Three hundred of these are meant to be free, and on a resting decoration they are: a settled
 * decoration emits no packets at all, for as long as nothing moves it.
 *
 * <p>It is a subinterface of {@link RiggedModel} rather than a separate hierarchy because the lifecycle
 * is genuinely the same one — bind, spawn once, despawn once, idempotently, from any thread — and a
 * plugin holding a mixed collection of models should not have to know which kind each is to shut them
 * down. What it adds is the three things an entity would otherwise have provided: a position somebody
 * has to set, a viewer list somebody has to manage, and a reason to move.
 *
 * <h2>Invisible until somebody is shown it</h2>
 * An entity-backed model inherits the server's own view tracking. A decoration has none, so it starts
 * out spawned and seen by nobody, and {@link #show(UUID)} is what puts it on a client. That is a feature
 * rather than an omission: it is what lets a decoration be shown to one player, and it is the seam a
 * distance culler is meant to drive.
 *
 * <p><strong>No culler drives it yet.</strong> The decision layer exists and is tested, but nothing in
 * this plugin runs it: there is no culling pass and no viewer sampler on a running server. So today the
 * only way anyone sees a decoration is a consumer calling {@link #show(UUID)} itself, and a consumer
 * that wants "everyone nearby sees it" has to work out "nearby" on its own. That is a gap being closed,
 * not the intended end state — but it is what is true now, and a decoration that nobody shows to anybody
 * is invisible rather than broken.
 *
 * <h2>Thread rules</h2>
 * <ul>
 *   <li>{@link #spawn()} must run on the thread that owns the <em>placement's</em> region. Creating the
 *       renderer-side binding fires a platform event that listeners are entitled to handle on a region
 *       thread, and the region a position belongs to is a property of its chunk — which is why the rule
 *       is about the placement rather than about the decoration.</li>
 *   <li>{@link #moveTo(DecorationPlacement)}, {@link #show(UUID)}, {@link #hide(UUID)},
 *       {@link #setAmbientMotion} and every getter are safe from any thread. They write a value or send
 *       packets, and neither is region state.</li>
 *   <li>{@link #despawn()} is safe from any thread, any number of times, exactly as on
 *       {@link RiggedModel}.</li>
 * </ul>
 */
public interface Decoration extends RiggedModel {

    /**
     * The world this decoration stands in, fixed for its whole life.
     *
     * <p>A decoration cannot be moved across worlds: the renderer's registry and the set of players who
     * could see it are both per-world, so that is a new decoration rather than a new position. Binding
     * again is the supported way, and it is cheap.
     *
     * @return the world's unique id
     */
    @NotNull UUID getWorldId();

    /**
     * Where this decoration currently stands.
     *
     * <p>Safe from any thread, and never a live world read — it is the value this plugin was last told,
     * which is the only position a decoration has.
     *
     * @return the current placement
     */
    @NotNull DecorationPlacement getPlacement();

    /**
     * Moves this decoration.
     *
     * <p>Safe from any thread and from any region: moving a decoration is a packet to whoever can see it
     * and a store into a field, neither of which is region state. A decoration that has not spawned
     * records the placement and spawns there.
     *
     * <p>The move is also <strong>motion</strong>. The difference between two placements is published to
     * the physics the same way an entity's own movement is, so the bones of a carried lantern swing; a
     * jump too large to be real movement is filtered out by the same teleport thresholds the entity path
     * uses, so a one-off reposition does not launch every bone at once.
     *
     * @param placement where to move to
     */
    void moveTo(@NotNull DecorationPlacement placement);

    /**
     * {@link #moveTo(DecorationPlacement)} from a platform location.
     *
     * <p>A convenience, and the place the world check lives: a location in another world is refused
     * here rather than silently teleporting the decoration's coordinates while leaving its renderer in
     * the world it was placed in.
     *
     * @param location where to move to; must be in {@link #getWorldId()}'s world
     * @throws IllegalArgumentException if {@code location} has no world, or a different one from this
     *                                 decoration's
     */
    default void moveTo(@NotNull Location location) {
        final World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException(
                    "A decoration cannot be moved to a location with no world");
        }
        if (!world.getUID().equals(getWorldId())) {
            throw new IllegalArgumentException(
                    "A decoration cannot change world: this one is in " + getWorldId() + " and the"
                            + " location is in " + world.getUID()
                            + ". Bind a new decoration in the other world instead.");
        }
        moveTo(new DecorationPlacement(location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch()));
    }

    /**
     * Spawns this decoration's bones for one viewer and sends their complete current state.
     *
     * <p>A full send rather than a resume, because bone transforms go out as deltas against what was
     * last emitted: a viewer who merely starts receiving updates keeps whatever pose they never
     * received, and nothing repairs it. Showing is therefore not free, which is why it is explicit.
     *
     * @param viewer the player to show it to
     * @return {@code true} if this was a change — {@code false} if they already had it, if the
     *         decoration is not spawned, or if the player is not online
     */
    boolean show(@NotNull UUID viewer);

    /**
     * {@link #show(UUID)} for a player already in hand.
     *
     * @param viewer the player to show it to
     * @return whether this was a change
     */
    default boolean show(@NotNull Player viewer) {
        return show(viewer.getUniqueId());
    }

    /**
     * Despawns this decoration's bones for one viewer, after which they cost that viewer nothing.
     *
     * @param viewer the player to take it away from
     * @return {@code true} if this was a change
     */
    boolean hide(@NotNull UUID viewer);

    /**
     * {@link #hide(UUID)} for a player already in hand.
     *
     * @param viewer the player to take it away from
     * @return whether this was a change
     */
    default boolean hide(@NotNull Player viewer) {
        return hide(viewer.getUniqueId());
    }

    /**
     * Whether one viewer currently holds this decoration's bones.
     *
     * @param viewer the player to ask about
     * @return whether they have been shown it and not hidden from it
     */
    boolean isShownTo(@NotNull UUID viewer);

    /**
     * How exposed this decoration is to the ambient field.
     *
     * @return the current exposure, {@link AmbientMotion#NONE} for a decoration nothing drives
     */
    @NotNull AmbientMotion getAmbientMotion();

    /**
     * Changes how exposed this decoration is to the ambient field.
     *
     * <p>Takes effect on the next pass of the ambient driver rather than instantly, which is the same
     * latency an entity-backed model's movement has, and for the same reason: the driver is sampled on
     * its own schedule rather than written by whoever changed it.
     *
     * <p>Setting {@link AmbientMotion#NONE} lets the decoration settle and go silent; setting anything
     * else starts it moving, and a moving decoration costs packets for as long as it moves.
     *
     * @param motion the exposure to apply
     */
    void setAmbientMotion(@NotNull AmbientMotion motion);
}
