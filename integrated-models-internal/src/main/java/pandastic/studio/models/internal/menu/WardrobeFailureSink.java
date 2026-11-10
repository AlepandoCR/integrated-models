package pandastic.studio.models.internal.menu;

import org.jetbrains.annotations.NotNull;

/**
 * Where a failure to dress somebody goes.
 *
 * <h2>Why the wardrobe needs one at all</h2>
 * {@link ModelWardrobe} defines a refusal as a no-op rather than as an exception, and it has to: the only
 * caller is a dialog click handler, and an exception thrown there is lost in the platform's own callback
 * plumbing with nothing naming the screen it came from. But the two things that can go wrong on the way
 * into {@link ModelWardrobe#wear} — no rig can be produced for a model, or the rig's bone names no longer
 * match it — are <strong>content errors an operator has to fix</strong>, and swallowing them without a
 * word turns a broken rig into a button that does nothing.
 *
 * <p>So the player gets the no-op the contract promises, and the console gets the reason. Exactly the
 * split {@link pandastic.studio.models.internal.command.CommandFailureSink} makes for the reload command.
 *
 * <h2>Why a seam rather than a logger</h2>
 * A test cannot usefully implement Adventure's component logger, and what is worth asserting here is
 * <em>whether</em> a failure is reported, not how it is formatted. The Adventure binding is one lambda at
 * the wiring site.
 *
 * <p>Declared in this package rather than reusing the command layer's identical interface because the
 * menu layer has nothing else to do with the command layer, and an import between the two would be the
 * only edge between them — a dependency that says something untrue about how they relate.
 *
 * <p>Implementations are called on the thread that owns the player being dressed, which is a region
 * thread, and must not block.
 */
@FunctionalInterface
public interface WardrobeFailureSink {

    /**
     * Reports that a wardrobe operation could not be carried out.
     *
     * @param message what was being attempted, naming the player and the model
     * @param thrown  why it could not be
     */
    void failed(@NotNull String message, @NotNull Throwable thrown);

    /** Discards everything. For tests and for a caller that has its own reporting. */
    @NotNull WardrobeFailureSink DISCARDING = (message, thrown) -> {
    };
}
