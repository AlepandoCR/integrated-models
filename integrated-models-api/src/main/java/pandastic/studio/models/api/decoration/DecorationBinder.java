package pandastic.studio.models.api.decoration;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.binding.ModelBinder;
import pandastic.studio.models.api.rig.Rig;

/**
 * How a consumer says "put this rig at this spot, with nothing underneath it".
 *
 * <h2>Why this is not an overload on {@link ModelBinder}</h2>
 * It very nearly is, and the difference is the handle rather than the call. A model attached to nothing
 * needs three capabilities an entity-backed one gets for free from its entity: something has to
 * <em>move</em> it, something has to decide <em>who can see</em> it, and something has to drive it,
 * because an entity that never moves is still a thing the physics can sample while an empty position is
 * not. Those are three methods on {@link Decoration} that would be meaningless on a model bound to an
 * entity — a second handle type, therefore a second binder, rather than an overload whose return type
 * widens by the capabilities only one of the two forms has.
 *
 * <p>The validation is the same validation, and deliberately so: both binders answer "does this rig
 * name bones this model actually has" offline, against the loaded model definition, before anything
 * exists in the world. A rig whose names went stale is a content error and has to surface as one loud
 * message naming every broken bone, not as a decoration that appears and then does not move.
 *
 * <h2>Binding does not place anything</h2>
 * {@link #bind} validates and hands back an unspawned handle, exactly as {@link ModelBinder#bind} does.
 * {@link Decoration#spawn()} is what creates the renderer-side binding, and it has a thread rule that
 * binding does not — see {@link Decoration}.
 */
public interface DecorationBinder {

    /**
     * Validates {@code rig} and returns a handle for a decoration standing at {@code location},
     * sheltered from the ambient field.
     *
     * <p>Sheltered is the default on purpose. Ambient motion costs packets continuously, because a
     * decoration that is moving can never reach the at-rest state that makes it free; a server whose
     * three hundred decorations all breathe by default would be paying for weather nobody asked for.
     *
     * @param rig      the rig to drive, as loaded from this plugin's rig directory
     * @param location where the decoration stands. Only its coordinates, yaw, pitch and world are read,
     *                 and they are read now — the handle keeps a value, not the location object
     * @return an unspawned handle; call {@link Decoration#spawn()} on the thread that owns the
     *         location's region
     * @throws IllegalArgumentException if {@code location} has no world
     * @throws IllegalStateException    if the renderer has no model by the name the rig points at, or if
     *                                  the model is missing any bone the rig names. The message lists
     *                                  <em>every</em> unresolved name
     */
    @NotNull Decoration bind(@NotNull Rig rig, @NotNull Location location);

    /**
     * {@link #bind(Rig, Location)} with an exposure to the ambient field.
     *
     * @param rig      the rig to drive
     * @param location where the decoration stands
     * @param ambient  how much of the configured ambient field reaches it
     * @return an unspawned handle
     * @throws IllegalArgumentException if {@code location} has no world
     * @throws IllegalStateException    if the rig cannot be bound to its model
     */
    @NotNull Decoration bind(
            @NotNull Rig rig,
            @NotNull Location location,
            @NotNull AmbientMotion ambient
    );
}
