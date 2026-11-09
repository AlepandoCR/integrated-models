package pandastic.studio.models.internal.config;

import pandastic.studio.core.service.api.config.ConfigFormat;
import pandastic.studio.core.service.api.config.ConfigValue;
import pandastic.studio.core.service.api.config.Configurable;

/**
 * Declaration of {@code vehicle.yml}: how fast anything a plugin calls a vehicle may go, how often the
 * server decides where it is, and how far it may be moved without paying for a teleport.
 *
 * <h2>Why a vehicle is bounded at all</h2>
 * Because a vehicle is the one thing in this plugin that writes positions, and a position write is the
 * operation a regionised server is strictest about. Everything else here renders: a rig that is tuned
 * badly looks wrong and costs packets. A vehicle that is tuned badly crosses a region boundary every tick,
 * and the cost of that is a scheduled teleport per tick per vehicle — which removes the entity and its
 * whole passenger tree from the world and re-adds it, twenty times a second.
 *
 * <p>So these keys are not a safety rail around a plugin that might misbehave. They are the operator's
 * half of a negotiation: a {@link pandastic.studio.models.api.vehicle.VehicleProfile} declares what it
 * wants and these say what it may have, with a refusal naming the component and the bound.
 *
 * <h2>Two of them are statements about other files</h2>
 * {@link #maxSpeedBlocksPerTick()} and {@link #maxBoostMultiplier()} multiply together into the fastest
 * per-tick displacement this server permits, and that figure has to clear two thresholds that live
 * elsewhere:
 * <ul>
 *   <li>{@code maxStepBlocks} in {@code simulation.yml}, which is where the pose pipeline decides a
 *       position delta was a teleport rather than movement. Above it, the gait of a vehicle at full speed
 *       is fed a motionless owner — the wheels stop turning exactly when the vehicle is going fastest,
 *       which is as confusing a symptom as this project has;</li>
 *   <li>{@link #maxLocalStepBlocks()} in this file, above which every ordinary step becomes a teleport.</li>
 * </ul>
 * Both are checked by {@link ConfigMapper} and both name the other key, because the fix might be in
 * either.
 *
 * <h2>Not to be confused with its namesake</h2>
 * {@link pandastic.studio.models.internal.vehicle.VehicleSettings} is a different type with the same
 * simple name: it is the assembled, already-validated bundle the vehicle layer reads. This one is the
 * <em>file</em> — the keys, the comments and the defaults. The project has the same deliberate shadow for
 * render, simulation and AI; nothing outside this package should need to name this interface, because
 * what the rest of the plugin sees is {@link ConfigSnapshot}.
 *
 * @see ConfigMapper
 * @see ConfigSnapshot
 */
@Configurable(name = "VehicleConfig", format = ConfigFormat.YAML)
public interface VehicleSettings {

    /**
     * Fastest a profile may travel, in blocks per tick, before its boost.
     *
     * <p>A tick is a twentieth of a second, so this figure times twenty is blocks per second: a sprinting
     * player manages about 0.28, a horse at a gallop about 0.65. The default sits between the two, which
     * makes the fastest permitted vehicle noticeably quicker than running and slower than the best horse —
     * a deliberately conservative ceiling, because raising it is one edit and discovering why it had to be
     * raised is not.
     *
     * @return the speed ceiling, in blocks per tick
     */
    @ConfigValue(comment = "Fastest a vehicle profile may travel, in blocks per tick, before its boost. Times 20 for blocks per second: a sprinting player is about 0.28 and a galloping horse about 0.65. Raising this may require raising maxStepBlocks in simulation.yml and maxLocalStepBlocks below; both are checked and the refusal names them.")
    default float maxSpeedBlocksPerTick() {
        return 0.5F;
    }

    /**
     * Largest change of speed a profile may ask for, in blocks per tick squared.
     *
     * <p>Applies to the acceleration and the braking alike. A value at or above
     * {@link #maxSpeedBlocksPerTick()} permits instant top speed, which is legal and is exactly what a
     * lift or a conveyor wants; the default allows a vehicle to reach its own top speed in a few ticks,
     * which is what feels like a machine rather than a trigger.
     *
     * @return the acceleration ceiling, in blocks per tick squared
     */
    @ConfigValue(comment = "Largest change of speed a profile may ask for, in blocks per tick squared, for acceleration and braking alike. At or above maxSpeedBlocksPerTick it permits instant top speed, which is what a lift wants.")
    default float maxAccelerationBlocksPerTickSquared() {
        return 0.2F;
    }

    /**
     * Fastest a profile may rotate, in degrees per tick.
     *
     * <p>The default is a full turn in two seconds. The ceiling worth caring about is not comfort but the
     * renderer: a pose is emitted at the configured emission rate with an interpolation window matched to
     * it, so a vehicle that yaws much faster than a few degrees per emission has a visual that is
     * permanently mid-interpolation and therefore permanently behind its own hitbox.
     *
     * @return the turn rate ceiling, in degrees per tick
     */
    @ConfigValue(comment = "Fastest a vehicle profile may rotate, in degrees per tick. 9 is a full turn in two seconds. Much faster than a few degrees per pose emission and the model is permanently mid-interpolation, so the visual lags the hitbox.")
    default float maxTurnRateDegreesPerTick() {
        return 9F;
    }

    /**
     * Largest multiple of the forward top speed the sprint key may unlock.
     *
     * <p>One means the boost key does nothing, which is the neutral multiplier rather than a disabled
     * feature. The product of this and {@link #maxSpeedBlocksPerTick()} is what the two cross-checks in
     * the class note are about, so this is the key that most often has to come down when one of them
     * refuses.
     *
     * @return the boost ceiling, as a multiplier
     */
    @ConfigValue(comment = "Largest multiple of top speed the sprint key may unlock. 1 means the boost key does nothing. This times maxSpeedBlocksPerTick is the fastest per-tick step the server will see, which is what the cross-file checks are about.")
    default float maxBoostMultiplier() {
        return 2.5F;
    }

    /**
     * Ticks between two decisions about where a vehicle is.
     *
     * <p>One is the useful value and the default. The rider's input only changes once per client tick, so
     * deciding more often than that would read the same keys again; deciding less often makes the vehicle
     * respond late by exactly this many ticks, which a rider feels immediately — unlike the pose
     * sampler's period, where the same trade buys a gait that lags and nothing more.
     *
     * <p>Raising it does not make the vehicle slower, because the solver integrates real elapsed time
     * rather than a step count. It makes it coarser: the same distance covered in fewer, larger jumps.
     *
     * @return the control period, in ticks
     */
    @ConfigValue(comment = "Ticks between two decisions about where a vehicle is. One is the useful value: rider input only changes once per client tick. Raising it makes the vehicle respond late by that many ticks and move in fewer, larger jumps - not slower, because elapsed time is measured rather than assumed.")
    default int controlPeriodTicks() {
        return 1;
    }

    /**
     * Longest gap, in ticks, that one control step will integrate across.
     *
     * <p>The guard on a server that stalled. A repeating task's period is a request rather than a
     * guarantee, so after a long pause the first step back sees the whole gap — and integrating it
     * faithfully teleports the vehicle to where it would have been, through whatever was in the way.
     * Clamping instead means a stalled server loses distance rather than gaining a vehicle inside a wall.
     *
     * <p>Must be at least {@link #controlPeriodTicks()}, or every ordinary step is clamped and the
     * vehicle silently runs slow. {@link ConfigMapper} refuses that pairing.
     *
     * @return the step ceiling, in ticks
     */
    @ConfigValue(comment = "Longest gap, in ticks, that one control step will integrate across. The guard on a server that stalled: integrating the whole gap faithfully would move the vehicle through whatever was in the way. Must be at least controlPeriodTicks.")
    default float maxStepTicks() {
        return 4F;
    }

    /**
     * Furthest a single in-place position write may reach, in blocks.
     *
     * <p>A platform constraint with a margin, not a comfort setting. A write is legal only inside the
     * chunks the ticking region already owns and a ticking region cannot expand that set mid-tick, so a
     * displacement that might leave the owned chunks has to go through the asynchronous teleport instead.
     * A chunk is sixteen blocks wide and an entity sits somewhere inside one, so anything approaching
     * that width is a write that depends on where in its chunk the vehicle happened to be.
     *
     * <p>Lowering it costs teleports; raising it risks a write outside the owned set, which fails
     * silently on this platform rather than throwing.
     *
     * @return the local step ceiling, in blocks
     */
    @ConfigValue(comment = "Furthest a single in-place position write may reach, in blocks. Beyond this a step goes through the asynchronous teleport, because a ticking region cannot expand its owned chunk set mid-tick. A chunk is 16 wide and the entity sits somewhere inside one, so values near 16 depend on where.")
    default float maxLocalStepBlocks() {
        return 4F;
    }

    /**
     * Speed below which a vehicle is treated as stopped, in blocks per tick.
     *
     * <p>Friction approaches zero geometrically and never reaches it, so without a floor a vehicle that
     * stopped moving minutes ago still writes a position every tick for a displacement no client could
     * resolve. The figure wants to be small enough that nobody sees the vehicle stop short and large
     * enough that the tail of the decay is cut off quickly: the default is a thousandth of a block a
     * tick, which is a fiftieth of a block a second.
     *
     * @return the rest threshold, in blocks per tick
     */
    @ConfigValue(comment = "Speed below which a vehicle is treated as stopped, in blocks per tick. Friction decays geometrically and never reaches zero, so without this a vehicle that stopped minutes ago still writes a position every tick for a displacement nobody can see.")
    default float restSpeedBlocksPerTick() {
        return 0.001F;
    }
}
