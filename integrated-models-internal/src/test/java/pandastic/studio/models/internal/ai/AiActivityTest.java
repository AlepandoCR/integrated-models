package pandastic.studio.models.internal.ai;

import pandastic.studio.models.api.ai.AiActivity;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The activity vocabulary.
 *
 * <p>There is not much to test about an enum, and the two things there are both matter. The vanilla
 * names are a lookup table rather than a derivation, so a typo in one of twenty-six string literals is
 * invisible until a server refuses to find the activity; and the name lookup is what a loader reading a
 * plan out of a file depends on.
 */
class AiActivityTest {

    @Test
    void thereAreAsManySlotsAsTheServerHas() {
        // The server's activity registry is frozen after bootstrap and holds twenty-six. The count is
        // derived from the enum rather than written down, so this asserts the enum is complete.
        assertEquals(26, AiActivity.COUNT);
    }

    @Test
    void everyVanillaNameIsDistinctAndLowerCase() {
        final Set<String> seen = new HashSet<>();

        assertAll(Arrays.stream(AiActivity.values()).map(activity -> () -> {
            final String name = activity.vanillaName();
            assertAll(
                    () -> assertEquals(name.toLowerCase(Locale.ROOT), name,
                            () -> activity + " has a vanilla name that is not lower case"),
                    () -> assertTrue(seen.add(name),
                            () -> activity + " shares its vanilla name with another slot, which means"
                                    + " two of them would map onto one server activity")
            );
        }));
    }

    @Test
    void everyNameRoundTripsThroughTheLookup() {
        assertAll(Arrays.stream(AiActivity.values()).map(activity -> () ->
                assertEquals(activity, AiActivity.byVanillaName(activity.vanillaName()))));
    }

    @Test
    void theLookupIsCaseInsensitive() {
        assertEquals(AiActivity.LONG_JUMP, AiActivity.byVanillaName("LONG_JUMP"));
    }

    @Test
    void anUnknownNameAnswersNullRatherThanThrowing() {
        // A loader has a file name and a line number to put in the message; this method does not.
        assertNull(AiActivity.byVanillaName("patrolling"));
    }
}
