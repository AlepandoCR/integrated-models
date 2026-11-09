package pandastic.studio.models.internal.nms;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.ai.AiContext;

/**
 * How the adapter gets the object it hands to a behaviour body.
 *
 * <h2>Why the seam calls back instead of building one</h2>
 * Because the two halves of a context come from opposite sides of the seam and neither can be built
 * first. The memory access is the brain's, so it only exists after the brain has been rebuilt, which is
 * below the seam. Everything else — the region-ownership checks, the scan radius clamping, the plan
 * membership rule a memory read is checked against, the intent holder the simulation reads — is
 * configuration-dependent policy, which must be above it.
 *
 * <p>A one-method callback resolves that without either side reaching into the other: the installer
 * passes a factory that already closes over the policy, the adapter calls it with the store it has just
 * built, and the result is a context whose memory half is the brain's and whose rules are the
 * operator's. Nothing below the seam learns what the rules are, and nothing above it needs a reference
 * to a brain.
 *
 * <h2>Called once per installation, not once per tick</h2>
 * A context is cheap but not free, and the one it produces is immutable and reusable: every field it
 * holds is per-creature and fixed for the installation's life. So the adapter calls this once and keeps
 * the result for every behaviour invocation, which is also what makes the api's "a context is valid
 * only for the duration of the call" rule a statement about the <em>caller's</em> discipline rather
 * than about the object's lifetime.
 */
@FunctionalInterface
public interface AiContextFactory {

    /**
     * Builds the context for the installation whose memories {@code store} reaches.
     *
     * @param store the brain-backed memory access, freshly built
     * @return the context, reusable for the life of the installation
     */
    @NotNull AiContext create(@NotNull MemoryStore store);
}
