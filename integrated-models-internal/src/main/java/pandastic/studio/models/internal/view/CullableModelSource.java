package pandastic.studio.models.internal.view;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

/**
 * Where a culling pass gets the models it has to decide about.
 *
 * <h2>Why a source and not a registry of its own</h2>
 * Every binder in this plugin already keeps the exact set this layer wants — "handed out and not yet
 * torn down" — because something has to be able to tear them all down at disable. A second registry here
 * would be a second copy of that membership, maintained by a second pair of statements, and the two would
 * disagree the first time a teardown took a path one of them did not cover: a model the culler still knew
 * about would keep being decided for, and a model it had dropped would silently stop being culled.
 *
 * <p>So the pass reads the binders' own collections through this seam, once per pass. The cost is one
 * snapshot copy per source per pass, which at ten passes a second is nothing next to what one suspended
 * simulation saves, and the correctness is free: a model that is gone from its binder is gone from here.
 */
@FunctionalInterface
public interface CullableModelSource {

    /**
     * Every model this source currently holds.
     *
     * <p>Called once per pass and iterated immediately, so an implementation may hand back a live view;
     * the pass does not retain it. It may be empty and it may contain models that are not
     * {@link CullableModel#isCullable() cullable}, which the pass handles.
     */
    @NotNull Collection<? extends CullableModel> cullableModels();

    /** A source that holds nothing. For a runtime assembled without one of the binders, and for tests. */
    @NotNull CullableModelSource EMPTY = List::of;
}
