package pandastic.studio.models.internal.config;

import pandastic.studio.core.service.api.config.ConfigFormat;
import pandastic.studio.core.service.api.config.ConfigValue;
import pandastic.studio.core.service.api.config.Configurable;

/**
 * Declaration of {@code vehicle.yml}: how fast anything a plugin calls a vehicle may go, how far it may
 * lean and climb in answer to what it is driving over, how often the server decides where it is, how far
 * it may be moved without paying for a teleport, and how many riders one may carry.
 *
 * <h2>The terrain keys come in three groups, and the third is about cost</h2>
 * The {@code terrain.sample*} keys do not change what a vehicle does about the ground — they decide how
 * often it <em>looks</em>. They are the only keys in this file about cost, and they are here because the
 * terrain reaction is the only thing in this plugin that reads blocks, which is the one cost a regionised
 * server cannot let anybody move off the owning thread. See {@link #terrainSampleMoveDeadbandBlocks()}.
 *
 * <h2>The other two halves</h2>
 * The {@code terrain.max*} and {@code terrain.minAngleBlendPerTick} keys are <em>ceilings</em>: the
 * server's statement about how far any vehicle on it may lean, pitch and climb, which is the same kind of
 * statement {@link #maxSpeedBlocksPerTick()} makes. The {@code terrain.<kind>*} keys are what a
 * vehicle whose plugin declares no terrain response <em>inherits</em>, so a server can retune every ground
 * vehicle it runs without the plugin that ships them being rebuilt. Each default has to be a value the
 * ceilings admit, and {@link ConfigMapper} checks every pair — for the reason {@code physics.yml}'s own
 * defaults are checked against its bounds: a vehicle that inherits a default is then validated against
 * those ceilings, so a ceiling narrowed past its own default breaks every vehicle of that kind.
 *
 * <h2>Two keys here are read by the rig loader</h2>
 * {@link #maxSeats()} and {@link #maxSeatOffsetBlocks()} bound a {@code seats:} section, which lives in
 * a rig file rather than in this one. They are in this file anyway, because what they describe is a
 * vehicle's passenger budget and the operator looking for it will look here — the same reasoning that
 * puts the rig loader's clamp margin in {@code physics.yml} rather than in a file of its own.
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

    // =============================================================================================
    // Terrain: how far a vehicle's model may lean, pitch and climb, and what each kind does unless
    // its own plugin says otherwise.
    //
    // Two halves with two different owners, which is why they are two groups of keys rather than
    // one. The six ceilings are the server's statement: how far ANY vehicle on it may lean before it
    // reads as broken geometry rather than as physics, and how long a climb may take before it reads
    // as lag. The defaults below them are what a vehicle whose plugin declares no terrain response
    // inherits, so that retuning every ground vehicle on the server is an edit here rather than a
    // rebuild of the plugin that ships them. A profile that does declare its own response keeps it.
    //
    // Every angle is a CEILING reached at a full-rate input, never a constant. bankDegrees is the
    // roll at a full-rate turn and the solver scales it by how hard the vehicle is actually turning,
    // which is what makes one number enough for "a tight turn leans further than a gentle one".
    //
    // There is deliberately no key for a term its kind cannot honour. A ground vehicle's pitch is the
    // hill it is on, so it has no acceleration pitch; nothing but a ground vehicle has a surface
    // beneath it, so nothing else has a slope pitch, a step height or a step duration. A key no kind
    // can read would be worse than no key at all: an operator would set it and watch nothing happen.
    // =============================================================================================

    /**
     * Furthest any vehicle may roll into a turn, in degrees.
     *
     * <p>A quarter turn would put the model on its side and past that it is upside down, so the useful
     * ceiling is well below one: the default is an eighth of a turn, which is already a fighter aircraft
     * rather than a cart. Zero is legal and means this server permits no banking at all, which is what
     * {@code VehicleBounds.NO_TERRAIN} expressed before these keys existed.
     *
     * @return the roll ceiling, in degrees
     */
    @ConfigValue(fieldName = "terrain.maxBankDegrees",
            comment = "Furthest any vehicle may roll into a turn, in degrees. The angle is reached at a full-rate turn and scaled below it, so this is the lean of the tightest turn rather than a constant. A quarter turn is a model lying on its side; zero permits no banking at all.")
    default float terrainMaxBankDegrees() {
        return 45F;
    }

    /**
     * Furthest any vehicle may pitch to follow the ground under it, in degrees.
     *
     * <p>The steepest terrain a player can build is a block per block, which is 45 degrees, so a ceiling
     * there is "as steep as the world gets" rather than an invented figure. Honoured only for the one
     * kind that has a surface beneath it.
     *
     * @return the slope pitch ceiling, in degrees
     */
    @ConfigValue(fieldName = "terrain.maxSlopePitchDegrees",
            comment = "Furthest any vehicle may pitch to follow the ground under it, in degrees. The steepest terrain anybody can build is a block per block, which is 45 degrees, so that is as steep as the world gets. Only ground vehicles have a surface beneath them to take an angle from.")
    default float terrainMaxSlopePitchDegrees() {
        return 45F;
    }

    /**
     * Furthest any vehicle may pitch under its own acceleration, in degrees.
     *
     * <p>Lower than the slope ceiling on purpose, because nothing in the world justifies this one: a
     * slope pitch is the ground the vehicle is lying along and can be checked against it, while an
     * acceleration pitch is expression — a boat squatting at the stern, a submarine nosing into a dive.
     * Thirty degrees is already a submarine diving steeply.
     *
     * @return the acceleration pitch ceiling, in degrees
     */
    @ConfigValue(fieldName = "terrain.maxAccelerationPitchDegrees",
            comment = "Furthest any vehicle may pitch under its own acceleration, in degrees. Lower than the slope ceiling on purpose: a slope pitch is the ground the model lies along, while this one is expression - a boat squatting at the stern, a submarine nosing into a dive. Ground vehicles ignore it, because a car's pitch is the hill it is on.")
    default float terrainMaxAccelerationPitchDegrees() {
        return 30F;
    }

    /**
     * Highest step any vehicle may climb, in blocks.
     *
     * <p>A block and a half, because a block is what the game itself steps: a vanilla horse climbs one
     * and nothing climbs two. Above it a vehicle is not climbing a step, it is flying over a wall, and
     * the honest way to ask for that is a flyer. Zero is legal and means this server permits no climbing.
     *
     * @return the step ceiling, in blocks
     */
    @ConfigValue(fieldName = "terrain.maxStepHeightBlocks",
            comment = "Highest step any vehicle may climb, in blocks. A block is what the game itself steps - a vanilla horse climbs one and nothing climbs two - so above a block and a half a vehicle is flying over a wall rather than climbing a step. Zero permits no climbing at all.")
    default float terrainMaxStepHeightBlocks() {
        return 1.5F;
    }

    /**
     * Longest a climb may be spread over, in ticks.
     *
     * <p>The duration is what makes a climb smooth: a display interpolates toward the transform it was
     * last given over the window it was given, so a whole block applied in one tick gives the client one
     * frame to cover it and snaps however good the arithmetic was. Three to four ticks reads as a
     * suspension taking the step.
     *
     * <p>The ceiling is about the other end of that trade. A climb spread over half a second finishes
     * well after the vehicle has driven past the step it was climbing, so the model is still rising while
     * its hitbox is already level — which reads as lag rather than as weight.
     *
     * @return the climb duration ceiling, in ticks
     */
    @ConfigValue(fieldName = "terrain.maxStepDurationTicks",
            comment = "Longest a climb may be spread over, in ticks. The duration is what buys the smoothness: a display interpolates over the window it was given, so a whole block in one tick snaps. Past half a second the model is still rising after the vehicle has driven off the step, which reads as lag rather than as weight.")
    default int terrainMaxStepDurationTicks() {
        return 10;
    }

    /**
     * Slowest any vehicle may approach a new angle, as a fraction of the remaining difference per tick.
     *
     * <p>A floor rather than a ceiling, because this is the one terrain term where the <em>small</em>
     * value is the dangerous one: at a fiftieth per tick a model needs about two and a half seconds to
     * get most of the way to a lean, by which point the turn that caused it is over and the model is
     * leaning into a straight. One is legal and snaps.
     *
     * @return the blend floor, as a fraction per tick
     */
    @ConfigValue(fieldName = "terrain.minAngleBlendPerTick",
            comment = "Slowest any vehicle may approach a new angle, as a fraction of the remaining difference closed each tick. A floor and not a ceiling: this is the term where the small value is the dangerous one, because a model that takes seconds to reach a lean is still leaning into a turn that ended. 1 snaps immediately.")
    default float terrainMinAngleBlendPerTick() {
        return 0.02F;
    }

    // =============================================================================================
    // How often the ground is read.
    //
    // These five keys are the only ones in this file about COST rather than about behaviour, and they
    // are here because the terrain reaction is the only thing in this plugin that reads BLOCKS. One
    // reading walks the column under the vehicle and the column one block ahead of it: five block
    // reads on flat ground at the shipped reach, seven at worst. At one control step a tick that is a
    // hundred block reads a second per vehicle, and the figure that matters is that times the number
    // of vehicles on the server.
    //
    // None of it can be made asynchronous. A block read is legal only on the thread that owns the
    // chunk, and on a regionised server that is a hard rule with no deferred form - so the only lever
    // is reading LESS. The terrain under a vehicle does not change while the vehicle has not moved,
    // which is what these keys exploit: a reading is kept until the vehicle has moved far enough to
    // be over different blocks, turned far enough to be looking at a different column, or held it for
    // long enough that the world might have changed instead.
    //
    // What they do NOT need is a second interpolator. terrain.<kind>AngleBlendPerTick already moves
    // every angle a fraction of the remaining difference per measured tick, so between two readings
    // the model keeps travelling toward the angle the older one implies - at a time constant of
    // several ticks, which is longer than the interval below. A reading taken less often changes when
    // the TARGET moves, not how smoothly the model gets there.
    // =============================================================================================

    /**
     * How far a vehicle must move before its ground is read again, in blocks.
     *
     * <p>The key that makes a parked vehicle free, which is most vehicles most of the time. The distance
     * is measured in three dimensions on purpose: a vehicle climbing a step moves almost entirely
     * vertically, and that is exactly the moment the ground beneath it has changed.
     *
     * <p>Zero reads on every step the interval allows. The ceiling is one block, and it is structural
     * rather than a matter of taste — the probe looks one column ahead, so a vehicle that has moved a
     * whole column is standing in the column its reading called "ahead". Past that the reading is not
     * stale, it is about somewhere else, and a vehicle re-reads regardless of what this key says.
     *
     * @return the deadband, in blocks
     */
    @ConfigValue(fieldName = "terrain.sampleMoveDeadbandBlocks",
            comment = "How far a vehicle must move before the blocks under it are read again, in blocks, measured in three dimensions. This is what makes a parked vehicle cost nothing: the ground does not change while the vehicle has not moved. Zero reads on every step the interval allows. At most one block, which is the probe's own look-ahead - past a whole column the reading describes ground the vehicle has already driven into, and it is re-read whatever this says.")
    default float terrainSampleMoveDeadbandBlocks() {
        return 0.25F;
    }

    /**
     * How far a vehicle must turn before its ground is read again, in degrees.
     *
     * <p>Its own term rather than part of the distance above, because the column a probe looks at is
     * chosen by the heading: a vehicle turning on the spot has not moved an inch and is looking at
     * different ground. Fifteen degrees moves the probe's own target about a quarter of a block
     * sideways, which is about when the column it lands in can change.
     *
     * @return the turn deadband, in degrees
     */
    @ConfigValue(fieldName = "terrain.sampleTurnDeadbandDegrees",
            comment = "How far a vehicle must turn before the blocks under it are read again, in degrees. Its own key rather than part of the distance, because which column counts as 'ahead' is chosen by the heading - a vehicle turning on the spot has not moved and is looking somewhere else. 15 degrees moves the probe's target about a quarter of a block.")
    default float terrainSampleTurnDeadbandDegrees() {
        return 15F;
    }

    /**
     * Fewest ticks between two readings, whatever the vehicle did in between.
     *
     * <p>What decouples the sample rate from the control rate. The loop decides where a vehicle is every
     * tick because a rider's input changes that often; the ground does not, and a vehicle at full speed
     * crosses the deadband every single step — so without this key the deadband saves nothing for exactly
     * the vehicles that are costing the most.
     *
     * <p>Zero imposes no minimum. Raising it does not make the model's lean coarser, because the blend
     * keeps running between readings; it makes the vehicle notice a change in the ground this many ticks
     * late.
     *
     * @return the minimum interval, in ticks
     */
    @ConfigValue(fieldName = "terrain.sampleMinIntervalTicks",
            comment = "Fewest ticks between two readings of the ground, whatever the vehicle did in between. This is what decouples the sample rate from the control rate: a rider's input changes every tick, the ground does not, and a vehicle at full speed crosses the deadband on every step. Zero imposes no minimum. It does not make the lean coarser - the angle blend keeps running between readings - it makes the vehicle notice new ground this many ticks late.")
    default int terrainSampleMinIntervalTicks() {
        return 2;
    }

    /**
     * After how many ticks a reading is taken again regardless of the deadband.
     *
     * <p>The other half of the deadband, and the reason it is safe: the thing that changed may be the
     * world rather than the vehicle. A player builds a ramp under a parked cart, and nothing about the
     * cart's own motion will ever notice. Two seconds is the default, so a parked vehicle costs one
     * reading every two seconds instead of twenty a second.
     *
     * <p>Must not be below {@link #terrainSampleMinIntervalTicks()}, or a reading expires before the
     * interval it was waiting for and the deadband suppresses nothing. {@link ConfigMapper} refuses that
     * pairing.
     *
     * @return the reading's lifetime, in ticks
     */
    @ConfigValue(fieldName = "terrain.sampleMaxAgeTicks",
            comment = "After how many ticks the ground is read again even though the vehicle has not moved. The other half of the deadband and the reason it is safe: what changed may be the world rather than the vehicle, and a ramp built under a parked cart is something the cart's own motion will never notice. 40 is two seconds. Must not be below sampleMinIntervalTicks.")
    default int terrainSampleMaxAgeTicks() {
        return 40;
    }

    /**
     * Whether a vehicle no viewer can see reads any blocks at all.
     *
     * <p>The largest saving here and the only one with a cost worth stating. What the terrain reaction
     * produces is a lean, a pitch and a step up on a <em>model</em>, so for a vehicle whose model no
     * viewer holds the right number of block reads is zero — and on a server with vehicles parked in
     * unvisited corners, that is most of them.
     *
     * <p>The cost is that an unseen vehicle does not climb, because the step-up is carried by the same
     * reading as the lean and arming one requires a reading taken that step. So an unobserved vehicle
     * driving at a kerb stops at it. That repairs itself on observation: the first step on which any
     * viewer holds the model takes a reading and arms the climb, so a player arriving finds a vehicle
     * that drives on rather than one that is stuck. Switch it off for a server whose vehicles drive
     * themselves across terrain nobody is watching.
     *
     * @return whether to read the ground only for a vehicle somebody can see
     */
    @ConfigValue(fieldName = "terrain.sampleOnlyWhenSeen",
            comment = "Whether a vehicle whose model no viewer holds reads any blocks at all. The biggest saving: what the terrain reaction produces is a lean, a pitch and a step up on a model, so an unseen vehicle's right cost is zero. The price is that an unseen vehicle does not climb - it stops at a kerb - and repairs itself the first step somebody can see it. Switch it off for vehicles that drive themselves across terrain nobody is watching.")
    default boolean terrainSampleOnlyWhenSeen() {
        return true;
    }

    // =============================================================================================
    // The per-kind defaults.
    //
    // Fourteen keys rather than four kinds times six terms, because ten of those twenty-four would be
    // a term the kind ignores. What each kind gets a key for is exactly what it honours:
    //
    //   ground     bank, slope pitch, step height, step duration, blend
    //   flyer      bank, acceleration pitch, blend
    //   submarine  bank, acceleration pitch, blend
    //   boat       bank, acceleration pitch, blend
    //
    // Bank and blend are every kind's, because all four turn and all four have an angle to reach.
    // The slope and the step are the ground vehicle's alone - it is the only kind with a surface
    // beneath it - and the acceleration pitch is every kind's but the ground vehicle's, whose pitch
    // is already the hill it is on and would fight a squat laid on top of it.
    //
    // Each figure has to be a value the ceilings above admit, for the reason physics.yml's defaults
    // do: a vehicle that inherits one is then validated against those ceilings, so an operator who
    // narrowed a ceiling past its own default has broken every vehicle of that kind on the server.
    // ConfigMapper checks each pair and names the default, because a narrowed ceiling was deliberate.
    // =============================================================================================

    /**
     * How far a ground vehicle rolls into a full-rate turn, in degrees.
     *
     * <p>Small, because a car on its suspension leans a few degrees and a cart on axles barely does. The
     * figure that reads as a motorcycle belongs on the vehicle's own profile, not on every ground vehicle
     * the server runs.
     *
     * @return the roll, in degrees
     */
    @ConfigValue(fieldName = "terrain.groundBankDegrees",
            comment = "How far a ground vehicle rolls into a full-rate turn, in degrees, unless its own plugin says otherwise. Small: a car on its suspension leans a few degrees and a cart on axles barely does.")
    default float defaultGroundBankDegrees() {
        return 8F;
    }

    /**
     * How far a ground vehicle pitches to follow the steepest slope it will take, in degrees.
     *
     * <p>Below the ceiling and deliberately: the ceiling is the steepest ground anybody can build, while
     * this is the slope a vehicle shipped with no opinion is expected to meet. A quarter of a turn would
     * be a vehicle climbing a staircase nose-first.
     *
     * @return the pitch, in degrees
     */
    @ConfigValue(fieldName = "terrain.groundSlopePitchDegrees",
            comment = "How far a ground vehicle pitches to follow the slope under it, in degrees, unless its own plugin says otherwise. The angle at the steepest slope it will take; gentler ground pitches it less.")
    default float defaultGroundSlopePitchDegrees() {
        return 25F;
    }

    /**
     * How high a step a ground vehicle climbs, in blocks.
     *
     * <p>One block, which is what the game itself steps: a player walks up a slab and a horse climbs a
     * full block, so a vehicle that cannot is a vehicle that gets stuck on a kerb. Zero disables climbing
     * for every ground vehicle that declares nothing.
     *
     * @return the step height, in blocks
     */
    @ConfigValue(fieldName = "terrain.groundStepHeightBlocks",
            comment = "How high a step a ground vehicle climbs, in blocks, unless its own plugin says otherwise. One block is what the game itself steps, so a vehicle below it gets stuck on a kerb. Zero disables climbing for every ground vehicle that declares nothing.")
    default float defaultGroundStepHeightBlocks() {
        return 1F;
    }

    /**
     * How many ticks a ground vehicle's climb is spread over.
     *
     * <p>Three, which is the low end of the window that reads as a suspension taking the step. One would
     * mean the climb is applied in a single tick, which is not "instant and smooth" but simply instant:
     * the client is given one frame to cover a whole block.
     *
     * @return the climb duration, in ticks
     */
    @ConfigValue(fieldName = "terrain.groundStepDurationTicks",
            comment = "How many ticks a ground vehicle's climb is spread over, unless its own plugin says otherwise. This is what buys the smoothness: the client interpolates across the window it is given, so 1 means the block is covered in a single frame and snaps. Three to four reads as a suspension taking the step.")
    default int defaultGroundStepDurationTicks() {
        return 3;
    }

    /**
     * Fraction of a ground vehicle's remaining angle difference closed each tick.
     *
     * <p>A fifth, so a lean is most of the way there in about a quarter of a second. This is a property
     * of the vehicle's <em>mass</em> rather than of how far it leans, which is why it is its own key: a
     * laden truck and a motorbike can bank to the same angle and have no business getting there at the
     * same rate.
     *
     * @return the blend, as a fraction per tick
     */
    @ConfigValue(fieldName = "terrain.groundAngleBlendPerTick",
            comment = "Fraction of a ground vehicle's remaining angle difference closed each tick, unless its own plugin says otherwise. A property of the vehicle's mass rather than of how far it leans: a laden truck and a motorbike can bank to the same angle and have no business getting there at the same rate.")
    default float defaultGroundAngleBlendPerTick() {
        return 0.2F;
    }

    /**
     * How far a flyer rolls into a full-rate turn, in degrees.
     *
     * <p>The largest of the four, because banking is the whole of a flyer's terrain response: it has no
     * surface under it to take a slope or a step from, so the only thing left to read as flight is the
     * roll into the turn. An aircraft that turns flat looks like it is sliding.
     *
     * @return the roll, in degrees
     */
    @ConfigValue(fieldName = "terrain.flyerBankDegrees",
            comment = "How far a flyer rolls into a full-rate turn, in degrees, unless its own plugin says otherwise. The largest of the four kinds, because banking is the whole of a flyer's terrain response: it has no surface beneath it, and an aircraft that turns flat reads as sliding.")
    default float defaultFlyerBankDegrees() {
        return 30F;
    }

    /**
     * How far a flyer pitches under full acceleration, in degrees.
     *
     * <p>Nose-up as it opens the throttle. Modest, because a flyer's pitch competes with the bank for the
     * same model: two large angles at once read as a crash rather than as a climb.
     *
     * @return the pitch, in degrees
     */
    @ConfigValue(fieldName = "terrain.flyerAccelerationPitchDegrees",
            comment = "How far a flyer pitches under full acceleration, in degrees, unless its own plugin says otherwise. Modest, because it competes with the bank for the same model and two large angles at once read as a crash rather than as a climb.")
    default float defaultFlyerAccelerationPitchDegrees() {
        return 10F;
    }

    /**
     * Fraction of a flyer's remaining angle difference closed each tick.
     *
     * <p>The quickest of the four: air is the lightest medium in the set and an aircraft rolls into a turn
     * in well under a second.
     *
     * @return the blend, as a fraction per tick
     */
    @ConfigValue(fieldName = "terrain.flyerAngleBlendPerTick",
            comment = "Fraction of a flyer's remaining angle difference closed each tick, unless its own plugin says otherwise. The quickest of the four kinds: air is the lightest medium in the set and an aircraft rolls into a turn in well under a second.")
    default float defaultFlyerAngleBlendPerTick() {
        return 0.25F;
    }

    /**
     * How far a submarine rolls into a full-rate turn, in degrees.
     *
     * <p>Between the flyer's and the boat's: a submerged hull is free to roll like an aircraft and is
     * held by far more water than a surface vessel pushes.
     *
     * @return the roll, in degrees
     */
    @ConfigValue(fieldName = "terrain.submarineBankDegrees",
            comment = "How far a submarine rolls into a full-rate turn, in degrees, unless its own plugin says otherwise. Between the flyer's and the boat's: a submerged hull is free to roll like an aircraft, and is held by far more water than a surface vessel pushes.")
    default float defaultSubmarineBankDegrees() {
        return 15F;
    }

    /**
     * How far a submarine pitches under full acceleration, in degrees.
     *
     * <p>The largest acceleration pitch of the four, because it is the one that carries meaning rather
     * than flavour: a submarine nose-down while it descends is the whole visual language of the thing,
     * and a submarine that dives level reads as a bug.
     *
     * @return the pitch, in degrees
     */
    @ConfigValue(fieldName = "terrain.submarineAccelerationPitchDegrees",
            comment = "How far a submarine pitches under full acceleration, in degrees, unless its own plugin says otherwise. The largest of the four, because it carries meaning rather than flavour: nose-down while descending is the whole visual language of a submarine, and one that dives level reads as a bug.")
    default float defaultSubmarineAccelerationPitchDegrees() {
        return 20F;
    }

    /**
     * Fraction of a submarine's remaining angle difference closed each tick.
     *
     * <p>The slowest of the four, which is the point: water resists, and a submarine that snaps to a new
     * attitude reads as weightless.
     *
     * @return the blend, as a fraction per tick
     */
    @ConfigValue(fieldName = "terrain.submarineAngleBlendPerTick",
            comment = "Fraction of a submarine's remaining angle difference closed each tick, unless its own plugin says otherwise. The slowest of the four, which is the point: water resists, and a submarine that snaps to a new attitude reads as weightless.")
    default float defaultSubmarineAngleBlendPerTick() {
        return 0.1F;
    }

    /**
     * How far a boat rolls into a full-rate turn, in degrees.
     *
     * <p>A hull heels outward as it turns, and the figure is small because the surface it floats on is
     * what stops it going further.
     *
     * @return the roll, in degrees
     */
    @ConfigValue(fieldName = "terrain.boatBankDegrees",
            comment = "How far a boat rolls into a full-rate turn, in degrees, unless its own plugin says otherwise. A hull heels outward as it turns, and the figure is small because the surface it floats on is what stops it going further.")
    default float defaultBoatBankDegrees() {
        return 12F;
    }

    /**
     * How far a boat pitches under full acceleration, in degrees.
     *
     * <p>Stern-down as it opens up and nose-down as it slows, which is the one terrain-reactive term a
     * surface vessel actually has: it cannot climb, and the water it sits on has no slope to follow.
     *
     * @return the pitch, in degrees
     */
    @ConfigValue(fieldName = "terrain.boatAccelerationPitchDegrees",
            comment = "How far a boat pitches under full acceleration, in degrees, unless its own plugin says otherwise. Stern-down as it opens up and nose-down as it slows - the one terrain-reactive term a surface vessel has, since it cannot climb and the water it sits on has no slope to follow.")
    default float defaultBoatAccelerationPitchDegrees() {
        return 8F;
    }

    /**
     * Fraction of a boat's remaining angle difference closed each tick.
     *
     * <p>Slower than a flyer's and quicker than a submarine's: a hull is half in the water it is resisted
     * by.
     *
     * @return the blend, as a fraction per tick
     */
    @ConfigValue(fieldName = "terrain.boatAngleBlendPerTick",
            comment = "Fraction of a boat's remaining angle difference closed each tick, unless its own plugin says otherwise. Slower than a flyer's and quicker than a submarine's: a hull is half in the water that resists it.")
    default float defaultBoatAngleBlendPerTick() {
        return 0.15F;
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

    /**
     * Most seats a single vehicle rig may declare.
     *
     * <p>A budget rather than a limit of the seating code: every seated player is an entity the vehicle
     * carries through every position write, and a write that moves a passenger tree costs the tree. The
     * default is the size of a cart — a boat seats two and a horse one — and raising it is one edit,
     * whereas discovering why it had to come back down is a report about lag.
     *
     * <p>Read by the <em>rig loader</em>, not by the vehicle layer: a seat is declared in a rig file, so
     * this is the key that decides what such a file may say. Seats past it are dropped from the end of
     * the list, which is the end riders would have been offered.
     *
     * @return the seat ceiling, per rig
     */
    @ConfigValue(comment = "Most seats one vehicle rig may declare. A budget, not a limit of the code: every seated player is an entity the vehicle carries through every position write. Seats past this are dropped from the end of the rig's list, which is the end riders would have been offered.")
    default int maxSeats() {
        return 4;
    }

    /**
     * Furthest a rider may be offset from their seat's locator, per axis, in blocks.
     *
     * <p>Symmetric, because an offset is a displacement and its sign is a direction. The figure is small
     * on purpose: a seat <em>is</em> a locator the rigger placed, so the locator is already where the
     * rider belongs and the offset only trims it — the half-block a saddle sits above a pivot, the
     * finger-width a hand rests off a handle. A rider who needs to be two blocks away has a locator in
     * the wrong place, and that is fixed in Blockbench rather than here; keeping the ceiling low is what
     * stops the rig file from becoming the place that mistake is expressed.
     *
     * <p>Like {@link #maxSeats()} this is read by the rig loader, which clamps an offset past it and
     * records a diagnostic naming the seat.
     *
     * @return the per-axis offset ceiling, in blocks
     */
    @ConfigValue(comment = "Furthest a rider may be offset from their seat's locator, per axis, in blocks. Symmetric. Small on purpose: the locator is already where the rider belongs and this only trims it. A rider needing more than a nudge has a locator in the wrong place, which is fixed in Blockbench.")
    default float maxSeatOffsetBlocks() {
        return 1F;
    }

    // =============================================================================================
    // The carrier: the entity a seated rider actually rides.
    //
    // A seat is a locator, and nobody can ride a point. So each occupied seat gets one invisible
    // entity that the player rides and that this plugin keeps on top of the locator. These four keys
    // are about that entity, and they are the only place in the configuration where a server's own
    // choice of entity shows up.
    // =============================================================================================

    /**
     * The entity type spawned as a rider's carrier.
     *
     * <p>An armour stand, and the project is designed around one: it can be made invisible, it can be
     * made a marker with no bounding box at all, and the server does not steer it from its first
     * passenger. That last property is not optional — a carrier type whose own class hands control to its
     * rider gives the <em>client</em> authority over where the seat is, which is the one failure this
     * plugin's whole vehicle design exists to avoid — so a boat, a horse, a pig, a strider or a happy
     * ghast named here is <strong>refused</strong>, and the seat is reported as unoccupiable rather than
     * silently handed over.
     *
     * <p>Changing it almost always means changing {@link #seatCarrierOffsetYBlocks()} too: every entity
     * type sits its passenger at its own height.
     *
     * @return the platform's own name for the entity type
     */
    @ConfigValue(comment = "Entity type spawned as the invisible carrier a seated rider rides. ARMOR_STAND is what this plugin is designed around: invisible, markerable, no bounding box, and not steered by its passenger. A type the server steers from its first passenger - boat, horse, pig, strider, happy ghast - is refused, because it would hand the seat's position to the rider's client. Changing this usually means changing seatCarrierOffsetYBlocks too.")
    default String seatCarrierEntityType() {
        return "ARMOR_STAND";
    }

    /**
     * Vertical trim applied to every carrier, in blocks.
     *
     * <p>Not a seat offset — that is the rigger's and lives in the rig file. This one compensates for the
     * carrier type's own passenger height, which is a property of the entity rather than of the model, so
     * it belongs to the server that chose the type. Zero is the honest default: it means "exactly where
     * the locator is", and an operator who sees riders floating or sunk by a consistent amount on every
     * vehicle has found this key rather than a rigging problem.
     *
     * @return the trim, in blocks
     */
    @ConfigValue(comment = "Vertical trim applied to every seat carrier, in blocks. Compensates for the carrier type's own passenger height, which is a property of the entity and not of the model - a seat's own offset belongs to the rig file. Riders consistently floating or sunk on every vehicle is this key, not a rigging problem.")
    default float seatCarrierOffsetYBlocks() {
        return 0F;
    }

    /**
     * Furthest a carrier may be moved in place in one step, in blocks.
     *
     * <p>The same platform constraint {@link #maxLocalStepBlocks()} describes, applied to the carriers:
     * past it the carrier goes through the asynchronous teleport, which is what a vehicle that just
     * crossed a region boundary needs and what an ordinary step must never pay for. It wants to be at
     * least as large as a vehicle's fastest step, or a vehicle at speed teleports its riders every tick.
     *
     * @return the carrier step ceiling, in blocks
     */
    @ConfigValue(comment = "Furthest a seat carrier may be moved in place in one step, in blocks. Past this it is relocated through the asynchronous teleport, which is what a vehicle that just crossed a region boundary needs and what an ordinary step must never pay for. Keep it at least as large as a vehicle's fastest single step.")
    default float seatMaxCarrierStepBlocks() {
        return 4F;
    }

    /**
     * Displacement below which a carrier is left where it is, in blocks.
     *
     * <p>A parked vehicle still animates, and an idle clip moves its locators by a fraction of a block
     * forever. Without a floor that costs a position packet per rider per tick for a movement nobody can
     * resolve. Zero is legal and means "follow everything", which is the right setting for a seat on a
     * bone-driven arm and the wrong one for a server with fifty parked carts.
     *
     * <p>Must stay below {@link #seatMaxCarrierStepBlocks()}: above it, every displacement is either
     * ignored or teleported and a carrier is never moved in place at all.
     *
     * @return the follow deadband, in blocks
     */
    @ConfigValue(comment = "Displacement below which a seat carrier is left alone, in blocks. A parked vehicle still animates, so without this an idle clip costs a position packet per rider per tick for movement nobody can see. Zero follows everything. Must stay below seatMaxCarrierStepBlocks.")
    default float seatFollowDeadbandBlocks() {
        return 0.002F;
    }

    // =============================================================================================
    // The seat chart: what a vehicle says about its seats when it binds.
    //
    // Every line below is MiniMessage, so colour and emphasis are the operator's. The chart is the
    // only place anybody finds out that a seat named a bone instead of a locator, or named nothing at
    // all, which is why it is on by default and why the failing states are coloured to be unmissable.
    // The whole chart is written at WARNING level when any seat is unusable, and at INFO otherwise.
    // =============================================================================================

    /**
     * Whether a vehicle logs its seat chart when it binds.
     *
     * <p>The whole verbosity switch. Off, a vehicle says nothing about its seats at all — including about
     * the ones that failed to resolve, which is the cost of switching it off and is deliberate: a server
     * that has read its chart once and wants its console back should be able to have it.
     *
     * @return whether to log the chart
     */
    @ConfigValue(comment = "Whether a vehicle logs its seat chart when it binds. On by default, because it is the only place an operator finds out that a seat named a bone instead of a locator. Off silences the whole chart, failed seats included.")
    default boolean logSeatChart() {
        return true;
    }

    /**
     * The line opening the chart. Placeholder: {@code title}.
     *
     * <p>Blank omits the box's top line and logs the title on its own, so the chart still says which rig
     * and which model it is about.
     *
     * @return the MiniMessage source
     */
    @ConfigValue(comment = "Line opening the seat chart. Placeholder: title. Blank logs the title on its own line instead. MiniMessage.")
    default String seatChartTop() {
        return "<dark_gray>┌──</dark_gray> <title>";
    }

    /**
     * What the chart is about. Placeholders: {@code rig}, {@code model}, {@code declared}.
     *
     * @return the MiniMessage source
     */
    @ConfigValue(comment = "Title of the seat chart. Placeholders: rig, model, declared (how many seats the rig declares). MiniMessage.")
    default String seatChartTitle() {
        return "<aqua>Seats</aqua> <dark_gray>·</dark_gray> <white><rig></white> "
                + "<dark_gray>on</dark_gray> <gray><model></gray> "
                + "<dark_gray>(<declared> declared)</dark_gray>";
    }

    /**
     * Drawn at the start of every seat line, which is what makes the box a box.
     *
     * @return the MiniMessage source
     */
    @ConfigValue(comment = "Drawn at the start of every seat line of the chart. MiniMessage.")
    default String seatChartRowPrefix() {
        return "<dark_gray>│</dark_gray> ";
    }

    /**
     * The seat's own name and its index. Placeholders: {@code seat}, {@code index}, {@code number}.
     *
     * <p>Seats are indexed and boarding fills the lowest free index, so the chart read top to bottom
     * answers "which seat does the first player to board get". The default shows the number for that
     * reason. {@code index} is the zero-based address a consumer passes to {@code Vehicle.board};
     * {@code number} is the same seat counted from one, which is what a player is told.
     *
     * @return the MiniMessage source
     */
    @ConfigValue(comment = "The seat-name column of a chart row. Placeholders: seat, index (zero-based, the address a plugin addresses the seat by) and number (the same seat counted from one, which is what a player is told). Boarding fills the lowest free index and the driver's seat is index 0, so this column read top to bottom is the order riders will fill the vehicle. MiniMessage.")
    default String seatChartSeatCell() {
        return "<dark_gray>#<number></dark_gray> <white><seat></white>";
    }

    /**
     * The state column for a seat that resolved to a locator — the only usable outcome.
     *
     * @return the MiniMessage source
     */
    @ConfigValue(comment = "State column for a seat that resolved to a locator, which is the only usable outcome. MiniMessage.")
    default String seatChartLocatorLabel() {
        return "<green>● locator</green>";
    }

    /**
     * The state column for a seat that resolved to a bone.
     *
     * <p>The rigger pointed at a bone, which is the mistake the locator policy exists to catch: a bone is
     * geometry the animation pass writes, so a rider on one would be moved by whichever clip is playing.
     * Coloured to be unmissable by default.
     *
     * @return the MiniMessage source
     */
    @ConfigValue(comment = "State column for a seat whose name turned out to be an ordinary bone. The rigger pointed at a bone: a seat must be a locator, because a bone is geometry the animation pass moves. Unmissable by default, on purpose. MiniMessage.")
    default String seatChartBoneLabel() {
        return "<red>✖ bone, not a locator</red>";
    }

    /**
     * The state column for a name the model does not carry at all.
     *
     * @return the MiniMessage source
     */
    @ConfigValue(comment = "State column for a seat naming something the model does not contain: a typo, a renamed element, or a locator nobody has added yet. MiniMessage.")
    default String seatChartUnresolvedLabel() {
        return "<red>✖ no such locator</red>";
    }

    /**
     * The role column for the seat the vehicle is steered from.
     *
     * @return the MiniMessage source
     */
    @ConfigValue(comment = "Role column for the seat the vehicle is steered from. At most one seat per rig. MiniMessage.")
    default String seatChartDriverLabel() {
        return "<gold>driver</gold>";
    }

    /**
     * The role column for every seat that is not the driver's.
     *
     * @return the MiniMessage source
     */
    @ConfigValue(comment = "Role column for a seat that is not the driver's. Its rider's input steers nothing. MiniMessage.")
    default String seatChartPassengerLabel() {
        return "<gray>passenger</gray>";
    }

    /**
     * The offset column. Placeholders: {@code x}, {@code y}, {@code z}.
     *
     * @return the MiniMessage source
     */
    @ConfigValue(comment = "Offset column of a chart row, for a seat that is nudged off its locator. Placeholders: x, y, z, in blocks. MiniMessage.")
    default String seatChartOffsetCell() {
        return "<dark_gray>offset</dark_gray> <gray><x> <y> <z></gray>";
    }

    /**
     * The offset column for a seat with no offset at all, which is the common case.
     *
     * @return the MiniMessage source
     */
    @ConfigValue(comment = "Offset column for a seat sitting exactly on its locator, which is the common case and reads better as a word than as three zeroes. MiniMessage.")
    default String seatChartOnLocatorLabel() {
        return "<dark_gray>on the locator</dark_gray>";
    }

    /**
     * The closing line. Placeholders: {@code usable}, {@code unusable}, {@code declared}.
     *
     * @return the MiniMessage source
     */
    @ConfigValue(comment = "Line closing the seat chart. Placeholders: usable, unusable, declared. MiniMessage.")
    default String seatChartSummary() {
        return "<dark_gray>└──</dark_gray> <green><usable> usable</green><dark_gray>,</dark_gray> "
                + "<red><unusable> unusable</red> <dark_gray>of <declared></dark_gray>";
    }

    /**
     * Extra line written only when at least one seat is unusable. Placeholders: {@code unusable},
     * {@code declared}.
     *
     * <p>Where the operator is told what to do about it. Blank omits it.
     *
     * @return the MiniMessage source
     */
    @ConfigValue(comment = "Extra line written only when at least one seat is unusable, which is where an operator is told what to do about it. Placeholders: unusable, declared. Blank omits it. MiniMessage.")
    default String seatChartUnusableNote() {
        return "<yellow>A seat must name a locator, never a bone: add the locator in Blockbench, "
                + "re-export the model, and reload. No locator means no seat - riders are turned away "
                + "rather than seated somewhere approximate.</yellow>";
    }

    /**
     * A line after the summary. Blank by default, because the summary already closes the box.
     *
     * @return the MiniMessage source
     */
    @ConfigValue(comment = "Line written after the summary. Blank by default, because the summary already closes the box. MiniMessage.")
    default String seatChartBottom() {
        return "";
    }

    /**
     * Spaces between two columns of the chart, on top of the widest cell in the left one.
     *
     * <p>The chart's columns are aligned from the rendered width of each cell, so this is the gap and not
     * the column width: an operator who re-colours a label does not have to re-count anything.
     *
     * @return the gap, in spaces
     */
    @ConfigValue(comment = "Spaces between two columns of the seat chart, on top of the widest cell in the left one. Columns are aligned from each cell's rendered width, so re-colouring a label needs no re-counting.")
    default int seatChartColumnGap() {
        return 2;
    }
}
