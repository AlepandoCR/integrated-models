package pandastic.studio.models.internal.config;

import pandastic.studio.core.service.api.config.ConfigFormat;
import pandastic.studio.core.service.api.config.ConfigValue;
import pandastic.studio.core.service.api.config.Configurable;

/**
 * Declaration of {@code ai.yml}: how much behaviour one creature may be given, and how loudly a broken
 * behaviour is allowed to complain before it is stopped.
 *
 * <h2>Why a plan is bounded at all</h2>
 * Because every number in a plan is multiplied by the creature count before the server pays for it. A
 * plan with forty behaviours in its active activity is forty start tests per creature per tick; a sensor
 * with a sixty-four block radius is a box query of that size per creature per period. Neither figure is
 * wrong in the abstract and both are wrong at scale, and the person who knows which is which is the
 * operator running the server rather than the plugin that wrote the plan.
 *
 * <p>So this file is not a safety rail around a plugin that might misbehave. It is the operator's half
 * of a negotiation: a plan declares what it wants and these keys say what it may have, with a refusal
 * naming the key so the conversation can continue.
 *
 * <h2>Where the money goes, in order</h2>
 * <ol>
 *   <li>{@link #maxSensorPeriodTicks()} and {@link #minSensorPeriodTicks()} — the period is the single
 *       biggest lever here. It multiplies the scan cost by the creature count, and halving it doubles
 *       the bill.</li>
 *   <li>{@link #maxScanRadiusBlocks()} — the scan cost itself, which grows with the volume searched.</li>
 *   <li>{@link #maxBehavioursPerActivity()} — the per-tick start tests of whichever activity is
 *       active.</li>
 * </ol>
 * Everything else bounds memory rather than time.
 *
 * <h2>Not to be confused with its namesake</h2>
 * {@link pandastic.studio.models.internal.ai.AiSettings} is a different type with the same simple name:
 * it is the assembled, already-validated bundle the AI layer reads. This one is the <em>file</em> — the
 * keys, the comments and the defaults. The project has the same deliberate shadow for render and
 * simulation; nothing outside this package should need to name this interface, because what the rest of
 * the plugin sees is {@link ConfigSnapshot}.
 *
 * @see ConfigMapper
 * @see ConfigSnapshot
 */
@Configurable(name = "AiConfig", format = ConfigFormat.YAML)
public interface AiSettings {

    /**
     * How many activity slots one plan may fill.
     *
     * <p>Bounded above by the number of slots the server has, which is twenty-six and frozen: an
     * activity is a registry object with a private constructor and the registry closes before any plugin
     * loads, so no configuration can produce more. A plan using more than a handful is usually one that
     * wanted memory conditions on fewer slots.
     *
     * @return the activity slot ceiling
     */
    @ConfigValue(comment = "How many activity slots one plan may fill. The server has 26 and the set is frozen, so this cannot usefully go higher; a plan wanting more usually wanted memory conditions on fewer slots.")
    default int maxActivities() {
        return 8;
    }

    /**
     * How many behaviours one activity may hold.
     *
     * <p>The direct multiplier on the per-tick cost of an active activity: the brain walks the list in
     * priority order and asks every behaviour that is not already running whether it wants to start.
     *
     * @return the per-activity behaviour ceiling
     */
    @ConfigValue(comment = "How many behaviours one activity may hold. Every behaviour in the active activity is a start test per creature per tick, so this is the direct multiplier on the per-tick cost.")
    default int maxBehavioursPerActivity() {
        return 12;
    }

    /**
     * Highest priority number a behaviour may claim.
     *
     * <p>Lower numbers run first, which is the server's convention. A ceiling rather than a limit on
     * anything real: it only bounds how sparsely a plan may number its fallback chain, and exists to
     * catch a plan using priorities as identifiers.
     *
     * @return the priority ceiling
     */
    @ConfigValue(comment = "Highest priority number a behaviour may claim. Lower numbers run first. Bounds nothing real - it catches a plan using priorities as identifiers rather than as an order.")
    default int maxPriority() {
        return 64;
    }

    /**
     * How many sensors one plan may run.
     *
     * <p>Each one is a repeating task retained per creature, so this is the number of scheduled tasks a
     * thousand creatures costs divided by a thousand.
     *
     * @return the sensor ceiling
     */
    @ConfigValue(comment = "How many sensors one plan may run. Each is a repeating scheduled task retained per creature, so a thousand creatures with three sensors is three thousand tasks.")
    default int maxSensors() {
        return 4;
    }

    /**
     * Fastest a sensor may run, in ticks.
     *
     * <p>One is legal and means a scan every tick per creature, which is what this key exists to let an
     * operator forbid. It is also the floor the platform itself imposes — anything below a tick is
     * rounded up — so a value under one would be a configured number that is not the number that runs.
     *
     * @return the sensor period floor, in ticks
     */
    @ConfigValue(comment = "Fastest a sensor may run, in ticks. One means a scan every tick per creature. The platform rounds anything below one tick up to one, so one is also the floor.")
    default int minSensorPeriodTicks() {
        return 1;
    }

    /**
     * Slowest a sensor may run, in ticks.
     *
     * <p>A ceiling rather than a courtesy. A sensor slower than the expiry of the memory it writes leaves
     * that memory absent most of the time, and the symptom is a behaviour that fires intermittently for
     * no visible reason — which is a far worse thing to debug than a sensor that is simply too fast.
     *
     * @return the sensor period ceiling, in ticks
     */
    @ConfigValue(comment = "Slowest a sensor may run, in ticks. A sensor slower than the expiry of the memory it writes leaves that memory absent most of the time, which presents as a behaviour firing intermittently for no reason.")
    default int maxSensorPeriodTicks() {
        return 100;
    }

    /**
     * Widest a scan may reach, in blocks.
     *
     * <p>Clamped rather than refused, unlike every other key here, and the asymmetry is deliberate: a
     * behaviour asking to look too far should get the operator's answer and be told what it got, because
     * it is already being told the same thing by the region boundary. A refusal would make a creature
     * standing in the open behave differently from one near a boundary for the same plan.
     *
     * @return the scan radius ceiling, in blocks
     */
    @ConfigValue(comment = "Widest a scan may reach, in blocks. Clamped rather than refused: a behaviour is already told when a scan was narrowed, because a region boundary narrows one too.")
    default float maxScanRadiusBlocks() {
        return 32F;
    }

    /**
     * Longest a memory may be remembered for, in ticks.
     *
     * <p>A memory that expires in a year is a memory that never expires, and the two want different
     * words: the server has an explicit no-expiry form and a plan that meant it should use that. The
     * default is one hour of game time, which is longer than any decision a creature makes.
     *
     * @return the memory expiry ceiling, in ticks
     */
    @ConfigValue(comment = "Longest a memory may be remembered for, in ticks. 72000 is one hour of game time. A memory that expires in a year is a memory that never expires, and the server has an explicit no-expiry form for that.")
    default int maxMemoryExpiryTicks() {
        return 72_000;
    }

    /**
     * How many distinct memories one plan may declare.
     *
     * <p>Each one is a slot in every creature's brain, seeded when the brain is built. Bounding it bounds
     * the per-creature memory footprint of a plan, which is the only cost here that does not scale with
     * ticks.
     *
     * @return the memory slot ceiling
     */
    @ConfigValue(comment = "How many distinct memories one plan may declare. Each is a slot in every creature's brain, so this bounds the per-creature footprint of a plan.")
    default int maxMemoriesPerPlan() {
        return 32;
    }

    /**
     * Seconds between repeat reports of one failing behaviour or sensor.
     *
     * <p>A behaviour that throws throws every tick, which without a throttle is twenty stack traces a
     * second per creature: it fills the disk, pushes every other line out of the log, and costs more time
     * formatting traces than the AI layer costs running. The suppressed count is carried into the next
     * report rather than dropped, because "this failed 1 800 times" and "this failed once" call for
     * different responses and look identical without it.
     *
     * @return the minimum interval between reports, in seconds
     */
    @ConfigValue(comment = "Seconds between repeat reports of one failing behaviour or sensor. A broken behaviour throws every tick, so without this it is twenty stack traces a second per creature.")
    default float faultLogIntervalSeconds() {
        return 5F;
    }

    /**
     * Consecutive failures after which a behaviour or sensor is stopped.
     *
     * <p>The half of the failure policy that is about the creature rather than the log. A behaviour that
     * fails is still the highest-priority candidate the brain can consider, so it keeps being chosen and
     * nothing below it ever gets a turn: the creature is not merely noisy, it is stuck, and will be until
     * the server restarts. Stopping the body leaves a creature missing part of its plan, which is a
     * degraded creature rather than a frozen one.
     *
     * <p>Counted consecutively, so a success resets it. A behaviour failing once an hour over something
     * transient should not be stopped on its tenth hour.
     *
     * @return the consecutive failure limit
     */
    @ConfigValue(comment = "Consecutive failures after which a behaviour or sensor is stopped. A failing behaviour is still the highest-priority candidate the brain considers, so leaving it in place keeps the creature stuck rather than merely noisy. A success resets the count.")
    default int faultsBeforeDisable() {
        return 5;
    }
}
