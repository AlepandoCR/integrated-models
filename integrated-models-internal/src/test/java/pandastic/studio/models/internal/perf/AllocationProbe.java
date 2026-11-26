package pandastic.studio.models.internal.perf;

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;

/**
 * Counts the bytes one loop body allocates, so a "this path allocates nothing per step" claim can be a
 * test rather than a comment.
 *
 * <h2>Why this exists at all</h2>
 * Three of this plugin's four hot paths run per model or per vehicle per tick, two of them on a thread
 * that on a non-regionised server <em>is</em> the main thread, and every one of them carries a javadoc
 * sentence promising it allocates nothing but the values it publishes. Those sentences were true when
 * they were written and nothing in the suite noticed when one stopped being true: a record added to a
 * reused buffer, a {@code Location} read for one of its five fields, a lambda that started capturing.
 * The symptom of all three is the same — garbage-collector pressure proportional to the number of models
 * — and it is invisible in a functional test and expensive to find on a live server.
 *
 * <h2>How the figure is obtained</h2>
 * {@link ThreadMXBean#getThreadAllocatedBytes(long)}, which HotSpot answers from the calling thread's own
 * allocation counter and which includes objects that never leave a thread-local allocation buffer. It is
 * therefore a count of what the body <em>asked</em> for, not of what survived — which is exactly the
 * question, because a short-lived object on a region thread still costs the allocation and still fills a
 * young generation.
 *
 * <p>Measured over many iterations and divided, because the counter's resolution is bytes and a single
 * step of some of these paths allocates less than one object. A body that allocates one 24-byte record
 * every third step reports eight bytes a step, which is the honest answer and is what a ceiling should be
 * compared against.
 *
 * <h2>Warming up is not optional</h2>
 * The first few hundred executions of any of these bodies allocate things the steady state does not:
 * class initialisers, the interpreter's own profiling structures, a lambda's call site, the first
 * {@code StringBuilder} inside an exception message that is never thrown again. Measuring from cold would
 * report a figure nobody pays and would make the ceiling useless. So the body runs {@code warmup} times
 * first, and the measurement is taken from a steady state.
 *
 * <h2>What a ceiling should be, and why never an exact figure</h2>
 * Assert a ceiling. The exact byte count depends on the JVM's object layout, on whether compressed
 * object pointers are on, and on how far the JIT got with scalar replacement by the time the measured
 * window opened — all three vary between machines and between runs, and none of them is what the test is
 * about. The question a regression test should ask is "did this path start allocating per step", and the
 * answer worth failing on is a figure several times the one measured today.
 */
public final class AllocationProbe {

    /**
     * The platform's own allocation counter, or {@code null} on a JVM that does not expose one.
     *
     * <p>Resolved once. {@code com.sun.management.ThreadMXBean} is HotSpot's extension to the standard
     * bean and is present on every JVM this project is built or run on, but a test that hard-depends on
     * it would fail rather than skip on one that is not — and a skipped allocation assertion is a worse
     * outcome than a failed build only if nobody notices, which is what {@link #isSupported()} is for.
     */
    private static final ThreadMXBean BEAN = resolveBean();

    private AllocationProbe() {
    }

    /** Whether this JVM can answer {@link #bytesPerIteration}. */
    public static boolean isSupported() {
        return BEAN != null && BEAN.isThreadAllocatedMemorySupported()
                && BEAN.isThreadAllocatedMemoryEnabled();
    }

    /**
     * Bytes {@code body} allocates per execution, in a steady state.
     *
     * <p>Runs on the calling thread, which is the thread the counter is read for — so {@code body} must
     * not hand its work to another one. Everything this probe is pointed at is single-threaded by
     * contract anyway: a simulation step, a control step, a sample.
     *
     * @param warmup     how many times to run the body before measuring. A few thousand for anything
     *                   with a loop in it, so the JIT has compiled the shape that will actually run
     * @param iterations how many executions the measurement is divided by. Large enough that one object
     *                   allocated every few steps still shows up
     * @param body       the thing being measured, re-run {@code warmup + iterations} times. It must be
     *                   idempotent in the sense that running it repeatedly is meaningful — a step is
     *                   exactly that
     * @return allocated bytes divided by {@code iterations}
     * @throws IllegalStateException if this JVM exposes no allocation counter; call
     *                               {@link #isSupported()} first
     */
    public static double bytesPerIteration(int warmup, int iterations, Runnable body) {
        if (!isSupported()) {
            throw new IllegalStateException(
                    "This JVM does not expose com.sun.management.ThreadMXBean's allocation counter");
        }
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations must be positive, got " + iterations);
        }
        for (int warmed = 0; warmed < warmup; warmed++) {
            body.run();
        }
        final long thread = Thread.currentThread().threadId();
        // Nothing is allocated between these two reads but the body itself. In particular the loop
        // counter is a primitive and the body is an already-created reference, so the figure is the
        // body's own.
        final long before = BEAN.getThreadAllocatedBytes(thread);
        for (int iteration = 0; iteration < iterations; iteration++) {
            body.run();
        }
        final long after = BEAN.getThreadAllocatedBytes(thread);
        return (double) (after - before) / iterations;
    }

    /**
     * The overhead of the measurement itself, as bytes per iteration over a body that allocates nothing.
     *
     * <p>Exposed so a test can report it beside a figure it is asserting on, and so that a ceiling of
     * "nothing" can be stated honestly: on a JVM where the counter itself allocated, every figure this
     * class produced would be that plus the body's, and a reader comparing a measured zero against a
     * ceiling would have no way to tell which they were looking at.
     */
    public static double baselineBytesPerIteration(int warmup, int iterations) {
        return bytesPerIteration(warmup, iterations, AllocationProbe::nothing);
    }

    /** A body that allocates nothing, for {@link #baselineBytesPerIteration}. */
    private static void nothing() {
    }

    private static ThreadMXBean resolveBean() {
        final java.lang.management.ThreadMXBean standard = ManagementFactory.getThreadMXBean();
        return standard instanceof ThreadMXBean extended ? extended : null;
    }
}
