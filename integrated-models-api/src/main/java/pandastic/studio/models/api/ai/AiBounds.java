package pandastic.studio.models.api.ai;

import org.jetbrains.annotations.NotNull;

/**
 * The limits a plan is checked against, as the operator set them.
 *
 * <h2>Why a plan is bounded at all</h2>
 * Because every number in a plan is multiplied by the creature count before the server pays it. A plan
 * with forty behaviours per activity is forty start tests per creature per tick; a sensor with a
 * sixty-four block radius is a sixty-four block box query per creature per period. Neither is wrong in
 * the abstract and both are wrong at scale, and the person who knows which is which is the operator
 * running the server rather than the plugin that wrote the plan. So the plan declares what it wants and
 * this record says what it may have.
 *
 * <p>Every field arrives from {@code ai.yml} and is hot-reloadable. None has a default here: a default
 * would be exactly the hardcoded tunable the project rule forbids, and it would be the hardest kind to
 * notice because a plan that fits inside it never mentions it.
 *
 * <h2>Why this throws while a rig file clamps</h2>
 * Same split as everywhere else in this project. A plan is written in Java by a programmer, so a plan
 * that exceeds a bound is a bug and is refused with a message naming the bound. A file is written by a
 * human, so a loader reading a plan out of a file is expected to clamp what it can, warn, and keep
 * going — and it has this record to clamp against.
 *
 * @param maxActivities             how many activity slots one plan may fill. The server has
 *                                  twenty-six and a plan using more than a handful is usually one that
 *                                  wanted conditions instead
 * @param maxBehavioursPerActivity  how many behaviours one activity may hold. The direct multiplier on
 *                                  the per-tick start tests of an active activity
 * @param maxPriority               highest priority number a behaviour may claim. Lower numbers win,
 *                                  so this is a ceiling on how deep the fallback chain may go
 * @param maxSensors                how many sensors one plan may run. Each is a retained scheduled task
 *                                  per creature
 * @param minSensorPeriodTicks      fastest a sensor may run, in ticks. One tick is legal and means a
 *                                  scan every tick per creature, which is what this bound is for
 * @param maxSensorPeriodTicks      slowest a sensor may run, in ticks. A ceiling rather than a
 *                                  courtesy: a sensor slower than its memory's expiry leaves the memory
 *                                  absent most of the time, and the symptom is a behaviour that fires
 *                                  intermittently for no visible reason
 * @param maxScanRadiusBlocks       widest a scan may reach, in blocks. Clamped rather than refused at
 *                                  the call site, because a behaviour asking for too much should get
 *                                  the operator's answer and be told what it got
 * @param maxMemoryExpiryTicks      longest a memory may be remembered for. A memory that expires in a
 *                                  year is a memory that never expires, and the two want different
 *                                  words
 * @param maxMemoriesPerPlan        how many distinct memories one plan may declare. Each is a slot in
 *                                  every creature's brain
 */
public record AiBounds(
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

    /**
     * Refuses a set of bounds that cannot admit any plan at all.
     *
     * <p>Each check catches a configuration that would make every plan fail with a message about the
     * plan, which is the wrong place to look. A {@code maxBehavioursPerActivity} of zero, for instance,
     * refuses every plan ever written and says nothing about {@code ai.yml}.
     */
    public AiBounds {
        atLeast("maxActivities", maxActivities, 1);
        atLeast("maxBehavioursPerActivity", maxBehavioursPerActivity, 1);
        atLeast("maxPriority", maxPriority, 0);
        atLeast("maxSensors", maxSensors, 0);
        atLeast("minSensorPeriodTicks", minSensorPeriodTicks, 1L);
        atLeast("maxSensorPeriodTicks", maxSensorPeriodTicks, 1L);
        atLeast("maxMemoryExpiryTicks", maxMemoryExpiryTicks, 1L);
        atLeast("maxMemoriesPerPlan", maxMemoriesPerPlan, 1);

        if (maxSensorPeriodTicks < minSensorPeriodTicks) {
            throw new IllegalArgumentException("maxSensorPeriodTicks " + maxSensorPeriodTicks
                    + " is below minSensorPeriodTicks " + minSensorPeriodTicks
                    + ", which leaves no legal sensor period");
        }
        if (!Double.isFinite(maxScanRadiusBlocks) || maxScanRadiusBlocks <= 0.0D) {
            throw new IllegalArgumentException("maxScanRadiusBlocks must be finite and positive but was "
                    + maxScanRadiusBlocks + "; a zero ceiling makes every creature blind");
        }
        if (maxActivities > AiActivity.COUNT) {
            // Not a sanity ceiling but a cardinality: a plan fills distinct slots out of a frozen
            // registry, so there are AiActivity.COUNT of them and no configuration can produce more.
            throw new IllegalArgumentException("maxActivities " + maxActivities
                    + " exceeds the " + AiActivity.COUNT + " activity slots the server has");
        }
    }

    private static void atLeast(@NotNull String name, long value, long floor) {
        if (value < floor) {
            throw new IllegalArgumentException(name + " must be at least " + floor + " but was " + value);
        }
    }

    /**
     * {@code radiusBlocks}, reduced to {@link #maxScanRadiusBlocks()} if it exceeds it.
     *
     * <p>Clamping lives here rather than at the call site so that every scan in the layer clamps the
     * same way, and so an {@link AiScan} can report a shrunken radius with one source of truth for why
     * it shrank.
     *
     * @param radiusBlocks the radius a body asked for
     * @return the radius it may have
     * @throws IllegalArgumentException if {@code radiusBlocks} is not a finite, non-negative number
     */
    public double clampScanRadius(double radiusBlocks) {
        if (!Double.isFinite(radiusBlocks) || radiusBlocks < 0.0D) {
            throw new IllegalArgumentException(
                    "A scan radius must be finite and non-negative but was " + radiusBlocks);
        }
        return Math.min(radiusBlocks, this.maxScanRadiusBlocks);
    }
}
