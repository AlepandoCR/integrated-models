package pandastic.studio.models.internal.nms;

/**
 * Constructing and swapping a mob's path navigation.
 *
 * <h2>Deliberately empty in this phase</h2>
 * See {@link BrainBridge} — the seam is declared now so the build isolation can be proved, and
 * filled by the AI phase.
 *
 * <h2>Why navigation needs a seam of its own rather than riding on {@link BrainBridge}</h2>
 * A brain decides <em>where</em> to go; the navigation decides <em>whether the shape of this
 * creature can get there</em>. They change independently: a model whose hitbox is three blocks wide
 * needs a different node evaluator from the same brain's default, and a flying model needs a
 * different navigation class entirely. Folding both into one seam would mean every brain change
 * re-asks the pathfinding question.
 *
 * <h2>The constraint that drives it</h2>
 * A navigation is constructed with the mob and the level, and the entity's navigation field is not
 * meant to be swapped after spawn — vanilla sets it once in the constructor. Replacing it on a live
 * entity is the operation this seam has to make safe, which means it is also the operation that has
 * to be undone when a model is unbound, or the replacement outlives the model and keeps steering a
 * mob nobody owns any more.
 *
 * <h2>Threading</h2>
 * Same rule as everywhere else below the seam: the owning region's thread only. Path computation in
 * vanilla is partly off-thread already, so an implementation must not assume that a navigation call
 * completes before it returns.
 */
public interface NavigationBridge {
}
