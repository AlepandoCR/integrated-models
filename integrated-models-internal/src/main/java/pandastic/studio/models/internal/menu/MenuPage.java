package pandastic.studio.models.internal.menu;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * One window onto a list, worked out once per screen build.
 *
 * <h2>Why the requested index is clamped rather than trusted</h2>
 * Because the index outlives the list. A session remembers which page a player was on, and between two
 * clicks a reload can take the catalogue from forty models to three — so the remembered index is
 * routinely past the end, through nobody's fault. Clamping turns that into "you are on the last page",
 * which is what a player expects; refusing it would turn a reload into an error message, and indexing
 * with it would turn one into an exception.
 *
 * <p>The same applies to a negative index, which is what a client can send if it is asked for a page
 * number. Both ends are pulled in, which is why this type exists instead of two lines of arithmetic at
 * each call site.
 *
 * <h2>An empty list still has one page</h2>
 * Page one of one, holding nothing. The alternative — zero pages — makes every caller special-case the
 * empty catalogue before it can even ask what page it is on, and the screen has to render something
 * either way.
 *
 * @param index     zero-based index of the page in view, always inside {@code [0, pageCount)}
 * @param pageCount how many pages the list spans, at least one
 * @param first     index of the first item on this page; equal to the list size when the list is empty
 * @param size      how many items are on this page; zero only for an empty list
 */
public record MenuPage(int index, int pageCount, int first, int size) {

    public MenuPage {
        if (pageCount < 1) {
            throw new IllegalArgumentException("pageCount must be at least 1, but was " + pageCount);
        }
        if (index < 0 || index >= pageCount) {
            throw new IllegalArgumentException("index " + index + " is outside [0, " + pageCount + ")");
        }
        if (first < 0 || size < 0) {
            throw new IllegalArgumentException(
                    "first (" + first + ") and size (" + size + ") must not be negative");
        }
    }

    /**
     * The page of {@code totalItems} items that holds {@code requestedIndex}, or the nearest one that
     * exists.
     *
     * @param totalItems     how many items the list holds
     * @param pageSize       how many fit on a page; at least one
     * @param requestedIndex the page the caller asked for, which may be out of range
     * @return the window
     */
    @Contract(value = "_, _, _ -> new", pure = true)
    public static @NotNull MenuPage of(int totalItems, int pageSize, int requestedIndex) {
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be at least 1, but was " + pageSize);
        }
        if (totalItems < 0) {
            throw new IllegalArgumentException("totalItems must not be negative, but was " + totalItems);
        }

        // Ceiling division, floored at one so the empty list still has a page to render.
        final int pages = Math.max(1, (totalItems + pageSize - 1) / pageSize);
        final int index = Math.min(pages - 1, Math.max(0, requestedIndex));
        final int first = Math.min(totalItems, index * pageSize);
        return new MenuPage(index, pages, first, Math.min(pageSize, totalItems - first));
    }

    /** One-based page number, which is the only form a player is ever shown. */
    @Contract(pure = true)
    public int humanIndex() {
        return this.index + 1;
    }

    /** Whether there is a page after this one. */
    @Contract(pure = true)
    public boolean hasNext() {
        return this.index + 1 < this.pageCount;
    }

    /** Whether there is a page before this one. */
    @Contract(pure = true)
    public boolean hasPrevious() {
        return this.index > 0;
    }

    /** The index of the next page, or this one when there is none. */
    @Contract(pure = true)
    public int nextIndex() {
        return hasNext() ? this.index + 1 : this.index;
    }

    /** The index of the previous page, or this one when there is none. */
    @Contract(pure = true)
    public int previousIndex() {
        return hasPrevious() ? this.index - 1 : this.index;
    }

    /**
     * The items this window covers.
     *
     * <p>A view rather than a copy, so a screen build does not duplicate the catalogue. The caller holds
     * it for the length of one build, which is the same window the list itself is held for.
     *
     * @param items the list this page was computed against
     * @param <ItemType> the element type
     * @return the items on this page, possibly empty
     * @throws IllegalArgumentException if {@code items} is not the list this page was computed from
     */
    @Contract(pure = true)
    public <ItemType> @NotNull List<ItemType> of(@NotNull List<ItemType> items) {
        if (this.first + this.size > items.size()) {
            throw new IllegalArgumentException("This page covers [" + this.first + ", "
                    + (this.first + this.size) + ") but the list holds " + items.size()
                    + " item(s): the page was computed against a different list");
        }
        return items.subList(this.first, this.first + this.size);
    }
}
