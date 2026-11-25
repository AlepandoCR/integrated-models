package pandastic.studio.models.internal.rig;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.internal.spi.ModelDefinition;
import pandastic.studio.models.internal.spi.ModelRenderBackends;

import java.util.List;
import java.util.Optional;

/**
 * The bones a model has, their nesting, and which of them can be driven — the whole of what
 * {@link RigFactory} needs to derive a rig, and nothing else.
 *
 * <h2>Why this is a seam</h2>
 * The rendering library is {@code compileOnly} and lives in its own module, so there is no renderer at
 * test time and its model definitions are not constructible off a live server. A derivation written
 * against one would be untestable by construction — which is the same reason {@code ModelBone} exists one
 * layer over, and the same split {@link pandastic.studio.models.internal.binding.RigBindValidator} makes.
 * So every decision lives in {@link RigFactory} against this interface, and {@link #renderer()} is one
 * delegation with no decision in it.
 *
 * <h2>The order is part of the contract</h2>
 * A parent always precedes its children, which {@link ModelDefinition#boneLinks()} promises. The
 * derivation's quality depends on it: "the first acceptable bone" is only "the most root-ward acceptable
 * bone" while the order holds.
 *
 * <p>{@link ModelDefinition#boneLinks()} rather than {@link ModelDefinition#bones()} on purpose.
 * The latter reconstructs every bone's rest transform, which is three joml copies and a quaternion
 * composition per bone, and does not carry the one bit a derivation actually needs — whether the renderer
 * will give the bone a display. Paying a 256-bone model's bind geometry to answer a question about names
 * would be the wrong trade in both directions.
 */
@FunctionalInterface
public interface ModelBoneSource {

    /**
     * The bones of the model registered as {@code modelId}, a parent always before its children.
     *
     * @return the bones, or empty if there is no such model — which is a fact about what the operator
     *         installed, not about the caller, and is why it is not an exception
     */
    @NotNull Optional<List<ModelBoneLink>> bonesOf(@NotNull String modelId);

    /**
     * The source that reads the installed renderer's loaded model definitions.
     *
     * <p>Which renderer that is, and how it decides whether a bone will get a display, is the backend
     * module's business — see {@link ModelDefinition#boneLinks()}.
     */
    static @NotNull ModelBoneSource renderer() {
        return modelId -> {
            final Optional<ModelDefinition> definition =
                    ModelRenderBackends.require().definition(modelId);
            if (definition.isEmpty()) {
                return Optional.empty();
            }
            // Copied defensively: the backend's contract permits a live view, and a derivation walks
            // this list several times.
            return Optional.of(List.copyOf(definition.get().boneLinks()));
        };
    }
}
