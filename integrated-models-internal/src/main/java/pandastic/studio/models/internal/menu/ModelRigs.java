package pandastic.studio.models.internal.menu;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.rig.Rig;

/**
 * How the wardrobe gets a rig for a model id.
 *
 * <h2>Why the wardrobe does not resolve one itself</h2>
 * Because "which rig drives this model" is a question with a policy in it — an authored rig from the rig
 * directory if one names the model, and something derived from the model's own bones if none does — and
 * that policy belongs to whatever owns the rig directory, not to a menu. Declaring the question as a
 * single-method seam here means the wardrobe compiles against its own abstraction and the runtime decides
 * what satisfies it, which is also what keeps this package free of any dependency on the rig loader.
 *
 * <p>It is a {@link FunctionalInterface} for exactly that reason: anything shaped like
 * {@code String -> Rig} is an implementation, so a factory living in another package is wired in as a
 * method reference rather than adapted.
 *
 * <h2>Failure is an exception, not an empty answer</h2>
 * A model id reaching this has already been checked against {@link ModelCatalogue}, so the renderer has
 * it loaded; a rig that cannot be produced for it therefore means the <em>server</em> cannot dress
 * anybody in that model, which is a condition an operator has to be told about rather than one a player
 * should see as a button that silently does nothing. The wardrobe catches it, reports it once through its
 * own failure sink and leaves the player undressed — see {@link RiggedModelWardrobe#wear}.
 */
@FunctionalInterface
public interface ModelRigs {

    /**
     * The rig that drives {@code modelId}.
     *
     * <p>Called on the thread that owns the player being dressed, and expected to be pure: a rig is an
     * immutable definition, so an implementation may answer the same instance to every caller.
     *
     * @param modelId the renderer's own model id
     * @return the rig to bind
     * @throws RuntimeException if no rig can be produced for that model
     */
    @NotNull Rig rigFor(@NotNull String modelId);
}
