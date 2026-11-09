package pandastic.studio.models.api.binding;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.rig.Rig;

/**
 * How a consumer says "put this rig on this entity".
 *
 * <p>The one entry point into the binding layer, and the only type in the published jar that names both
 * a {@link Rig} and a platform object. Everything it returns is a {@link RiggedModel}; everything it
 * builds behind that is this plugin's business.
 *
 * <h2>Why binding validates and does not spawn</h2>
 * {@link #bind} answers one question — "does this rig name bones this model actually has" — and answers
 * it <strong>offline</strong>, against the model definition the renderer loaded rather than against a
 * live tracker. A rig whose bone names stopped matching is a content error: somebody renamed a group in
 * their modelling tool and every rig naming it is now wrong. That has to surface as a loud failure with
 * every broken name in it, at the moment a plugin tries to use the rig, rather than as a model that
 * spawns and then does not move.
 *
 * <p>Spawning is a separate call because it has a thread rule binding does not: see
 * {@link RiggedModel#spawn()}.
 *
 * <h2>What is not here yet</h2>
 * A model attached to nothing — a rig standing at a location with no entity under it — is expressible in
 * the layer below this one and is not yet exposed here. It arrives as a second {@code bind} overload
 * rather than as a second handle type, because the only thing that differs is where the position comes
 * from.
 */
public interface ModelBinder {

    /**
     * Validates {@code rig} against its model and returns a handle for driving {@code entity} with it.
     *
     * <p>Does not spawn anything and does not touch {@code entity}, so it is safe to call from any
     * thread — including from a loading routine before the entity's region has been ticked.
     *
     * @param rig    the rig to drive, as loaded from this plugin's rig directory
     * @param entity what the model follows, and whose region thread owns every subsequent read of it
     * @return an unspawned handle; call {@link RiggedModel#spawn()} on the entity's own thread
     * @throws IllegalStateException if the renderer has no model by the name the rig points at, or if
     *                               the model is missing any bone the rig names. The message lists
     *                               <em>every</em> unresolved name and the model's own bone names,
     *                               because the person fixing it is working from that one line
     */
    @NotNull RiggedModel bind(@NotNull Rig rig, @NotNull Entity entity);
}
