package pandastic.studio.models.internal.menu;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a page does with an index the list has outgrown, which is the ordinary case rather than the
 * pathological one.
 *
 * <p>A session remembers a page number and nothing keeps that number honest: the catalogue can be
 * reloaded by the picker's own button between two clicks. So the interesting assertions here are all
 * about a remembered index meeting a smaller list.
 */
class MenuPageTest {

    private static final List<String> TEN =
            List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j");

    // ------------------------------------------------------------------ the arithmetic

    @Test
    void aFullListIsDividedWithTheLastPageShort() {
        final MenuPage page = MenuPage.of(10, 3, 3);
        assertAll(
                () -> assertEquals(4, page.pageCount()),
                () -> assertEquals(3, page.index()),
                () -> assertEquals(9, page.first()),
                () -> assertEquals(1, page.size()),
                () -> assertEquals(List.of("j"), page.of(TEN)));
    }

    @Test
    void anExactDivisionLeavesNoShortPage() {
        final MenuPage page = MenuPage.of(9, 3, 2);
        assertAll(
                () -> assertEquals(3, page.pageCount()),
                () -> assertEquals(3, page.size()),
                () -> assertEquals(6, page.first()));
    }

    @Test
    void aPageSizeLargerThanTheListIsOnePage() {
        final MenuPage page = MenuPage.of(3, 64, 0);
        assertAll(
                () -> assertEquals(1, page.pageCount()),
                () -> assertEquals(3, page.size()),
                () -> assertFalse(page.hasNext()),
                () -> assertFalse(page.hasPrevious()));
    }

    // ------------------------------------------------------------------ the index nobody controls

    @Test
    void anIndexPastTheEndLandsOnTheLastPage() {
        // What a player sees after a reload took the catalogue from forty models to five: the page they
        // were on no longer exists, and the answer a player expects is the last one that does.
        final MenuPage page = MenuPage.of(5, 2, 97);
        assertAll(
                () -> assertEquals(3, page.pageCount()),
                () -> assertEquals(2, page.index()),
                () -> assertEquals(4, page.first()),
                () -> assertEquals(1, page.size()));
    }

    @Test
    void aNegativeIndexLandsOnTheFirstPage() {
        assertEquals(0, MenuPage.of(10, 3, -40).index());
    }

    @Test
    void anEmptyListStillHasOnePage() {
        // Zero pages would make every caller special-case the empty catalogue before it could ask what
        // page it is on, and the screen has to render something either way.
        final MenuPage page = MenuPage.of(0, 8, 4);
        assertAll(
                () -> assertEquals(1, page.pageCount()),
                () -> assertEquals(0, page.index()),
                () -> assertEquals(0, page.size()),
                () -> assertEquals(1, page.humanIndex()),
                () -> assertTrue(page.of(List.of()).isEmpty()));
    }

    // ------------------------------------------------------------------ navigation

    @Test
    void steppingStopsAtEitherEnd() {
        final MenuPage first = MenuPage.of(10, 5, 0);
        final MenuPage last = MenuPage.of(10, 5, 1);
        assertAll(
                () -> assertEquals(0, first.previousIndex()),
                () -> assertEquals(1, first.nextIndex()),
                () -> assertEquals(0, last.previousIndex()),
                () -> assertEquals(1, last.nextIndex()),
                () -> assertFalse(first.hasPrevious()),
                () -> assertTrue(first.hasNext()),
                () -> assertTrue(last.hasPrevious()),
                () -> assertFalse(last.hasNext()));
    }

    @Test
    void theNumberAPlayerSeesIsOneBased() {
        assertEquals(3, MenuPage.of(10, 4, 2).humanIndex());
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void aPageSizeBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> MenuPage.of(10, 0, 0));
    }

    @Test
    void aNegativeListIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> MenuPage.of(-1, 8, 0));
    }

    @Test
    void aPageAppliedToADifferentListIsRefusedRatherThanThrowingFromTheSublist() {
        // The complaint has to name the mismatch, because the symptom otherwise is an
        // IndexOutOfBoundsException from inside subList with nothing saying which list was wrong.
        final MenuPage page = MenuPage.of(10, 3, 3);
        assertThrows(IllegalArgumentException.class, () -> page.of(List.of("a", "b")));
    }
}
