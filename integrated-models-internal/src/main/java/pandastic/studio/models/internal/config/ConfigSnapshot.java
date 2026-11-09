package pandastic.studio.models.internal.config;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.ai.AiBounds;
import pandastic.studio.models.api.locomotion.BindingBounds;
import pandastic.studio.models.api.locomotion.GaitTuning;
import pandastic.studio.models.api.math.IkTolerance;
import pandastic.studio.models.api.physics.DriverBounds;
import pandastic.studio.models.api.physics.PhysicsTuning;
import pandastic.studio.models.api.render.CadenceBounds;
import pandastic.studio.models.api.rig.ProfileBounds;
import pandastic.studio.models.api.rig.ProfileDefaults;
import pandastic.studio.models.api.rig.RigBounds;
import pandastic.studio.models.api.vehicle.VehicleBounds;
import pandastic.studio.models.internal.rig.RigDiagnostic;

/**
 * Every tunable value in this project, as one immutable object.
 *
 * <h2>One snapshot, not one per file</h2>
 * The files could each have been published through their own reference, and that would have been
 * cheaper to reload. It would also have been wrong. Several of the invariants this layer enforces span
 * two files — the renderer's dirty threshold has to sit at or above the solver's rest threshold, and
 * the loop rate has to keep up with the emission rate — so a reader holding physics from reload
 * <em>n</em> and render from reload <em>n−1</em> can observe a combination that no file on disk
 * describes and that the validator would have refused. A single reference makes that state
 * unrepresentable rather than merely unlikely, and it costs the simulation one volatile read per step
 * instead of one per file.
 *
 * <h2>The simulation reads it once per step</h2>
 * Not once per value. The loop runs at tens of hertz on an asynchronous thread; reading the reference
 * repeatedly within one step would let a reload land between two reads and give one step two different
 * sets of coefficients. Read it, hold it, finish the step.
 *
 * <h2>Why the api value types, and the nested records</h2>
 * Most of the components are immutable value types from the api module, which is what lets the solver,
 * the rig compiler and the renderer depend on the api rather than on this package. The nested records
 * carry the values the api has no type for, because nothing in the api needs them — a loop rate, a log
 * interval, a console toggle. They are grouped by file, and by consumer where one file feeds two layers
 * at two rates, so that the component list stays readable; and they expose plain primitives so a
 * consumer still ends up taking an {@code int}.
 *
 * @param revision     monotonically increasing publication counter; see {@link #revision()}
 * @param physics      solver calibration and step size
 * @param profileBounds bounds a rig's physics profile is validated against
 * @param profileDefaults coefficients a rig file inherits for anything it does not declare. Validated
 *                        against {@code profileBounds} before the snapshot exists, because the bounds
 *                        have to admit the defaults a rig file falls back to
 * @param rigBounds    bounds a rig as a whole is validated against
 * @param gait         walk cycle calibration
 * @param bindingBounds bounds a locomotion binding and a gait profile are validated against
 * @param cadenceBounds the interpolation window an emission cadence may choose from
 * @param driverBounds  the per-tick movement a driver sample treats as real
 * @param ikTolerance   the degenerate-target guard of the two-bone solver
 * @param render        render values with no api counterpart
 * @param simulation    simulation loop values with no api counterpart
 * @param diagnostics   console toggles
 * @param rig           values governing how a rig file is read
 * @param decoration    the ambient field a model with no entity under it is driven by
 * @param aiBounds      the limits an AI plan is validated against
 * @param ai            AI values with no api counterpart
 * @param vehicleBounds the limits a vehicle profile is validated against
 * @param vehicle       vehicle values with no api counterpart
 * @param menu          every word a menu says and every bound a menu control offers. One of the three
 *                      components that is a value type from another internal package rather than an api
 *                      type or a record declared here: it has one component per key and there are
 *                      seventy of them, so an options record beside it would be a second declaration of
 *                      the same seventy whose only job is to be copied into the first
 * @param view          what governs the culling decision. A value type from another internal package
 *                      for the same reason {@code menu} is, in the small: its eleven components are
 *                      exactly the eleven keys of {@code view.yml}, and it carries the arithmetic the
 *                      culler runs on them
 * @param binding       how often the region threads sample a bound model's anchor. Also the view
 *                      layer's own kind of type, and for the same reason — one component, one key
 * @param command       every line the reload command says. The fourth component that is a value type
 *                      from another internal package, for the reason {@code menu} is: it has one
 *                      component per key and nothing else, so an options record beside it would be a
 *                      second declaration of the same seven whose only job is to be copied into the
 *                      first
 */
public record ConfigSnapshot(
        long revision,
        @NotNull PhysicsTuning physics,
        @NotNull ProfileBounds profileBounds,
        @NotNull ProfileDefaults profileDefaults,
        @NotNull RigBounds rigBounds,
        @NotNull GaitTuning gait,
        @NotNull BindingBounds bindingBounds,
        @NotNull CadenceBounds cadenceBounds,
        @NotNull DriverBounds driverBounds,
        @NotNull IkTolerance ikTolerance,
        @NotNull RenderOptions render,
        @NotNull SimulationOptions simulation,
        @NotNull DiagnosticsOptions diagnostics,
        @NotNull RigOptions rig,
        @NotNull DecorationOptions decoration,
        @NotNull AiBounds aiBounds,
        @NotNull AiOptions ai,
        @NotNull VehicleBounds vehicleBounds,
        @NotNull VehicleOptions vehicle,
        @NotNull pandastic.studio.models.internal.menu.MenuSettings menu,
        @NotNull pandastic.studio.models.internal.view.ViewSettings view,
        @NotNull pandastic.studio.models.internal.binding.BindingSettings binding,
        @NotNull pandastic.studio.models.internal.command.CommandSettings command
) {

    /** The revision of a snapshot that was never published, i.e. the declared defaults. */
    public static final long UNPUBLISHED = 0L;

    /**
     * The values of {@code vehicle.yml} that no api type carries.
     *
     * <p>All four describe the <em>control loop</em> rather than the vehicle: how often it decides, how
     * long a gap it will integrate across, how far it may write in place, and when it stops writing at
     * all. What a vehicle is allowed to <em>be</em> is {@link VehicleBounds}, which the api declares
     * because the profile validator lives there.
     *
     * @param controlPeriodTicks       ticks between two decisions about where a vehicle is
     * @param maxStepTicks             longest gap one step will integrate across
     * @param maxLocalStepBlocks       furthest a single in-place position write may reach
     * @param restSpeedBlocksPerTick   speed below which a vehicle is treated as stopped
     */
    public record VehicleOptions(
            int controlPeriodTicks,
            float maxStepTicks,
            float maxLocalStepBlocks,
            float restSpeedBlocksPerTick
    ) {
    }

    /**
     * The values of {@code ai.yml} that no api type carries.
     *
     * <p>Both describe what happens when a behaviour or a sensor throws, which is the half of the AI
     * configuration that is about the plugin's own conduct rather than about what a plan may contain —
     * the latter is {@link AiBounds}, which the api declares because the plan validator lives there.
     *
     * @param faultLogIntervalSeconds seconds between repeat reports of one failing body
     * @param faultsBeforeDisable     consecutive failures after which a body is stopped
     */
    public record AiOptions(
            float faultLogIntervalSeconds,
            int faultsBeforeDisable
    ) {

        /**
         * The report interval in nanoseconds, which is the unit the throttle compares in.
         *
         * <p>Converted here rather than stored as nanoseconds in the file, because an operator thinks
         * in seconds and a monotonic clock does not. The widening to {@code double} before the multiply
         * is not decoration: a {@code float} second count times a billion lands in a range where
         * consecutive floats are hundreds of nanoseconds apart.
         *
         * @return the interval, in nanoseconds
         */
        public long faultLogIntervalNanos() {
            return (long) ((double) this.faultLogIntervalSeconds * 1_000_000_000.0D);
        }
    }

    /**
     * The values of {@code render.yml} that no api type carries.
     *
     * @param emissionHz              pose updates sent per second, at most the client tick rate
     * @param jitterTolerance         whether to overshoot the interpolation window by one tick
     * @param boneDirtyEpsilonRadians angular change below which a bone is not re-emitted
     * @param boneNamesInMessage      how many model bone names a binding error lists in full
     */
    public record RenderOptions(
            int emissionHz,
            boolean jitterTolerance,
            float boneDirtyEpsilonRadians,
            int boneNamesInMessage
    ) {
    }

    /**
     * The values of {@code physics.yml} that govern how a rig <em>file</em> is read rather than how a
     * bone moves, and which therefore have no api type of their own.
     *
     * @param stabilityMargin      fraction of the damping stability bound a clamped value is held to
     * @param minReachLengthBlocks shortest reach vector a file may declare, before normalisation
     */
    public record RigOptions(
            float stabilityMargin,
            float minReachLengthBlocks
    ) {
    }

    /**
     * The values of {@code simulation.yml} that describe the ambient field a decoration is driven by,
     * rather than the loop that steps it.
     *
     * <p>Grouped separately from {@link SimulationOptions} because they are consumed by a different
     * layer at a different rate: the decoration driver loop reads these once per pass, while the
     * simulation loop reads those once per step. They share a file because they describe the same
     * subject from two sides — what is allowed to push a model around.
     *
     * <p>Every component is the <em>world's</em> weather. What an individual decoration carries is one
     * exposure scalar a plugin sets per decoration.
     *
     * @param driverUpdateHz             how often a decoration's driver is recomputed and published
     * @param windBaseSpeedBlocksPerTick steady air speed past a fully exposed decoration. Settles, and
     *                                   is therefore the half of the look that costs no bandwidth
     * @param windGustSpeedBlocksPerTick amplitude of the gusting air speed. The half that does
     * @param windGustRateHz             frequency of the primary gust oscillation
     * @param windGustHarmonicRatio      frequency of the second oscillation, as a multiple of the first
     * @param windGustHarmonicWeight     how much of the gust comes from the second oscillation
     * @param windGustWavelengthBlocks   distance between two gust crests, which decorrelates neighbours
     * @param windDirectionDegrees       bearing the wind blows toward, measured as yaw is
     * @param windDirectionSwayDegrees   how far the bearing wanders
     * @param windDirectionSwayRateHz    how fast it wanders
     * @param windVerticalFraction       updraft a gust carries, as a fraction of its horizontal
     *                                   amplitude
     */
    public record DecorationOptions(
            int driverUpdateHz,
            float windBaseSpeedBlocksPerTick,
            float windGustSpeedBlocksPerTick,
            float windGustRateHz,
            float windGustHarmonicRatio,
            float windGustHarmonicWeight,
            float windGustWavelengthBlocks,
            float windDirectionDegrees,
            float windDirectionSwayDegrees,
            float windDirectionSwayRateHz,
            float windVerticalFraction
    ) {
    }

    /**
     * The values of {@code simulation.yml} that drive the loop itself and that no api type carries.
     *
     * <p>{@link DecorationOptions} carries the rest of the same file; the split is by consumer rather
     * than by subject, because these are read once per simulation step and those once per decoration
     * driver pass.
     *
     * @param loopRateHz              steps per second of the asynchronous loop
     * @param errorLogIntervalSeconds seconds between repeat reports of a failing simulation
     */
    public record SimulationOptions(
            int loopRateHz,
            float errorLogIntervalSeconds
    ) {

        /**
         * The report interval in nanoseconds, which is the unit the rate limiter compares in.
         *
         * <p>Converted here rather than stored as nanoseconds in the file, because an operator thinks
         * in seconds and a monotonic clock does not. The widening to {@code double} before the
         * multiply is not decoration: a {@code float} second count times a billion lands in a range
         * where consecutive floats are hundreds of nanoseconds apart.
         *
         * @return the interval, in nanoseconds
         */
        public long errorLogIntervalNanos() {
            return (long) ((double) this.errorLogIntervalSeconds * 1_000_000_000.0D);
        }
    }

    /**
     * The values of {@code diagnostics.yml}.
     *
     * @param logRigDiagnostics     whether rig-load diagnostics reach the console
     * @param rigDiagnosticSeverity lowest severity that is logged
     * @param logConfigReloadSummary whether a reload logs whether it was applied or refused
     */
    public record DiagnosticsOptions(
            boolean logRigDiagnostics,
            @NotNull RigDiagnostic.Severity rigDiagnosticSeverity,
            boolean logConfigReloadSummary
    ) {

        /**
         * Whether a diagnostic of {@code severity} should be logged.
         *
         * <p>The comparison is on the enum's ordinal, which is only legitimate because
         * {@link RigDiagnostic.Severity} declares {@code WARNING} before {@code ERROR}, least severe
         * first. Reordering that enum would silently invert this threshold.
         *
         * @param severity the severity of the diagnostic in hand
         * @return {@code true} when it passes both the master toggle and the threshold
         */
        public boolean shouldLog(@NotNull RigDiagnostic.Severity severity) {
            return this.logRigDiagnostics && severity.ordinal() >= this.rigDiagnosticSeverity.ordinal();
        }
    }

    /**
     * The values every declaration in this package declares, with no file involved.
     *
     * <p>This is what {@link ConfigService} starts life holding, which is why {@code snapshot()} never
     * returns {@code null} and never blocks: the service is usable before its first load and stays
     * usable if that load is refused. Building it touches no disk and cannot fail — if it ever did, a
     * default in one of the declarations would be violating the validator in the same package, and
     * {@link ConfigMapper} throws rather than papering over that.
     *
     * @return a snapshot of the declared defaults, at revision {@link #UNPUBLISHED}
     */
    @Contract(value = "-> new", pure = true)
    public static @NotNull ConfigSnapshot defaults() {
        return ConfigMapper.map(
                new PhysicsConfig(),
                new GaitConfig(),
                new RenderConfig(),
                new SimulationConfig(),
                new DiagnosticsConfig(),
                new AiConfig(),
                new VehicleConfig(),
                new MenuConfig(),
                new ViewConfig(),
                new BindingConfig(),
                new CommandConfig()
        ).snapshotOrThrow();
    }

    /**
     * This snapshot, stamped with a publication number.
     *
     * @param revision the publication counter to stamp
     * @return a copy carrying {@code revision}
     */
    @Contract(value = "_ -> new", pure = true)
    public @NotNull ConfigSnapshot withRevision(long revision) {
        return new ConfigSnapshot(revision, this.physics, this.profileBounds, this.profileDefaults,
                this.rigBounds, this.gait, this.bindingBounds, this.cadenceBounds, this.driverBounds,
                this.ikTolerance, this.render, this.simulation, this.diagnostics, this.rig,
                this.decoration, this.aiBounds, this.ai, this.vehicleBounds, this.vehicle, this.menu,
                this.view, this.binding, this.command);
    }

    /**
     * Whether moving from {@code previous} to this snapshot invalidates every live simulation state.
     *
     * <p>It does exactly when the substep rate changed. A {@code PhysicsState} is built against a step
     * size and its stability bound is checked at construction, so a state built at 60 Hz carries a
     * proof about 60 Hz and nothing else; stepping it at 30 Hz silently runs an integrator past the
     * bound that was supposed to protect it, which ends in NaN rather than in stiffness.
     *
     * <p>Note what this method is <em>not</em>: a callback. The owner of the simulations has to rebuild
     * them on its own thread, because that is the thread stepping them — a push notification would fire
     * on whichever pool thread finished the reload and race the loop it was trying to help. So the
     * signal is pulled: compare the snapshot you built against with the one you just read.
     *
     * @param previous the snapshot that was live before this one
     * @return {@code true} when every {@code PhysicsState} has to be rebuilt
     */
    @Contract(pure = true)
    public boolean requiresSimulationRebuild(@NotNull ConfigSnapshot previous) {
        return previous.physics.substepRateHz() != this.physics.substepRateHz();
    }
}
