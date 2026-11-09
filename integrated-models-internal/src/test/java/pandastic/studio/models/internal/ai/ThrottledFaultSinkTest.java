package pandastic.studio.models.internal.ai;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.ai.AiBounds;
import pandastic.studio.models.internal.nms.AiFaultSink;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The failure policy: one report per interval, and a body that keeps failing is stopped.
 *
 * <h2>Why both halves need testing and neither is cosmetic</h2>
 * A behaviour runs every tick, so a behaviour that throws throws every tick. Without the throttle that
 * is twenty stack traces a second per creature, which fills the disk and pushes every other line out of
 * the log. Without the stop, the creature is <em>stuck</em> rather than merely noisy — a failing
 * behaviour is still the highest-priority candidate the brain considers, so nothing below it ever gets a
 * turn, for the rest of the server's life.
 *
 * <p>The clock is injected, so the throttling is proved rather than slept through.
 */
class ThrottledFaultSinkTest {

    private static final long INTERVAL_NANOS = 1_000_000_000L;
    private static final AiBounds BOUNDS = AiFixtures.BOUNDS;

    private final List<String> reports = new ArrayList<>();
    private long now;

    private ThrottledFaultSink sink(int faultsBeforeDisable) {
        final AiSettings settings =
                new AiSettings(BOUNDS, INTERVAL_NANOS, faultsBeforeDisable);
        return new ThrottledFaultSink(
                (message, failure) -> this.reports.add(message),
                AiSettingsSource.fixed(settings),
                () -> this.now);
    }

    private static RuntimeException failure() {
        return new IllegalStateException("broken");
    }

    @Test
    void theFirstFailureIsAlwaysReportedBecauseItIsNotARepeat() {
        final ThrottledFaultSink sink = sink(10);

        assertAll(
                () -> assertTrue(sink.failed(AiFaultSink.BEHAVIOUR, "follow", failure())),
                () -> assertEquals(1, this.reports.size()),
                () -> assertTrue(this.reports.get(0).contains("behaviour 'follow'"),
                        () -> this.reports.get(0))
        );
    }

    @Test
    void repeatsWithinTheIntervalAreCountedRatherThanReported() {
        final ThrottledFaultSink sink = sink(100);
        for (int attempt = 0; attempt < 5; attempt++) {
            sink.failed(AiFaultSink.BEHAVIOUR, "follow", failure());
        }

        assertAll(
                () -> assertEquals(1, this.reports.size(), "only the first may get through"),
                () -> assertEquals(5L, sink.consecutiveFailures(AiFaultSink.BEHAVIOUR, "follow"))
        );
    }

    @Test
    void theNextReportCarriesTheSuppressedCount() {
        // "This failed 1800 times" and "this failed once" call for different responses and look
        // identical without the number.
        final ThrottledFaultSink sink = sink(100);
        for (int attempt = 0; attempt < 4; attempt++) {
            sink.failed(AiFaultSink.BEHAVIOUR, "follow", failure());
        }
        this.now += INTERVAL_NANOS;
        sink.failed(AiFaultSink.BEHAVIOUR, "follow", failure());

        assertAll(
                () -> assertEquals(2, this.reports.size()),
                () -> assertTrue(this.reports.get(1).contains("3 further failure(s) suppressed"),
                        () -> this.reports.get(1))
        );
    }

    @Test
    void theCountIsConsecutiveSoASuccessResetsIt() {
        // A behaviour failing once an hour over something transient should not be stopped on its tenth
        // hour; one failing ten times in ten ticks is broken.
        final ThrottledFaultSink sink = sink(3);

        assertAll(
                () -> assertTrue(sink.failed(AiFaultSink.BEHAVIOUR, "follow", failure())),
                () -> assertTrue(sink.failed(AiFaultSink.BEHAVIOUR, "follow", failure())),
                () -> {
                    sink.succeeded(AiFaultSink.BEHAVIOUR, "follow");
                    assertEquals(0L, sink.consecutiveFailures(AiFaultSink.BEHAVIOUR, "follow"));
                },
                () -> assertTrue(sink.failed(AiFaultSink.BEHAVIOUR, "follow", failure()),
                        "the third failure after a reset must not stop the body")
        );
    }

    @Test
    void aBodyThatReachesTheLimitIsStopped() {
        final ThrottledFaultSink sink = sink(3);

        assertAll(
                () -> assertTrue(sink.failed(AiFaultSink.BEHAVIOUR, "follow", failure())),
                () -> assertTrue(sink.failed(AiFaultSink.BEHAVIOUR, "follow", failure())),
                () -> assertFalse(sink.failed(AiFaultSink.BEHAVIOUR, "follow", failure()),
                        "the third consecutive failure is the one the configured limit refuses")
        );
    }

    @Test
    void theStopIsAlwaysReportedEvenIfTheThrottleWouldHaveSuppressedIt() {
        // It is the last thing anyone will hear about that behaviour; suppressing it would leave a
        // creature quietly missing part of its plan.
        final ThrottledFaultSink sink = sink(2);
        sink.failed(AiFaultSink.BEHAVIOUR, "follow", failure());
        sink.failed(AiFaultSink.BEHAVIOUR, "follow", failure());

        assertAll(
                () -> assertEquals(2, this.reports.size()),
                () -> assertTrue(this.reports.get(1).contains("will not be run again"),
                        () -> this.reports.get(1)),
                () -> assertTrue(this.reports.get(1).contains("missing"),
                        () -> "the report must say what it costs the creature: " + this.reports.get(1))
        );
    }

    @Test
    void twoBodiesAreThrottledAndCountedIndependently() {
        final ThrottledFaultSink sink = sink(100);
        sink.failed(AiFaultSink.BEHAVIOUR, "follow", failure());
        sink.failed(AiFaultSink.BEHAVIOUR, "wander", failure());

        assertEquals(2, this.reports.size(),
                "a throttle shared across bodies would hide the second broken one behind the first");
    }

    @Test
    void aBehaviourAndASensorWithOneNameAreCountedApart() {
        final ThrottledFaultSink sink = sink(100);
        sink.failed(AiFaultSink.BEHAVIOUR, "look", failure());
        sink.failed(AiFaultSink.SENSOR, "look", failure());

        assertAll(
                () -> assertEquals(2, this.reports.size()),
                () -> assertEquals(1L, sink.consecutiveFailures(AiFaultSink.BEHAVIOUR, "look")),
                () -> assertEquals(1L, sink.consecutiveFailures(AiFaultSink.SENSOR, "look"))
        );
    }

    @Test
    void theIntervalIsReadPerDecisionSoAnOperatorCanRetuneDuringAnIncident() {
        // The one component someone reaches for while something is going wrong.
        final long[] interval = {INTERVAL_NANOS};
        final ThrottledFaultSink sink = new ThrottledFaultSink(
                (message, failure) -> this.reports.add(message),
                () -> new AiSettings(BOUNDS, interval[0], 100),
                () -> this.now);

        sink.failed(AiFaultSink.SENSOR, "look", failure());
        sink.failed(AiFaultSink.SENSOR, "look", failure());
        assertEquals(1, this.reports.size());

        interval[0] = 0L;
        sink.failed(AiFaultSink.SENSOR, "look", failure());
        assertEquals(2, this.reports.size(), "a throttle turned to zero must let the next one through");
    }

    @Test
    void aBodyThatNeverFailedHasNoCountAndSucceedingIsCheap() {
        final ThrottledFaultSink sink = sink(3);
        sink.succeeded(AiFaultSink.BEHAVIOUR, "never-failed");

        assertEquals(0L, sink.consecutiveFailures(AiFaultSink.BEHAVIOUR, "never-failed"));
    }
}
