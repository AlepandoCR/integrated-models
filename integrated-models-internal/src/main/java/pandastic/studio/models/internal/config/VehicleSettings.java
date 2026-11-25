package pandastic.studio.models.internal.config;

import pandastic.studio.core.service.api.config.ConfigFormat;
import pandastic.studio.core.service.api.config.ConfigValue;
import pandastic.studio.core.service.api.config.Configurable;

/**
 * Declaration of {@code vehicle.yml}: how fast anything a plugin calls a vehicle may go, how often the
 * server decides where it is, how far it may be moved without paying for a teleport, and how many
 * riders one may carry.
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
