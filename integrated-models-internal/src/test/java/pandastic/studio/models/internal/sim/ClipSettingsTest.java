package pandastic.studio.models.internal.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What the clip layer's value record refuses.
 *
 * <p>It throws rather than clamping, which is the api-side half of this project's split: a programmatic
 * caller handing over a bad threshold has a bug, whereas a <em>file</em> is written by a human and
 * {@code ConfigMapper} is where a bad key is reported with its name and refused as a whole reload.
 */
class ClipSettingsTest {

    private static ClipSettings of(float walk, float run, float hysteresis, float hold, int in, int out) {
        return new ClipSettings(true, walk, run, hysteresis, hold, in, out);
    }

    @Test
    void acceptsTheOrdinaryCase() {
        assertDoesNotThrow(() -> of(0.02F, 0.25F, 0.01F, 0.3F, 3, 3));
    }

    @Test
    void refusesThresholdsThatDoNotLeaveRoomForWalking() {
        // Equal thresholds leave no speed at which the walk clip plays, so the model only ever stands or
        // sprints — with nothing in the log to explain it.
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> of(0.2F, 0.2F, 0F, 0F, 0, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> of(0.3F, 0.2F, 0F, 0F, 0, 0)));
    }

    @Test
    void refusesNegativeAndNonFiniteValues() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> of(-0.1F, 0.5F, 0F, 0F, 0, 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> of(0.1F, Float.NaN, 0F, 0F, 0, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> of(0.1F, 0.5F, -1F, 0F, 0, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> of(0.1F, 0.5F, 0F, -1F, 0, 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> of(0.1F, 0.5F, 0F, Float.POSITIVE_INFINITY, 0, 0)));
    }

    @Test
    void refusesNegativeBlendDurations() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> of(0.1F, 0.5F, 0F, 0F, -1, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> of(0.1F, 0.5F, 0F, 0F, 0, -1)));
    }

    @Test
    void acceptsZeroForEveryGuard() {
        // Zero hysteresis and zero hold are legitimate — they mean "no guard" — and zero blend means
        // "snap", which is right for a clip whose first frame already matches the rest pose.
        assertDoesNotThrow(() -> of(0F, 0.5F, 0F, 0F, 0, 0));
    }
}
