package pandastic.studio.models.internal.menu;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The catalogue the menus read, over whatever the renderer currently holds.
 *
 * <h2>Sorted, and that is not cosmetic</h2>
 * {@link ModelLibrary#modelIds()} answers in the renderer's own iteration order, which is an accident of
 * how its map was filled. The picker pages that list and remembers a page <em>index</em> across clicks,
 * so an order that can change between two builds makes a remembered index name a different model each
 * time — a player would press Next and arrive somewhere unrelated, with nothing to blame. Sorting here
 * makes the order a function of the ids alone, so the index means the same thing for as long as the ids
 * do. Alphabetical rather than "most recently installed first", because a player looking for a model
 * knows its name and not when it arrived.
 *
 * <h2>Nothing is cached, and the Reload button therefore only counts</h2>
 * Two facts decide this. BetterModel re-reads its own models on its own operator command and reports
 * that to nobody, so a cached list would have no event able to invalidate it; and a screen build already
 * happens on a click, where one copy and one sort of a list of tens of names is not measurable against
 * building and serializing a dialog.
 *
 * <p>So every method asks the library afresh, and {@link #reload()} has nothing to re-read: it answers
 * the count the <em>next</em> screen will be built from, which is exactly what the button's own
 * documentation promises a player is told. It deliberately does <strong>not</strong> ask the renderer to
 * re-read its asset folder — that is BetterModel's own operator action, it rebuilds a resource pack, and
 * putting it behind a button every player can press would be a denial of service with a nice label on
 * it.
 */
public final class LibraryModelCatalogue implements ModelCatalogue {

    private final @NotNull ModelLibrary library;

    /**
     * @param library where the renderer's loaded models are read from
     */
    public LibraryModelCatalogue(@NotNull ModelLibrary library) {
        this.library = Objects.requireNonNull(library, "library");
    }

    @Override
    public @NotNull @Unmodifiable List<String> modelIds() {
        return sorted(this.library.modelIds());
    }

    /**
     * {@inheritDoc}
     *
     * <p>An <strong>exact</strong> comparison against the renderer's own key, deliberately. The renderer
     * resolves a model name through a map lookup, so accepting a case variant here would accept an id
     * that {@link ModelWardrobe#wear} then cannot resolve — the picker would confirm a model the player
     * is not wearing, which is worse than refusing one that nearly matched.
     */
    @Override
    public boolean hasModel(@NotNull String modelId) {
        Objects.requireNonNull(modelId, "modelId");
        return this.library.modelIds().contains(modelId);
    }

    @Override
    public @NotNull @Unmodifiable List<String> animationIds(@NotNull String modelId) {
        Objects.requireNonNull(modelId, "modelId");
        return sorted(this.library.animationIds(modelId));
    }

    @Override
    public int reload() {
        return this.library.modelIds().size();
    }

    /**
     * {@code ids}, copied out of whatever the renderer handed over and sorted.
     *
     * <p>Copied first because the library's answer may be a live unmodifiable view of the renderer's own
     * map: sorting that in place is impossible and holding it across a build would let the list change
     * under a screen that had already paged it.
     */
    private static @NotNull @Unmodifiable List<String> sorted(@NotNull Collection<String> ids) {
        final List<String> copy = new ArrayList<>(ids);
        copy.sort(null);
        return List.copyOf(copy);
    }

    @Override
    public @NotNull String toString() {
        return "LibraryModelCatalogue[" + this.library + "]";
    }
}
