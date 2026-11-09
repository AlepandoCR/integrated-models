package pandastic.studio.models.internal.ai;

import pandastic.studio.models.api.ai.AiActivity;
import pandastic.studio.models.api.ai.AiBounds;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The bounds record, whose failures all look like a plan's fault rather than a file's.
 *
 * <p>That is the selection criterion here. A {@code maxBehavioursPerActivity} of zero refuses every plan
 * ever written and says nothing about {@code ai.yml}, so the operator spends their time reading plan
 * code. Each case is one such configuration, refused where the file is still the subject.
 */
class AiBoundsTest {

    @Test
    void theFixtureBoundsAreLegal() {
        assertEquals(3, AiFixtures.BOUNDS.maxActivities());
    }

    @Test
    void aBoundThatAdmitsNoPlanIsRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> bounds(0, 2, 7, 2, 2L, 40L, 11.0D, 500L, 8), "maxActivities of 0"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> bounds(3, 0, 7, 2, 2L, 40L, 11.0D, 500L, 8),
                        "maxBehavioursPerActivity of 0"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> bounds(3, 2, -1, 2, 2L, 40L, 11.0D, 500L, 8), "negative maxPriority"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> bounds(3, 2, 7, -1, 2L, 40L, 11.0D, 500L, 8), "negative maxSensors"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> bounds(3, 2, 7, 2, 0L, 40L, 11.0D, 500L, 8),
                        "a sensor period floor below one tick, which the platform rounds up anyway"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> bounds(3, 2, 7, 2, 2L, 40L, 0.0D, 500L, 8),
                        "a zero scan ceiling, which makes every creature blind"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> bounds(3, 2, 7, 2, 2L, 40L, 11.0D, 0L, 8), "maxMemoryExpiryTicks of 0"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> bounds(3, 2, 7, 2, 2L, 40L, 11.0D, 500L, 0), "maxMemoriesPerPlan of 0")
        );
    }

    @Test
    void aSensorPeriodRangeWithNoLegalValueIsRefused() {
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> bounds(3, 2, 7, 2, 40L, 2L, 11.0D, 500L, 8));

        assertEquals(true, refused.getMessage().contains("no legal sensor period"),
                () -> refused.getMessage());
    }

    @Test
    void anActivityCeilingAboveTheServersOwnSlotCountIsRefused() {
        // Not a sanity ceiling but a cardinality: an activity is a registry object whose registry is
        // frozen before any plugin loads, so there are exactly this many and no file can produce more.
        assertThrows(IllegalArgumentException.class,
                () -> bounds(AiActivity.COUNT + 1, 2, 7, 2, 2L, 40L, 11.0D, 500L, 8));
    }

    @Test
    void aNonFiniteScanCeilingIsRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> bounds(3, 2, 7, 2, 2L, 40L, Double.NaN, 500L, 8)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> bounds(3, 2, 7, 2, 2L, 40L, Double.POSITIVE_INFINITY, 500L, 8))
        );
    }

    @Test
    void aScanRadiusIsClampedRatherThanRefused() {
        // The one asymmetry in this record, and it is deliberate: a behaviour asking to look too far
        // should get the operator's answer, because the region boundary is already going to give it one.
        assertAll(
                () -> assertEquals(5.0D, AiFixtures.BOUNDS.clampScanRadius(5.0D)),
                () -> assertEquals(11.0D, AiFixtures.BOUNDS.clampScanRadius(200.0D)),
                () -> assertEquals(0.0D, AiFixtures.BOUNDS.clampScanRadius(0.0D))
        );
    }

    @Test
    void aNonsensicalScanRadiusIsRefusedRatherThanClamped() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AiFixtures.BOUNDS.clampScanRadius(-1.0D)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AiFixtures.BOUNDS.clampScanRadius(Double.NaN))
        );
    }

    /** Named arguments, so a case above reads as the one thing it is changing. */
    private static AiBounds bounds(
            int maxActivities,
            int maxBehavioursPerActivity,
            int maxPriority,
            int maxSensors,
            long minSensorPeriodTicks,
            long maxSensorPeriodTicks,
            double maxScanRadiusBlocks,
            long maxMemoryExpiryTicks,
            int maxMemoriesPerPlan
    ) {
        return new AiBounds(maxActivities, maxBehavioursPerActivity, maxPriority, maxSensors,
                minSensorPeriodTicks, maxSensorPeriodTicks, maxScanRadiusBlocks, maxMemoryExpiryTicks,
                maxMemoriesPerPlan);
    }
}
