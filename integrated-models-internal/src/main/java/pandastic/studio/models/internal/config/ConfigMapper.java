package pandastic.studio.models.internal.config;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.ai.AiActivity;
import pandastic.studio.models.api.ai.AiBounds;
import pandastic.studio.models.api.locomotion.BindingBounds;
import pandastic.studio.models.api.locomotion.GaitTuning;
import pandastic.studio.models.api.math.IkTolerance;
import pandastic.studio.models.api.physics.DriverBounds;
import pandastic.studio.models.api.physics.PhysicsTuning;
import pandastic.studio.models.api.render.CadenceBounds;
import pandastic.studio.models.api.render.EmissionCadence;
import pandastic.studio.models.api.rig.ProfileBounds;
import pandastic.studio.models.api.rig.ProfileDefaults;
import pandastic.studio.models.api.rig.RigBounds;
import pandastic.studio.models.api.vehicle.VehicleBounds;
import pandastic.studio.models.internal.menu.ModelAppearance;
import pandastic.studio.models.internal.rig.RigDiagnostic;

// The menu value type's nested records, imported by their own simple names. The outer type cannot be
// imported: pandastic.studio.models.internal.menu.MenuSettings and the MenuSettings declaration in this
// package share a simple name, which is the deliberate shadow this project uses for every file whose
// consumer-side value has no api counterpart. Only the outer name is therefore written in full, once,
// in menuSettings(...) below.
import pandastic.studio.models.internal.menu.MenuSettings.Animation;
import pandastic.studio.models.internal.menu.MenuSettings.Appearance;
import pandastic.studio.models.internal.menu.MenuSettings.Layout;
import pandastic.studio.models.internal.menu.MenuSettings.Picker;
import pandastic.studio.models.internal.menu.MenuSettings.Session;

// The view, binding and command value types are in exactly the same position and cannot be imported
// either: pandastic.studio.models.internal.view.ViewSettings, pandastic.studio.models.internal.binding
// .BindingSettings and pandastic.studio.models.internal.command.CommandSettings share their simple names
// with the declarations of view.yml, binding.yml and command.yml in this package. None has nested
// records to import, so each is written in full once, in viewSettings(...), bindingSettings(...) and
// commandSettings(...) below.

import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * Turns the loaded configurations into one validated {@link ConfigSnapshot}, or into the list of
 * reasons it refused to.
 *
 * <h2>Validate first, construct second</h2>
 * Every value is checked before a single api value object is built. That ordering is the whole point:
 * the api types validate their own components and throw {@code IllegalArgumentException} when a
 * component is out of range, and an exception from a record constructor can name the component but not
 * the <em>file and key</em> the component came from. Checking first means an operator who typed
 * {@code minMass: -1} is told which file and which key, instead of being shown a stack trace from a
 * constructor they have never heard of.
 *
 * <h2>The checks that span two files</h2>
 * Two invariants in this project are statements about two files at once, and both are enforced here
 * because nowhere else can see both sides:
 * <ul>
 *   <li>the renderer's dirty threshold must sit at or above the solver's rest threshold, or a model the
 *       physics calls settled is a model the renderer keeps re-emitting, forever;</li>
 *   <li>the simulation loop must run at least as often as emission, since emission happens on a whole
 *       number of simulation steps.</li>
 * </ul>
 * There is a third, inside {@code physics.yml} alone: the integrator's stability bound is a statement
 * about the stiffness coefficient, the lightest legal mass and the substep rate together, so no one of
 * those three can be validated on its own.
 *
 * <h2>Pure</h2>
 * No disk, no clock, no logger, no plugin. Hand it one configuration per file and it returns an answer, which
 * is what makes the whole validation layer testable without a server.
 */
public final class ConfigMapper {

    /**
     * Ceiling applied to the coefficients whose only real constraint is "finite and not absurd".
     *
     * <p>A shared ceiling rather than a per-key one, because a per-key ceiling would be a number
     * invented to look authoritative. What these bounds are actually for is catching a misplaced
     * decimal point and an accidental {@code 1e30}, both of which this catches equally well.
     */
    private static final float COEFFICIENT_CEILING = 100_000F;

    /** Ceiling on the angular amplitudes, in radians. Half a turn is already past anatomy. */
    private static final float MAX_AMPLITUDE_RADIANS = (float) Math.PI;

    /** Ceiling on the blend and clamp durations, in seconds. */
    private static final float MAX_DURATION_SECONDS = 10F;

    /**
     * Ceiling on an interpolation window, in client ticks.
     *
     * <p>The server re-sends an entity's position unprompted every 60 ticks and that restarts the
     * client's countdown, so a window of 60 or more can never complete. Like the client tick rate this
     * is a protocol fact rather than a preference, which is why it bounds a key instead of being one.
     */
    private static final int MAX_WINDOW_TICKS = 59;

    /**
     * Shortest default reach vector accepted, in model-space units.
     *
     * <p>An input-sanity floor rather than a tuning value: only the vector's direction is used, since
     * {@link pandastic.studio.models.api.rig.PhysicsProfile} normalises it, so its magnitude is
     * meaningless right up to the point where it is too small to divide by. This sits an order of
     * magnitude above the length that api refuses, so a vector that passes here is always one it can
     * normalise — and an operator who zeroed all three components gets a sentence instead of an
     * exception from a record constructor.
     */
    private static final float MIN_REACH_LENGTH = 1.0E-3F;

    /**
     * Ceiling on an air speed, in blocks per tick.
     *
     * <p>An input-sanity ceiling. A hundred blocks a tick is two thousand blocks a second, so anything
     * this catches is a decimal point rather than a preference — the same ceiling the gait's own speeds
     * are held to.
     */
    private static final float MAX_WIND_SPEED_BLOCKS_PER_TICK = 100F;

    /**
     * Ceiling on an oscillation rate in the ambient field, in hertz.
     *
     * <p>An input-sanity ceiling, and the same one {@code breathRateHz} carries: a field cycling faster
     * than the client tick rate cannot reach a viewer as anything but noise, whatever the server
     * computes.
     */
    private static final float MAX_FIELD_RATE_HZ = 20F;

    /**
     * Ceiling on the gust's harmonic ratio.
     *
     * <p>An input-sanity ceiling. Multiplied by the gust rate this is the fastest thing in the field,
     * and past this the second oscillation is faster than anything that can be put on the wire — so what
     * it catches is a typo rather than a choice.
     */
    private static final float MAX_HARMONIC_RATIO = 32F;

    /**
     * Ceiling on a bearing, in degrees.
     *
     * <p>An input-sanity ceiling: any finite angle is mathematically fine, and a full turn either way
     * is as far as a bearing anybody meant can be from zero.
     */
    private static final float MAX_BEARING_DEGREES = 360F;

    /**
     * Ceiling on the wind's direction sway, in degrees.
     *
     * <p>An input-sanity ceiling. Half a turn either side already lets the wind reverse, which is past
     * wandering.
     */
    private static final float MAX_SWAY_DEGREES = 180F;

    /**
     * Ceiling on a sensor period, in ticks.
     *
     * <p>An input-sanity figure: a sensor that runs once every six thousand ticks — five minutes — has
     * stopped being a sensor, and the memory it writes will have expired long before it runs again.
     * Nothing reads this to decide how a creature behaves.
     */
    private static final int MAX_SENSOR_PERIOD_TICKS = 6_000;

    /**
     * Ceiling on a scan radius, in blocks.
     *
     * <p>An input-sanity figure, sized by what the operation costs rather than by what is meaningful:
     * two hundred and fifty-six blocks is already past any server's view distance, so a value above it
     * is a misplaced decimal point rather than an intention.
     */
    private static final float MAX_SCAN_RADIUS_BLOCKS = 256F;

    /**
     * Ceiling on a memory expiry, in ticks.
     *
     * <p>An input-sanity figure. Twenty-four million ticks is about two weeks of game time, by which
     * point the distinction between a long expiry and no expiry has stopped existing — and the server
     * has an explicit no-expiry form for the case where that is what was meant.
     */
    private static final int MAX_MEMORY_EXPIRY_TICKS = 24_000_000;

    /**
     * Ceiling on a vehicle speed or acceleration, in blocks per tick.
     *
     * <p>An input-sanity ceiling, and the same figure the gait's own speeds are held to: a hundred blocks
     * a tick is two thousand blocks a second, so anything this catches is a decimal point rather than a
     * preference. The ceiling that actually constrains a vehicle is the pair of cross-checks against
     * {@code maxStepBlocks} and {@code maxLocalStepBlocks}, which are far tighter.
     */
    private static final float MAX_VEHICLE_SPEED_BLOCKS_PER_TICK = 100F;

    /**
     * Ceiling on a vehicle's boost multiplier.
     *
     * <p>An input-sanity figure. Sixteen times top speed is past anything a boost key means, and the real
     * constraint on it is the cross-check against the teleport thresholds rather than this.
     */
    private static final float MAX_VEHICLE_BOOST_MULTIPLIER = 16F;

    /**
     * Ceiling on a vehicle's control period, in ticks.
     *
     * <p>An input-sanity figure. A vehicle whose position is decided once a second is not a vehicle a
     * rider can steer, and twenty ticks is that second.
     */
    private static final int MAX_CONTROL_PERIOD_TICKS = 20;

    /**
     * Ceiling on the gap one control step will integrate across, in ticks.
     *
     * <p>An input-sanity figure: a hundred ticks is five seconds, by which point a vehicle catching up
     * has crossed more ground than any single step should and the honest answer is to lose the distance.
     */
    private static final float MAX_STEP_TICKS = 100F;

    /**
     * Ceiling on an in-place position write, in blocks.
     *
     * <p><strong>A platform constraint rather than an input-sanity figure.</strong> A chunk is sixteen
     * blocks wide and a write is legal only inside the chunks the ticking region already owns, so a
     * displacement of a chunk width or more cannot be inside the owned set whatever the entity's position
     * within its own chunk — and on this platform that failure is silent. The margin below it is the
     * operator's to choose; the ceiling is not.
     */
    private static final float MAX_LOCAL_STEP_BLOCKS = 16F;

    /**
     * Ceiling on a menu session's idle timeout, in seconds.
     *
     * <p>An input-sanity figure. An hour of idleness is already longer than any session anybody meant,
     * and the key exists because nothing reports that a dialog was closed — a timeout past this is
     * indistinguishable from never forgetting, which is the state the key exists to avoid.
     */
    private static final float MAX_MENU_TIMEOUT_SECONDS = 3_600F;

    /**
     * Ceiling on how many menu sessions may be held.
     *
     * <p>An input-sanity figure, sized against the largest player count anybody runs: a session is a
     * page number and a selected id, so the ceiling bounds a handful of kilobytes rather than anything
     * that could matter.
     */
    private static final int MAX_MENU_SESSIONS = 100_000;

    /**
     * Ceiling on a menu page size.
     *
     * <p>An input-sanity figure, and the only menu bound chosen by how the controls behave rather than by
     * what the protocol allows: the picker's chooser is cycled rather than scrolled, so a page of a
     * hundred entries is ninety-nine clicks to reach the last one.
     */
    private static final int MAX_MENU_PAGE_SIZE = 64;

    /**
     * Ceiling on a thumbnail's width or height.
     *
     * <p>An input-sanity figure. A thumbnail wider than this is bigger than the dialog it sits in, so
     * what it catches is a number that was meant to be the body width.
     */
    private static final int MAX_THUMBNAIL_EXTENT = 256;

    /**
     * Ceiling on a dialog element's width.
     *
     * <p>A protocol bound rather than an input-sanity figure, which is why it is taken from the menu
     * layer's own constant instead of being written again here: the client clamps a wider element, so
     * anything above it is a number that is discarded rather than a wider element. Written in full
     * because the menu value type and the {@link MenuSettings} declaration in this package share a simple
     * name on purpose.
     */
    private static final int MAX_DIALOG_ELEMENT_WIDTH =
            pandastic.studio.models.internal.menu.MenuSettings.MAX_ELEMENT_WIDTH;

    /**
     * Ceiling on every distance in {@code view.yml}, in blocks.
     *
     * <p>A platform bound rather than an input-sanity figure. The largest view distance a server may run
     * is 32 chunks, which is 512 blocks, so past this nothing reaches anybody however generous the culler
     * is — a range beyond it is not a wider range, it is a range that no longer binds anything.
     *
     * <p>Shared across every distance key in that file for the reason {@link #COEFFICIENT_CEILING} gives:
     * the individual keys have no ceiling of their own that is not invented, and the relationships that do
     * constrain them are checked against each other rather than against a figure.
     */
    private static final double MAX_VIEW_DISTANCE_BLOCKS = 512D;

    /**
     * Ceiling on the assumed viewer speed, in blocks per tick.
     *
     * <p>An input-sanity ceiling, and the same figure the gait's and the vehicle's speeds are held to: a
     * hundred blocks a tick is two thousand blocks a second, so what it catches is a decimal point.
     * Deliberately loose, because this is the one speed in the project where over-stating the value is the
     * safe direction — it is what a hysteresis margin is proven against rather than something anything is
     * clamped to.
     */
    private static final double MAX_VIEWER_SPEED_BLOCKS_PER_TICK = 100D;

    /**
     * Half-angle at which the view bowtie degenerates, in degrees.
     *
     * <p>A geometric fact rather than a chosen bound. The angular test admits a model near the look axis
     * in <em>either</em> direction, so at 90 degrees the two halves meet across the perpendicular plane
     * and every direction is inside: an operator who sets a half-angle there has not widened the test,
     * they have deleted it. Exclusive at the other end too, where a cone of zero width admits nothing at
     * all.
     */
    private static final double VIEW_CONE_LIMIT_DEGREES = 90D;

    /**
     * Ceiling on a position sampling period, in ticks.
     *
     * <p>An input-sanity figure: twenty ticks is a second, and a decision taken from a sample a second
     * old is not a decision about where anybody is. Shared by the viewer sampler in {@code view.yml} and
     * the anchor sampler in {@code binding.yml} because it is the same quantity measured on two subjects,
     * and it is the same second {@link #MAX_CONTROL_PERIOD_TICKS} is for the same reason.
     */
    private static final int MAX_SAMPLE_PERIOD_TICKS = 20;

    /**
     * Ceiling on how many models one viewer may be handed in a single pass.
     *
     * <p>An input-sanity figure sized by what the key is for rather than by what the protocol allows.
     * Showing a model re-sends every bone at full state, so a pass serving hundreds of them is precisely
     * the burst the budget exists to break up — past this the key has stopped being a budget.
     */
    private static final int MAX_SPAWN_BUDGET_PER_VIEWER = 256;

    /**
     * The charset a resource location — and therefore a material name — is allowed to use.
     *
     * <p>A structural fact about the identifier rather than a bound on anything: the namespace and the
     * path are both restricted to these characters by the game itself, so a name outside them cannot
     * resolve whatever is installed. It is a shape test and nothing more; see {@link #checkMaterial}.
     */
    private static final Pattern MATERIAL_NAME =
            Pattern.compile("[a-z0-9_.-]+(:[a-z0-9_./-]+)?");

    private ConfigMapper() {
    }

    /**
     * Validates the loaded configurations and builds the snapshot they describe, taking the declared
     * defaults for {@code ai.yml}.
     *
     * <p>Kept as an overload rather than folded into the longer form so that a caller which has nothing
     * to say about the AI layer — every test of the other files, and the declared-defaults path — is not
     * made to construct a configuration it does not care about. The delegation uses a fresh generated
     * instance, which starts at its own declared defaults, so the two forms agree about everything
     * except what the caller supplied.
     *
     * @param physics     the loaded {@code physics.yml}
     * @param gait        the loaded {@code gait.yml}
     * @param render      the loaded {@code render.yml}
     * @param simulation  the loaded {@code simulation.yml}
     * @param diagnostics the loaded {@code diagnostics.yml}
     * @return the snapshot, or the diagnostics explaining why there is none
     */
    @Contract(value = "_, _, _, _, _ -> new", pure = true)
    public static @NotNull Result map(
            @NotNull PhysicsSettings physics,
            @NotNull GaitSettings gait,
            @NotNull RenderSettings render,
            @NotNull SimulationSettings simulation,
            @NotNull DiagnosticsSettings diagnostics
    ) {
        return map(physics, gait, render, simulation, diagnostics, new AiConfig());
    }

    /**
     * Validates the loaded configurations and builds the snapshot they describe, taking the declared
     * defaults for {@code vehicle.yml}.
     *
     * <p>Kept as an overload for the same reason the five-argument form is: a caller which has nothing to
     * say about vehicles should not have to construct a configuration it does not care about. Note the
     * asymmetry with the five-argument form, which also defaults the AI file — both exist because each
     * was added by a phase that did not want to rewrite every existing call site, and collapsing them now
     * would do exactly that.
     *
     * @param physics     the loaded {@code physics.yml}
     * @param gait        the loaded {@code gait.yml}
     * @param render      the loaded {@code render.yml}
     * @param simulation  the loaded {@code simulation.yml}
     * @param diagnostics the loaded {@code diagnostics.yml}
     * @param ai          the loaded {@code ai.yml}
     * @return the snapshot, or the diagnostics explaining why there is none
     */
    @Contract(value = "_, _, _, _, _, _ -> new", pure = true)
    public static @NotNull Result map(
            @NotNull PhysicsSettings physics,
            @NotNull GaitSettings gait,
            @NotNull RenderSettings render,
            @NotNull SimulationSettings simulation,
            @NotNull DiagnosticsSettings diagnostics,
            @NotNull AiSettings ai
    ) {
        return map(physics, gait, render, simulation, diagnostics, ai, new VehicleConfig());
    }

    /**
     * Validates the loaded configurations and builds the snapshot they describe, taking the declared
     * defaults for {@code menu.yml}.
     *
     * <p>Kept as an overload for the same reason the shorter forms are, and with the same asymmetry: each
     * exists because a phase added a file and did not want to rewrite every existing call site. Note that
     * defaulting this file is cheap in a way defaulting the others is not — the declared menu defaults are
     * a complete, working set of labels, so a test that ignores them is not testing against a half-built
     * screen.
     *
     * @param physics     the loaded {@code physics.yml}
     * @param gait        the loaded {@code gait.yml}
     * @param render      the loaded {@code render.yml}
     * @param simulation  the loaded {@code simulation.yml}
     * @param diagnostics the loaded {@code diagnostics.yml}
     * @param ai          the loaded {@code ai.yml}
     * @param vehicle     the loaded {@code vehicle.yml}
     * @return the snapshot, or the diagnostics explaining why there is none
     */
    @Contract(value = "_, _, _, _, _, _, _ -> new", pure = true)
    public static @NotNull Result map(
            @NotNull PhysicsSettings physics,
            @NotNull GaitSettings gait,
            @NotNull RenderSettings render,
            @NotNull SimulationSettings simulation,
            @NotNull DiagnosticsSettings diagnostics,
            @NotNull AiSettings ai,
            @NotNull VehicleSettings vehicle
    ) {
        return map(physics, gait, render, simulation, diagnostics, ai, vehicle, new MenuConfig());
    }

    /**
     * Validates the loaded configurations and builds the snapshot they describe, taking the declared
     * defaults for {@code view.yml} and {@code binding.yml}.
     *
     * <p>Kept as an overload for the same reason the shorter forms are, and with the same asymmetry: each
     * exists because a phase added a file and did not want to rewrite every existing call site. This one
     * defaults two files rather than one because the two arrived together — the culler and the anchor
     * sampler are the region-thread half of the same pipeline.
     *
     * @param physics     the loaded {@code physics.yml}
     * @param gait        the loaded {@code gait.yml}
     * @param render      the loaded {@code render.yml}
     * @param simulation  the loaded {@code simulation.yml}
     * @param diagnostics the loaded {@code diagnostics.yml}
     * @param ai          the loaded {@code ai.yml}
     * @param vehicle     the loaded {@code vehicle.yml}
     * @param menu        the loaded {@code menu.yml}
     * @return the snapshot, or the diagnostics explaining why there is none
     */
    @Contract(value = "_, _, _, _, _, _, _, _ -> new", pure = true)
    public static @NotNull Result map(
            @NotNull PhysicsSettings physics,
            @NotNull GaitSettings gait,
            @NotNull RenderSettings render,
            @NotNull SimulationSettings simulation,
            @NotNull DiagnosticsSettings diagnostics,
            @NotNull AiSettings ai,
            @NotNull VehicleSettings vehicle,
            @NotNull MenuSettings menu
    ) {
        return map(physics, gait, render, simulation, diagnostics, ai, vehicle, menu,
                new ViewConfig(), new BindingConfig());
    }

    /**
     * Validates the loaded configurations and builds the snapshot they describe, taking the declared
     * defaults for {@code command.yml}.
     *
     * <p>Kept as an overload for the same reason the shorter forms are: a caller which has nothing to
     * say about the reload command's wording should not have to construct a configuration for it. Like
     * the menu defaults, these are a complete working set of lines, so a test that ignores them is not
     * testing against a half-built reply.
     *
     * @param physics     the loaded {@code physics.yml}
     * @param gait        the loaded {@code gait.yml}
     * @param render      the loaded {@code render.yml}
     * @param simulation  the loaded {@code simulation.yml}
     * @param diagnostics the loaded {@code diagnostics.yml}
     * @param ai          the loaded {@code ai.yml}
     * @param vehicle     the loaded {@code vehicle.yml}
     * @param menu        the loaded {@code menu.yml}
     * @param view        the loaded {@code view.yml}
     * @param binding     the loaded {@code binding.yml}
     * @return the snapshot, or the diagnostics explaining why there is none
     */
    @Contract(value = "_, _, _, _, _, _, _, _, _, _ -> new", pure = true)
    public static @NotNull Result map(
            @NotNull PhysicsSettings physics,
            @NotNull GaitSettings gait,
            @NotNull RenderSettings render,
            @NotNull SimulationSettings simulation,
            @NotNull DiagnosticsSettings diagnostics,
            @NotNull AiSettings ai,
            @NotNull VehicleSettings vehicle,
            @NotNull MenuSettings menu,
            @NotNull ViewSettings view,
            @NotNull BindingSettings binding
    ) {
        return map(physics, gait, render, simulation, diagnostics, ai, vehicle, menu, view, binding,
                new CommandConfig());
    }

    /**
     * Validates every loaded configuration and builds the snapshot they describe.
     *
     * <p>The snapshot comes back at revision {@link ConfigSnapshot#UNPUBLISHED}; stamping it is
     * {@link ConfigService}'s job, because only the service knows which publication this is.
     *
     * @param physics     the loaded {@code physics.yml}
     * @param gait        the loaded {@code gait.yml}
     * @param render      the loaded {@code render.yml}
     * @param simulation  the loaded {@code simulation.yml}
     * @param diagnostics the loaded {@code diagnostics.yml}
     * @param ai          the loaded {@code ai.yml}
     * @param vehicle     the loaded {@code vehicle.yml}
     * @param menu        the loaded {@code menu.yml}
     * @param view        the loaded {@code view.yml}
     * @param binding     the loaded {@code binding.yml}
     * @param command     the loaded {@code command.yml}
     * @return the snapshot, or the diagnostics explaining why there is none
     */
    @Contract(value = "_, _, _, _, _, _, _, _, _, _, _ -> new", pure = true)
    public static @NotNull Result map(
            @NotNull PhysicsSettings physics,
            @NotNull GaitSettings gait,
            @NotNull RenderSettings render,
            @NotNull SimulationSettings simulation,
            @NotNull DiagnosticsSettings diagnostics,
            @NotNull AiSettings ai,
            @NotNull VehicleSettings vehicle,
            @NotNull MenuSettings menu,
            @NotNull ViewSettings view,
            @NotNull BindingSettings binding,
            @NotNull CommandSettings command
    ) {
        final ConfigChecks checks = new ConfigChecks();

        checkPhysics(physics, checks);
        checkGait(gait, checks);
        checkRender(render, checks);
        checkSimulation(simulation, checks);
        final RigDiagnostic.Severity severity = checkSeverity(diagnostics, checks);
        checkAi(ai, checks);
        checkVehicle(vehicle, checks);
        checkMenu(menu, checks);
        checkView(view, checks);
        checkBinding(binding, checks);
        checkCommand(command, checks);
        checkAcrossFiles(physics, render, simulation, vehicle, checks);

        if (!checks.isEmpty() || severity == null) {
            return new Result(null, checks.toList());
        }

        final ConfigSnapshot snapshot = new ConfigSnapshot(
                ConfigSnapshot.UNPUBLISHED,
                physicsTuning(physics),
                profileBounds(physics),
                profileDefaults(physics),
                rigBounds(physics),
                gaitTuning(gait),
                bindingBounds(gait),
                cadenceBounds(render),
                driverBounds(simulation),
                ikTolerance(simulation),
                renderOptions(render),
                simulationOptions(simulation),
                new ConfigSnapshot.DiagnosticsOptions(diagnostics.logRigDiagnostics(), severity,
                        diagnostics.logConfigReloadSummary()),
                new ConfigSnapshot.RigOptions(
                        physics.rigStabilityMargin(), physics.rigMinReachLengthBlocks()),
                decorationOptions(simulation),
                aiBounds(ai),
                aiOptions(ai),
                vehicleBounds(vehicle),
                vehicleOptions(vehicle),
                menuSettings(menu),
                viewSettings(view),
                bindingSettings(binding),
                commandSettings(command)
        );
        return new Result(snapshot, List.of());
    }

    // =================================================================================================
    // Validation
    // =================================================================================================

    /**
     * Checks {@code physics.yml}, including the two stability bounds that span its own keys.
     *
     * @param physics the loaded configuration
     * @param checks  the accumulator receiving the complaints
     */
    private static void checkPhysics(@NotNull PhysicsSettings physics, @NotNull ConfigChecks checks) {
        final ConfigFile file = ConfigFile.PHYSICS;

        final float stiffnessToRate = checks.above(file, "stiffnessToRate",
                physics.stiffnessToRate(), 0F, COEFFICIENT_CEILING);
        final float dampingToRate = checks.above(file, "dampingToRate",
                physics.dampingToRate(), 0F, COEFFICIENT_CEILING);
        checks.inRange(file, "gravityToRate", physics.gravityToRate(), 0F, COEFFICIENT_CEILING);
        checks.inRange(file, "windToRadians", physics.windToRadians(), 0F, COEFFICIENT_CEILING);
        checks.inRange(file, "turnToSpeed", physics.turnToSpeed(), 0F, COEFFICIENT_CEILING);

        // A margin of 1 trusts an exclusive bound compared in float; 0 would clamp every damping to
        // zero. Both ends are refused rather than silently tolerated.
        checks.above(file, "rigStabilityMargin", physics.rigStabilityMargin(), 0F, 1F);
        checks.above(file, "rigMinReachLengthBlocks", physics.rigMinReachLengthBlocks(),
                0F, COEFFICIENT_CEILING);

        final int substepRateHz = checks.inRange(file, "substepRateHz", physics.substepRateHz(), 1, 1_000);
        checks.inRange(file, "maxSubsteps", physics.maxSubsteps(), 1, 64);
        checks.above(file, "restEpsilonRadians", physics.restEpsilonRadians(), 0F, 0.1F);

        final float minMass = checks.above(file, "minMass", physics.minMass(), 0F, 10_000F);
        final float maxMass = checks.above(file, "maxMass", physics.maxMass(), 0F, 10_000F);
        checks.below(file, "minMass", minMass, file, "maxMass", maxMass);
        final float maxGravity = checks.inRange(file, "maxGravity", physics.maxGravity(), 0F, 1_000F);
        final float maxWind = checks.inRange(file, "maxWind", physics.maxWind(), 0F, 1_000F);
        checks.inRange(file, "maxCollisionRadius", physics.maxCollisionRadius(), 0F, 64F);
        checks.above(file, "collisionRadiusToRadians", physics.collisionRadiusToRadians(), 0F, 100F);
        checks.above(file, "defaultLimitDegrees", physics.defaultLimitDegrees(), 0F, 180F);
        checks.inRange(file, "maxBones", physics.maxBones(), 1, 65_536);

        checkStability(stiffnessToRate, dampingToRate, minMass, substepRateHz, checks);
        checkProfileDefaults(physics, minMass, maxMass, maxGravity, maxWind, checks);
    }

    /**
     * Checks the coefficients a rig file inherits, and checks them against the bounds in the same file.
     *
     * <p>The second half is the one worth having. Defaults and bounds are independent keys, and a rig
     * file is entitled to omit every coefficient — in which case the profile that gets built is made
     * entirely of these defaults and is then validated against those bounds. An operator who narrowed a
     * bound past its own default has therefore broken every bone on the server that trusted the default,
     * and the only symptom without this check is a per-bone rig warning naming a value that appears
     * nowhere in the rig file. One complaint about two keys in one file is the honest report.
     *
     * <p>The complaint names the {@code default*} key rather than the bound, on the same reasoning as
     * {@code defaultStrideLength} in {@code gait.yml}: an operator who edited a bound did so
     * deliberately, so the value that is wrong relative to the other two is the default.
     *
     * @param physics     the loaded configuration
     * @param minMass     the validated mass floor
     * @param maxMass     the validated mass ceiling
     * @param maxGravity  the validated gravity ceiling
     * @param maxWind     the validated wind ceiling
     * @param checks      the accumulator receiving the complaints
     */
    private static void checkProfileDefaults(
            @NotNull PhysicsSettings physics,
            float minMass,
            float maxMass,
            float maxGravity,
            float maxWind,
            @NotNull ConfigChecks checks
    ) {
        final ConfigFile file = ConfigFile.PHYSICS;

        // Fractions by definition, so the range is the word's meaning rather than a bound in this file.
        checks.inRange(file, "defaultStiffness", physics.defaultStiffness(), 0F, 1F);
        checks.inRange(file, "defaultDamping", physics.defaultDamping(), 0F, 1F);
        checks.inRange(file, "defaultLag", physics.defaultLag(), 0F, 1F);

        final float mass = checks.above(file, "defaultMass", physics.defaultMass(), 0F, 10_000F);
        final float wind = checks.inRange(file, "defaultWind", physics.defaultWind(), 0F, 1_000F);
        final float pendulumGravity = checks.inRange(file, "defaultPendulumGravity",
                physics.defaultPendulumGravity(), 0F, 1_000F);
        final float springGravity = checks.inRange(file, "defaultSpringGravity",
                physics.defaultSpringGravity(), 0F, 1_000F);
        final float clothStripGravity = checks.inRange(file, "defaultClothStripGravity",
                physics.defaultClothStripGravity(), 0F, 1_000F);
        final float dragGravity = checks.inRange(file, "defaultDragGravity",
                physics.defaultDragGravity(), 0F, 1_000F);

        final float reachX = checks.inRange(file, "defaultReachX", physics.defaultReachX(),
                -COEFFICIENT_CEILING, COEFFICIENT_CEILING);
        final float reachY = checks.inRange(file, "defaultReachY", physics.defaultReachY(),
                -COEFFICIENT_CEILING, COEFFICIENT_CEILING);
        final float reachZ = checks.inRange(file, "defaultReachZ", physics.defaultReachZ(),
                -COEFFICIENT_CEILING, COEFFICIENT_CEILING);
        checkReachLength(reachX, reachY, reachZ, checks);

        // Mass is the two-sided one, because it has a floor as well as a ceiling.
        if (!Float.isNaN(mass) && !Float.isNaN(minMass) && !Float.isNaN(maxMass)
                && (mass < minMass || mass > maxMass)) {
            checks.reject(file, "defaultMass", "must be within the mass bounds [" + minMass + ", "
                    + maxMass + "] it is validated against, but is " + mass
                    + ": a bone that declares no mass inherits this one");
        }
        checkDefaultCeiling(checks, "defaultWind", wind, "maxWind", maxWind);
        checkDefaultCeiling(checks, "defaultPendulumGravity", pendulumGravity, "maxGravity", maxGravity);
        checkDefaultCeiling(checks, "defaultSpringGravity", springGravity, "maxGravity", maxGravity);
        checkDefaultCeiling(checks, "defaultClothStripGravity", clothStripGravity,
                "maxGravity", maxGravity);
        checkDefaultCeiling(checks, "defaultDragGravity", dragGravity, "maxGravity", maxGravity);
    }

    /**
     * Checks that the three reach components describe a direction at all.
     *
     * <p>Reported against {@code defaultReachX} with the other two named in the message, because the
     * three keys are one value and no single one of them is at fault.
     *
     * @param reachX the x component
     * @param reachY the y component
     * @param reachZ the z component
     * @param checks the accumulator receiving the complaints
     */
    private static void checkReachLength(
            float reachX,
            float reachY,
            float reachZ,
            @NotNull ConfigChecks checks
    ) {
        if (Float.isNaN(reachX) || Float.isNaN(reachY) || Float.isNaN(reachZ)) {
            // Already rejected on its own key; a derived complaint would only add noise.
            return;
        }
        final float length = (float) Math.sqrt(
                (double) reachX * reachX + (double) reachY * reachY + (double) reachZ * reachZ);
        if (length < MIN_REACH_LENGTH) {
            checks.reject(ConfigFile.PHYSICS, "defaultReachX", "together with defaultReachY and"
                    + " defaultReachZ has to describe a direction, but [" + reachX + ", " + reachY + ", "
                    + reachZ + "] is " + length + " long and the shortest vector that can be normalised"
                    + " is " + MIN_REACH_LENGTH + ". Use [0, -1, 0] for a bone that hangs.");
        }
    }

    /**
     * Checks one seeded coefficient against the ceiling it will be validated against.
     *
     * @param checks   the accumulator receiving the complaints
     * @param key      the {@code default*} key being checked
     * @param value    its loaded value
     * @param boundKey the key carrying the ceiling
     * @param bound    the ceiling
     */
    private static void checkDefaultCeiling(
            @NotNull ConfigChecks checks,
            @NotNull String key,
            float value,
            @NotNull String boundKey,
            float bound
    ) {
        if (Float.isNaN(value) || Float.isNaN(bound) || value <= bound) {
            return;
        }
        checks.reject(ConfigFile.PHYSICS, key, "must not exceed " + boundKey + ", which is " + bound
                + ", but is " + value + ": a bone that declares no value for it inherits this default"
                + " and is then refused by that bound");
    }

    /**
     * Checks the integrator's two stability bounds, both of which are statements about three keys at
     * once.
     *
     * <p>The spring bound is the familiar one: semi-implicit Euler survives only while
     * {@code omega * h} stays under {@link PhysicsTuning#STABILITY_LIMIT}, and the fastest spring a rig can ask for
     * is the stiffest coefficient on the lightest legal mass. The damping bound is the one that
     * actually bites, because the velocity update is explicit: a step multiplies velocity by
     * {@code (1 - damping * dampingToRate * h / mass)}, so once {@code dampingToRate * h} passes 2 the
     * velocity can flip sign and grow without limit for a rig whose damping is below its mass — which
     * is exactly the rig the rest of the project believes is safe.
     *
     * <p>Both are refused rather than clamped. A clamp here would quietly change the meaning of every
     * rig file on the server.
     *
     * @param stiffnessToRate the stiffness conversion factor
     * @param dampingToRate   the damping conversion factor
     * @param minMass         the lightest mass a rig may declare
     * @param substepRateHz   the substep rate
     * @param checks          the accumulator receiving the complaints
     */
    private static void checkStability(
            float stiffnessToRate,
            float dampingToRate,
            float minMass,
            int substepRateHz,
            @NotNull ConfigChecks checks
    ) {
        if (!Float.isFinite(stiffnessToRate) || !Float.isFinite(dampingToRate)
                || !Float.isFinite(minMass) || minMass <= 0F || substepRateHz <= 0) {
            // Already rejected above; the derived bounds would only add noise.
            return;
        }

        final float substepSeconds = 1F / substepRateHz;
        final float fastestSpring = (float) Math.sqrt(stiffnessToRate / minMass);
        if (fastestSpring * substepSeconds >= PhysicsTuning.STABILITY_LIMIT) {
            checks.reject(ConfigFile.PHYSICS, "stiffnessToRate",
                    "is unstable at substepRateHz " + substepRateHz + " with minMass " + minMass
                            + ": the fastest legal spring is " + fastestSpring
                            + " rad/s and the integrator needs rate * step below " + PhysicsTuning.STABILITY_LIMIT
                            + ". Lower stiffnessToRate, raise minMass, or raise substepRateHz.");
        }
        if (dampingToRate * substepSeconds > PhysicsTuning.STABILITY_LIMIT) {
            checks.reject(ConfigFile.PHYSICS, "dampingToRate",
                    "is unstable at substepRateHz " + substepRateHz + ": the explicit velocity update"
                            + " needs dampingToRate / substepRateHz at most " + PhysicsTuning.STABILITY_LIMIT
                            + ", so the ceiling here is " + (PhysicsTuning.STABILITY_LIMIT * substepRateHz) + ".");
        }
    }

    /**
     * Checks {@code gait.yml}.
     *
     * @param gait   the loaded configuration
     * @param checks the accumulator receiving the complaints
     */
    private static void checkGait(@NotNull GaitSettings gait, @NotNull ConfigChecks checks) {
        final ConfigFile file = ConfigFile.GAIT;

        checks.inRange(file, "counterPhaseCycles", gait.counterPhaseCycles(), 0F, 1F);
        checks.above(file, "restingLegExtension", gait.restingLegExtension(), 0F, 1F);
        checks.inRange(file, "movementDeadbandBlocksPerTick",
                gait.movementDeadbandBlocksPerTick(), 0F, 1F);
        checks.inRange(file, "sneakTorsoPitchRadians", gait.sneakTorsoPitchRadians(),
                0F, MAX_AMPLITUDE_RADIANS);
        final float walkSpeed = checks.above(file, "fullStrideSpeedBlocksPerTick",
                gait.fullStrideSpeedBlocksPerTick(), 0F, 100F);
        final float sprintSpeed = checks.above(file, "sprintSpeedBlocksPerTick",
                gait.sprintSpeedBlocksPerTick(), 0F, 100F);
        // Strictly below, not merely at most: the sprint blend divides by the gap between the two, so
        // equal speeds are a division by zero and an inverted pair runs the blend backwards — the
        // sprint amplitudes would be expressed while standing still.
        checks.below(file, "fullStrideSpeedBlocksPerTick", walkSpeed,
                file, "sprintSpeedBlocksPerTick", sprintSpeed);
        checks.inRange(file, "strideToFootReach", gait.strideToFootReach(), 0F, 1F);
        checks.inRange(file, "footLiftFraction", gait.footLiftFraction(), 0F, 1F);
        // Signed, unlike the amplitudes below: the sign selects which way the ankle rolls, so a rig
        // whose foot bone points backward wants both negated.
        checks.inRange(file, "footToeOffRadians", gait.footToeOffRadians(),
                -MAX_AMPLITUDE_RADIANS, MAX_AMPLITUDE_RADIANS);
        checks.inRange(file, "footHeelStrikeRadians", gait.footHeelStrikeRadians(),
                -MAX_AMPLITUDE_RADIANS, MAX_AMPLITUDE_RADIANS);

        final float maxIkReach = checks.above(file, "maxIkReachFraction",
                gait.maxIkReachFraction(), 0F, 1F);
        final float minIkDown = checks.above(file, "minIkDownFraction",
                gait.minIkDownFraction(), 0F, 1F);
        // An empty clamp window is not merely odd: the foot target would be clamped to a radius below
        // the minimum depth and above the maximum reach at the same time, and which one wins is an
        // ordering detail of the solver rather than a decision anybody made.
        checks.below(file, "minIkDownFraction", minIkDown, file, "maxIkReachFraction", maxIkReach);

        checks.inRange(file, "legSwingRadians", gait.legSwingRadians(), 0F, MAX_AMPLITUDE_RADIANS);
        checks.inRange(file, "sprintLegSwingRadians", gait.sprintLegSwingRadians(),
                0F, MAX_AMPLITUDE_RADIANS);
        checks.inRange(file, "armSwingRadians", gait.armSwingRadians(), 0F, MAX_AMPLITUDE_RADIANS);
        checks.inRange(file, "forearmCurlRadians", gait.forearmCurlRadians(), 0F, MAX_AMPLITUDE_RADIANS);
        checks.inRange(file, "torsoLeanRadians", gait.torsoLeanRadians(), 0F, MAX_AMPLITUDE_RADIANS);
        checks.inRange(file, "torsoBobRadians", gait.torsoBobRadians(), 0F, MAX_AMPLITUDE_RADIANS);
        checks.inRange(file, "torsoBobHarmonic", gait.torsoBobHarmonic(), 0F, 8F);
        checks.inRange(file, "torsoSwayRadians", gait.torsoSwayRadians(), 0F, MAX_AMPLITUDE_RADIANS);
        checks.inRange(file, "torsoTwistRadians", gait.torsoTwistRadians(), 0F, MAX_AMPLITUDE_RADIANS);

        checks.above(file, "breathRateHz", gait.breathRateHz(), 0F, 20F);
        checks.inRange(file, "breathRadians", gait.breathRadians(), 0F, MAX_AMPLITUDE_RADIANS);

        checks.above(file, "amplitudeBlendSeconds", gait.amplitudeBlendSeconds(), 0F, MAX_DURATION_SECONDS);
        checks.above(file, "airborneBlendSeconds", gait.airborneBlendSeconds(), 0F, MAX_DURATION_SECONDS);
        checks.above(file, "sneakBlendSeconds", gait.sneakBlendSeconds(), 0F, MAX_DURATION_SECONDS);
        checks.above(file, "maxStepSeconds", gait.maxStepSeconds(), 0F, MAX_DURATION_SECONDS);

        checks.above(file, "maxGaitSpeedBlocksPerTick", gait.maxGaitSpeedBlocksPerTick(), 0F, 1_000F);
        checks.above(file, "maxPhaseAdvancePerStep", gait.maxPhaseAdvancePerStep(), 0F, 1_000F);

        // An int, so the file cannot express a fractional count and the wrap always lands on a
        // breathing zero crossing. Only the floor needs checking.
        checks.inRange(file, "elapsedWrapBreathCycles", gait.elapsedWrapBreathCycles(), 1, 1_000_000);
        checks.above(file, "settledAmplitude", gait.settledAmplitude(), 0F, 1F);

        final float minSegment = checks.above(file, "minSegmentLength", gait.minSegmentLength(), 0F, 1_024F);
        final float maxSegment = checks.above(file, "maxSegmentLength", gait.maxSegmentLength(), 0F, 1_024F);
        checks.below(file, "minSegmentLength", minSegment, file, "maxSegmentLength", maxSegment);

        final float minStride = checks.above(file, "minStrideLength", gait.minStrideLength(), 0F, 1_024F);
        final float maxStride = checks.above(file, "maxStrideLength", gait.maxStrideLength(), 0F, 1_024F);
        checks.below(file, "minStrideLength", minStride, file, "maxStrideLength", maxStride);
        checks.inRange(file, "maxScale", gait.maxScale(), 1F, 64F);

        // The default has to be a legal profile value, or every gait profile that omits a stride is
        // rejected by its own constructor the first time a rig is loaded. The complaint names the
        // default rather than the bound, because the default is the value that is wrong relative to the
        // other two and the operator who edited a bound did so on purpose.
        final float defaultStride = gait.defaultStrideLength();
        if (!Float.isNaN(defaultStride) && !Float.isNaN(minStride) && !Float.isNaN(maxStride)
                && (defaultStride < minStride || defaultStride > maxStride)) {
            checks.reject(file, "defaultStrideLength", "must be within the stride bounds ["
                    + minStride + ", " + maxStride + "] it is validated against, but is " + defaultStride);
        }
        checks.inRange(file, "defaultHeadStabilise", gait.defaultHeadStabilise(), 0F, 1F);
        checks.inRange(file, "defaultHandStabilise", gait.defaultHandStabilise(), 0F, 1F);
    }

    /**
     * Checks {@code render.yml}.
     *
     * @param render the loaded configuration
     * @param checks the accumulator receiving the complaints
     */
    private static void checkRender(@NotNull RenderSettings render, @NotNull ConfigChecks checks) {
        final ConfigFile file = ConfigFile.RENDER;

        // The ceiling is the client tick rate, and it is not negotiable: a display entity consumes its
        // pending-update flags once per client tick, so a second packet inside one tick only replaces
        // the target the single interpolation step will aim at. Paying for packets nobody can see is
        // refused rather than silently clamped, because the operator who raised it expected something.
        checks.inRange(file, "emissionHz", render.emissionHz(), 1, EmissionCadence.CLIENT_TICK_HZ);

        final int minTicks = checks.inRange(file, "minInterpolationTicks",
                render.minInterpolationTicks(), 1, MAX_WINDOW_TICKS);
        final int maxTicks = checks.inRange(file, "maxInterpolationTicks",
                render.maxInterpolationTicks(), 1, MAX_WINDOW_TICKS);
        if (minTicks > maxTicks) {
            checks.reject(file, "minInterpolationTicks", "must not exceed maxInterpolationTicks, which is "
                    + maxTicks + ", but is " + minTicks);
        }

        checks.above(file, "boneDirtyEpsilonRadians", render.boneDirtyEpsilonRadians(), 0F, 0.1F);
        checks.inRange(file, "boneNamesInMessage", render.boneNamesInMessage(), 1, 4_096);
    }

    /**
     * Checks {@code simulation.yml}.
     *
     * @param simulation the loaded configuration
     * @param checks     the accumulator receiving the complaints
     */
    private static void checkSimulation(
            @NotNull SimulationSettings simulation,
            @NotNull ConfigChecks checks
    ) {
        final ConfigFile file = ConfigFile.SIMULATION;

        checks.inRange(file, "loopRateHz", simulation.loopRateHz(), 1, 1_000);
        checks.above(file, "errorLogIntervalSeconds", simulation.errorLogIntervalSeconds(), 0F, 3_600F);
        checks.above(file, "maxStepBlocks", simulation.maxStepBlocks(), 0F, 1_000F);
        checks.above(file, "maxVerticalStepBlocks", simulation.maxVerticalStepBlocks(), 0F, 1_000F);
        checks.above(file, "ikDegenerateTargetBlocks", simulation.ikDegenerateTargetBlocks(), 0F, 1F);

        checkDecoration(simulation, checks);
    }

    /**
     * Checks the ambient field a decoration is driven by.
     *
     * <p>Every bound here is an input-sanity ceiling rather than a statement about how a bone moves: the
     * field is a sum of two sines and nothing in it has a stability condition, so what these catch is a
     * misplaced decimal point. The two that are not ceilings are the ones where zero is undefined rather
     * than merely small — a wavelength is divided by, and a gust frozen at zero frequency would leave a
     * per-position constant offset no operator could have meant.
     *
     * <p>Deliberately <strong>no</strong> cross-check against {@code loopRateHz} or {@code emissionHz},
     * unlike the emission rate's. A driver pass rate above the loop rate is wasteful rather than
     * incoherent — the extra passes publish samples the simulation never reads, and the simulation still
     * reads the most recent one — and a gust faster than half the emission rate aliases into something
     * blurry rather than into something wrong. Refusing either would mean refusing a configuration that
     * works, which is a worse failure than the one it would prevent.
     *
     * @param simulation the loaded configuration
     * @param checks     the accumulator receiving the complaints
     */
    private static void checkDecoration(
            @NotNull SimulationSettings simulation,
            @NotNull ConfigChecks checks
    ) {
        final ConfigFile file = ConfigFile.SIMULATION;

        checks.inRange(file, "decorationDriverUpdateHz", simulation.decorationDriverUpdateHz(), 1, 1_000);

        checks.inRange(file, "decorationWindBaseSpeedBlocksPerTick",
                simulation.decorationWindBaseSpeedBlocksPerTick(), 0F, MAX_WIND_SPEED_BLOCKS_PER_TICK);
        checks.inRange(file, "decorationWindGustSpeedBlocksPerTick",
                simulation.decorationWindGustSpeedBlocksPerTick(), 0F, MAX_WIND_SPEED_BLOCKS_PER_TICK);

        checks.above(file, "decorationWindGustRateHz", simulation.decorationWindGustRateHz(),
                0F, MAX_FIELD_RATE_HZ);
        checks.above(file, "decorationWindGustHarmonicRatio",
                simulation.decorationWindGustHarmonicRatio(), 0F, MAX_HARMONIC_RATIO);
        // A fraction by definition, so the range is the word's meaning rather than a bound in this file.
        checks.inRange(file, "decorationWindGustHarmonicWeight",
                simulation.decorationWindGustHarmonicWeight(), 0F, 1F);
        checks.above(file, "decorationWindGustWavelengthBlocks",
                simulation.decorationWindGustWavelengthBlocks(), 0F, COEFFICIENT_CEILING);

        checks.inRange(file, "decorationWindDirectionDegrees",
                simulation.decorationWindDirectionDegrees(), -MAX_BEARING_DEGREES, MAX_BEARING_DEGREES);
        checks.inRange(file, "decorationWindDirectionSwayDegrees",
                simulation.decorationWindDirectionSwayDegrees(), 0F, MAX_SWAY_DEGREES);
        checks.inRange(file, "decorationWindDirectionSwayRateHz",
                simulation.decorationWindDirectionSwayRateHz(), 0F, MAX_FIELD_RATE_HZ);
        checks.inRange(file, "decorationWindVerticalFraction",
                simulation.decorationWindVerticalFraction(), 0F, 1F);
    }

    /**
     * Resolves the diagnostic severity threshold by name.
     *
     * <p>Refused rather than defaulted when the name is not a constant. An operator who wrote
     * {@code WARN} meant {@code WARNING}, and quietly substituting the default would leave them
     * believing a threshold is in effect that is not.
     *
     * @param diagnostics the loaded configuration
     * @param checks      the accumulator receiving the complaints
     * @return the resolved severity, or {@code null} when the name does not name one
     */
    private static @Nullable RigDiagnostic.Severity checkSeverity(
            @NotNull DiagnosticsSettings diagnostics,
            @NotNull ConfigChecks checks
    ) {
        // Tested for null despite the declaration's @NotNull: the annotation documents the default
        // method's return, and the generated class reads the key through getString, which answers null
        // for a key that is present with an empty value. "severity:" on its own line is a real edit an
        // operator can make, and it must produce a diagnostic rather than a NullPointerException.
        final String declared = diagnostics.rigDiagnosticSeverity();
        if (declared != null) {
            for (final RigDiagnostic.Severity candidate : RigDiagnostic.Severity.values()) {
                if (candidate.name().equalsIgnoreCase(declared.trim())) {
                    return candidate;
                }
            }
        }
        final StringJoiner accepted = new StringJoiner(", ");
        for (final RigDiagnostic.Severity candidate : RigDiagnostic.Severity.values()) {
            accepted.add(candidate.name());
        }
        checks.reject(ConfigFile.DIAGNOSTICS, "rigDiagnosticSeverity",
                "must be one of " + accepted + " but is "
                        + (declared == null ? "empty" : "'" + declared + "'"));
        return null;
    }

    /**
     * Checks {@code ai.yml}.
     *
     * <p>Every ceiling here is an input-sanity bound rather than a derived one: nothing in this file
     * participates in an equation, so there is no value whose legality depends on another's. What the
     * bounds catch is a misplaced decimal point and a figure an operator would not have meant — a
     * hundred sensors per creature, a scan the width of a render distance, a memory that outlives the
     * world.
     *
     * <p>The one relationship worth checking is the sensor period range, because a maximum below the
     * minimum leaves no legal period at all and every plan would then fail with a message about the
     * plan rather than about this file. {@link pandastic.studio.models.api.ai.AiBounds} refuses that
     * pairing too, from its own constructor, but an exception out of a record is a stack trace rather
     * than a sentence naming two keys.
     *
     * @param ai     the loaded configuration
     * @param checks the accumulator receiving the complaints
     */
    private static void checkAi(@NotNull AiSettings ai, @NotNull ConfigChecks checks) {
        final ConfigFile file = ConfigFile.AI;

        // Bounded above by the cardinality of the server's frozen activity registry rather than by a
        // sanity figure: there are exactly that many slots and no configuration can produce more.
        checks.inRange(file, "maxActivities", ai.maxActivities(), 1, AiActivity.COUNT);
        checks.inRange(file, "maxBehavioursPerActivity", ai.maxBehavioursPerActivity(), 1, 256);
        checks.inRange(file, "maxPriority", ai.maxPriority(), 0, 4_096);
        checks.inRange(file, "maxSensors", ai.maxSensors(), 0, 64);
        checks.inRange(file, "maxMemoriesPerPlan", ai.maxMemoriesPerPlan(), 1, 1_024);

        final int minPeriod = checks.inRange(file, "minSensorPeriodTicks",
                ai.minSensorPeriodTicks(), 1, MAX_SENSOR_PERIOD_TICKS);
        final int maxPeriod = checks.inRange(file, "maxSensorPeriodTicks",
                ai.maxSensorPeriodTicks(), 1, MAX_SENSOR_PERIOD_TICKS);
        checks.atMost(file, "minSensorPeriodTicks", minPeriod, file, "maxSensorPeriodTicks", maxPeriod);

        checks.above(file, "maxScanRadiusBlocks", ai.maxScanRadiusBlocks(), 0F, MAX_SCAN_RADIUS_BLOCKS);
        checks.inRange(file, "maxMemoryExpiryTicks", ai.maxMemoryExpiryTicks(), 1,
                MAX_MEMORY_EXPIRY_TICKS);

        checks.inRange(file, "faultLogIntervalSeconds", ai.faultLogIntervalSeconds(), 0F,
                MAX_DURATION_SECONDS);
        checks.inRange(file, "faultsBeforeDisable", ai.faultsBeforeDisable(), 1, 1_000);
    }

    /**
     * Checks {@code vehicle.yml}.
     *
     * <p>Two of the five relationships here are inside this file and the other three are the same
     * subject seen from a different one, so only the first two live in this method:
     *
     * <ul>
     *   <li>{@code maxStepTicks} must admit {@code controlPeriodTicks}, or every ordinary step is clamped
     *       and every vehicle on the server silently runs slow — a symptom with no log line and no
     *       obvious cause, since nothing about the vehicle looks wrong except its speed;</li>
     *   <li>the fastest permitted per-tick step — top speed times the boost ceiling — must fit inside
     *       {@code maxLocalStepBlocks}, or a vehicle at full speed pays a scheduled teleport per tick.
     *       That one is reported against the boost rather than the speed, because an operator who raised
     *       a speed ceiling meant it and a boost is the cheaper of the two to give up.</li>
     * </ul>
     *
     * @param vehicle the loaded configuration
     * @param checks  the accumulator receiving the complaints
     */
    private static void checkVehicle(@NotNull VehicleSettings vehicle, @NotNull ConfigChecks checks) {
        final ConfigFile file = ConfigFile.VEHICLE;

        final float maxSpeed = checks.above(file, "maxSpeedBlocksPerTick",
                vehicle.maxSpeedBlocksPerTick(), 0F, MAX_VEHICLE_SPEED_BLOCKS_PER_TICK);
        checks.above(file, "maxAccelerationBlocksPerTickSquared",
                vehicle.maxAccelerationBlocksPerTickSquared(), 0F,
                MAX_VEHICLE_SPEED_BLOCKS_PER_TICK);
        // A full turn a tick either way is already past anything a visual can follow, and the api
        // refuses a non-positive ceiling because it would admit no profile at all.
        checks.above(file, "maxTurnRateDegreesPerTick", vehicle.maxTurnRateDegreesPerTick(),
                0F, MAX_BEARING_DEGREES);
        // One rather than zero: a multiplier's identity is its floor, and a ceiling below it would
        // refuse even a profile that declares no boost.
        final float maxBoost = checks.inRange(file, "maxBoostMultiplier",
                vehicle.maxBoostMultiplier(), 1F, MAX_VEHICLE_BOOST_MULTIPLIER);

        final int controlPeriod = checks.inRange(file, "controlPeriodTicks",
                vehicle.controlPeriodTicks(), 1, MAX_CONTROL_PERIOD_TICKS);
        final float maxStepTicks = checks.above(file, "maxStepTicks", vehicle.maxStepTicks(),
                0F, MAX_STEP_TICKS);
        final float maxLocalStep = checks.above(file, "maxLocalStepBlocks",
                vehicle.maxLocalStepBlocks(), 0F, MAX_LOCAL_STEP_BLOCKS);
        checks.above(file, "restSpeedBlocksPerTick", vehicle.restSpeedBlocksPerTick(), 0F, 1F);

        checks.atMost(file, "controlPeriodTicks", controlPeriod, file, "maxStepTicks", maxStepTicks);

        if (!Float.isNaN(maxSpeed) && !Float.isNaN(maxBoost) && !Float.isNaN(maxLocalStep)
                && maxSpeed * maxBoost > maxLocalStep) {
            checks.reject(file, "maxBoostMultiplier", "times maxSpeedBlocksPerTick is "
                    + (maxSpeed * maxBoost) + " blocks per tick, which exceeds maxLocalStepBlocks, set to "
                    + maxLocalStep + ": a vehicle at full speed would pay a scheduled teleport every"
                    + " tick. Lower the boost, lower the speed, or raise maxLocalStepBlocks.");
        }
    }

    /**
     * Checks {@code menu.yml}.
     *
     * <h2>What the numeric bounds are and are not</h2>
     * Most of them are input-sanity figures. Two are not, and the distinction matters because only one
     * kind can be loosened:
     * <ul>
     *   <li><strong>The element widths are a protocol bound.</strong> The client clamps a dialog
     *       element's width, so a larger value is a number it discards rather than a wider element.
     *       Refusing it means an operator hears about it instead of wondering why nothing changed.</li>
     *   <li><strong>The colour range is a protocol bound.</strong> A channel is one byte on the wire.
     *       Narrowing the range is a legitimate thing for a server to do; widening it past 255 asks for
     *       values that cannot be transmitted.</li>
     * </ul>
     *
     * <h2>Why the labels are checked at all</h2>
     * Because a blank one is invisible rather than wrong. An operator who deletes the Wear button's label
     * gets a button with no text on it and no indication of what happened, so the keys where blank is
     * <em>not</em> a legitimate value are refused here — and the ones where it is, which are the optional
     * lines and the tooltips, are checked only for the null a key-with-no-value reads back as.
     *
     * <h2>The material is the one key this file does not own</h2>
     * Its legal values are the server's, so it is resolved here rather than range-checked: a name no
     * material has, or one that is not an item, cannot become a thumbnail and the operator would
     * otherwise discover that as a missing picture.
     *
     * @param menu   the loaded configuration
     * @param checks the accumulator receiving the complaints
     */
    private static void checkMenu(@NotNull MenuSettings menu, @NotNull ConfigChecks checks) {
        final ConfigFile file = ConfigFile.MENU;

        checks.above(file, "session.timeoutSeconds", menu.sessionTimeoutSeconds(),
                0F, MAX_MENU_TIMEOUT_SECONDS);
        checks.inRange(file, "session.maxSessions", menu.sessionMaxSessions(), 1, MAX_MENU_SESSIONS);

        checkWidth(checks, "layout.bodyWidth", menu.layoutBodyWidth());
        checkWidth(checks, "layout.inputWidth", menu.layoutInputWidth());
        checkWidth(checks, "layout.buttonWidth", menu.layoutButtonWidth());
        checks.text(file, "layout.pageIndicator", menu.layoutPageIndicator());
        checks.text(file, "layout.nextPageLabel", menu.layoutNextPageLabel());
        checks.text(file, "layout.previousPageLabel", menu.layoutPreviousPageLabel());
        checks.text(file, "layout.backLabel", menu.layoutBackLabel());
        checks.text(file, "layout.closeLabel", menu.layoutCloseLabel());

        checkPickerMenu(menu, checks);
        checkAppearanceMenu(menu, checks);
        checkAnimationMenu(menu, checks);
    }

    /**
     * Checks the {@code picker.*} half of {@code menu.yml}.
     *
     * @param menu   the loaded configuration
     * @param checks the accumulator receiving the complaints
     */
    private static void checkPickerMenu(@NotNull MenuSettings menu, @NotNull ConfigChecks checks) {
        final ConfigFile file = ConfigFile.MENU;

        checks.text(file, "picker.title", menu.pickerTitle());
        checks.optionalText(file, "picker.heading", menu.pickerHeading());
        checks.inRange(file, "picker.pageSize", menu.pickerPageSize(), 1, MAX_MENU_PAGE_SIZE);
        checks.text(file, "picker.chooserLabel", menu.pickerChooserLabel());
        checks.text(file, "picker.emptyMessage", menu.pickerEmptyMessage());
        checks.text(file, "picker.wearLabel", menu.pickerWearLabel());
        checks.optionalText(file, "picker.wearTooltip", menu.pickerWearTooltip());
        checks.text(file, "picker.removeLabel", menu.pickerRemoveLabel());
        checks.optionalText(file, "picker.removeTooltip", menu.pickerRemoveTooltip());
        checks.text(file, "picker.refreshLabel", menu.pickerRefreshLabel());
        checks.optionalText(file, "picker.refreshTooltip", menu.pickerRefreshTooltip());
        checks.text(file, "picker.reloadLabel", menu.pickerReloadLabel());
        checks.optionalText(file, "picker.reloadTooltip", menu.pickerReloadTooltip());
        checks.text(file, "picker.appearanceLabel", menu.pickerAppearanceLabel());
        checks.text(file, "picker.animationsLabel", menu.pickerAnimationsLabel());
        checks.text(file, "picker.wornLine", menu.pickerWornLine());
        checks.text(file, "picker.nothingWornLine", menu.pickerNothingWornLine());
        checks.text(file, "picker.unknownModelMessage", menu.pickerUnknownModelMessage());
        checks.text(file, "picker.wornMessage", menu.pickerWornMessage());
        checks.text(file, "picker.removedMessage", menu.pickerRemovedMessage());
        checks.text(file, "picker.reloadedMessage", menu.pickerReloadedMessage());

        checkMaterial(checks, menu.pickerThumbnailMaterial());
        checks.optionalText(file, "picker.thumbnailItemModel", menu.pickerThumbnailItemModel());
        checks.inRange(file, "picker.thumbnailWidth", menu.pickerThumbnailWidth(),
                1, MAX_THUMBNAIL_EXTENT);
        checks.inRange(file, "picker.thumbnailHeight", menu.pickerThumbnailHeight(),
                1, MAX_THUMBNAIL_EXTENT);
        checks.optionalText(file, "picker.thumbnailDescription", menu.pickerThumbnailDescription());
    }

    /**
     * Checks the {@code appearance.*} half of {@code menu.yml}, including the three relationships inside
     * it.
     *
     * <p>The relationships are the only interesting part. A slider needs a range to have a handle at all,
     * a step larger than its range leaves the handle one position, and a default outside the range is a
     * state the screen can display and the player can never return to — which is what Reset would do to
     * them.
     *
     * @param menu   the loaded configuration
     * @param checks the accumulator receiving the complaints
     */
    private static void checkAppearanceMenu(@NotNull MenuSettings menu, @NotNull ConfigChecks checks) {
        final ConfigFile file = ConfigFile.MENU;

        checks.text(file, "appearance.title", menu.appearanceTitle());
        checks.optionalText(file, "appearance.heading", menu.appearanceHeading());
        checks.text(file, "appearance.glowLabel", menu.appearanceGlowLabel());
        checks.text(file, "appearance.redLabel", menu.appearanceRedLabel());
        checks.text(file, "appearance.greenLabel", menu.appearanceGreenLabel());
        checks.text(file, "appearance.blueLabel", menu.appearanceBlueLabel());
        checks.text(file, "appearance.channelLabelFormat", menu.appearanceChannelLabelFormat());
        checks.text(file, "appearance.scaleLabel", menu.appearanceScaleLabel());
        checks.text(file, "appearance.scaleLabelFormat", menu.appearanceScaleLabelFormat());
        checks.text(file, "appearance.physicsLabel", menu.appearancePhysicsLabel());
        checks.text(file, "appearance.firstPersonArmsLabel", menu.appearanceFirstPersonArmsLabel());
        checks.text(file, "appearance.applyLabel", menu.appearanceApplyLabel());
        checks.text(file, "appearance.resetLabel", menu.appearanceResetLabel());
        checks.text(file, "appearance.swatchGlyph", menu.appearanceSwatchGlyph());
        checks.text(file, "appearance.swatchLine", menu.appearanceSwatchLine());
        checks.optionalText(file, "appearance.swatchNote", menu.appearanceSwatchNote());
        checks.text(file, "appearance.appliedMessage", menu.appearanceAppliedMessage());
        checks.text(file, "appearance.resetMessage", menu.appearanceResetMessage());
        checks.text(file, "appearance.noModelMessage", menu.appearanceNoModelMessage());

        // Both ends are the byte a channel travels in, which is why this is a range test rather than a
        // ceiling: a floor above zero is a server forbidding near-black tints, and that is allowed.
        final int minChannel = checks.inRange(file, "appearance.minChannel", menu.appearanceMinChannel(),
                ModelAppearance.CHANNEL_FLOOR, ModelAppearance.CHANNEL_CEILING);
        final int maxChannel = checks.inRange(file, "appearance.maxChannel", menu.appearanceMaxChannel(),
                ModelAppearance.CHANNEL_FLOOR, ModelAppearance.CHANNEL_CEILING);
        if (minChannel >= maxChannel) {
            checks.reject(file, "appearance.minChannel", "must stay below appearance.maxChannel, which is "
                    + maxChannel + ", but is " + minChannel + ": a slider needs a range to have a handle");
        }
        checkStep(checks, "appearance.channelStep", menu.appearanceChannelStep(),
                maxChannel - (float) minChannel);

        final float minScale = checks.above(file, "appearance.minScale", menu.appearanceMinScale(),
                0F, COEFFICIENT_CEILING);
        final float maxScale = checks.above(file, "appearance.maxScale", menu.appearanceMaxScale(),
                0F, COEFFICIENT_CEILING);
        checks.below(file, "appearance.minScale", minScale, file, "appearance.maxScale", maxScale);
        checkStep(checks, "appearance.scaleStep", menu.appearanceScaleStep(), maxScale - minScale);

        checkChannelDefault(checks, "appearance.defaultRed", menu.appearanceDefaultRed(),
                minChannel, maxChannel);
        checkChannelDefault(checks, "appearance.defaultGreen", menu.appearanceDefaultGreen(),
                minChannel, maxChannel);
        checkChannelDefault(checks, "appearance.defaultBlue", menu.appearanceDefaultBlue(),
                minChannel, maxChannel);

        final float defaultScale = menu.appearanceDefaultScale();
        if (!Float.isNaN(defaultScale) && !Float.isNaN(minScale) && !Float.isNaN(maxScale)
                && (defaultScale < minScale || defaultScale > maxScale)) {
            checks.reject(file, "appearance.defaultScale", "must sit inside the slider range ["
                    + minScale + ", " + maxScale + "], but is " + defaultScale
                    + ": Reset would put the player somewhere the slider cannot return to");
        }
    }

    /**
     * Checks the {@code animation.*} half of {@code menu.yml}.
     *
     * @param menu   the loaded configuration
     * @param checks the accumulator receiving the complaints
     */
    private static void checkAnimationMenu(@NotNull MenuSettings menu, @NotNull ConfigChecks checks) {
        final ConfigFile file = ConfigFile.MENU;

        checks.text(file, "animation.title", menu.animationTitle());
        checks.optionalText(file, "animation.heading", menu.animationHeading());
        checks.inRange(file, "animation.pageSize", menu.animationPageSize(), 1, MAX_MENU_PAGE_SIZE);
        checks.text(file, "animation.emptyMessage", menu.animationEmptyMessage());
        checks.text(file, "animation.entryLabel", menu.animationEntryLabel());
        checks.optionalText(file, "animation.entryTooltip", menu.animationEntryTooltip());
        checks.text(file, "animation.stopLabel", menu.animationStopLabel());
        checks.optionalText(file, "animation.stopTooltip", menu.animationStopTooltip());
        checks.text(file, "animation.playedMessage", menu.animationPlayedMessage());
        checks.text(file, "animation.stoppedMessage", menu.animationStoppedMessage());
        checks.text(file, "animation.unknownAnimationMessage", menu.animationUnknownAnimationMessage());
        checks.text(file, "animation.noModelMessage", menu.animationNoModelMessage());
    }

    /**
     * Checks one dialog element width against the bound the client imposes.
     *
     * @param checks the accumulator receiving the complaints
     * @param key    the key being checked
     * @param width  the loaded width
     */
    private static void checkWidth(@NotNull ConfigChecks checks, @NotNull String key, int width) {
        if (width >= 1 && width <= MAX_DIALOG_ELEMENT_WIDTH) {
            return;
        }
        checks.reject(ConfigFile.MENU, key, "must be within [1, " + MAX_DIALOG_ELEMENT_WIDTH
                + "], but is " + width + ": the client clamps a wider element rather than drawing it,"
                + " so the extra width is discarded");
    }

    /**
     * Checks one slider's granularity against the range it spans.
     *
     * @param checks the accumulator receiving the complaints
     * @param key    the key being checked
     * @param step   the loaded step
     * @param range  the span of the slider
     */
    private static void checkStep(
            @NotNull ConfigChecks checks,
            @NotNull String key,
            float step,
            float range
    ) {
        if (Float.isNaN(range) || range <= 0F) {
            // The range is already rejected on its own keys; a derived complaint would only add noise,
            // and bounding a step by a negative range would produce a sentence nobody can act on.
            return;
        }
        // The ceiling is the span, because a step larger than the range leaves the handle one position.
        checks.above(ConfigFile.MENU, key, step, 0F, range);
    }

    /**
     * Checks one seeded colour channel against the range the sliders offer.
     *
     * @param checks the accumulator receiving the complaints
     * @param key    the {@code default*} key being checked
     * @param value  its loaded value
     * @param min    the slider floor
     * @param max    the slider ceiling
     */
    private static void checkChannelDefault(
            @NotNull ConfigChecks checks,
            @NotNull String key,
            int value,
            int min,
            int max
    ) {
        if (value >= min && value <= max) {
            return;
        }
        checks.reject(ConfigFile.MENU, key, "must sit inside the slider range [" + min + ", " + max
                + "], but is " + value
                + ": Reset would put the player somewhere the slider cannot return to");
    }

    /**
     * Checks that the thumbnail's material name could name a material.
     *
     * <h2>Why this is a shape test and not a lookup</h2>
     * Because the lookup is not available here, and making it available would cost more than it buys.
     * {@code Material.matchMaterial} initialises the server's registry, so calling it would make this
     * class — the one piece of the configuration layer with no disk, no clock and no plugin in it —
     * require a running server, and with it every test of every other file in this package.
     *
     * <p>So what is checked is the resource-location charset, which catches the mistakes a shape test
     * can catch: a name with a space in the middle, punctuation, an empty namespace. A <em>misspelled</em>
     * material passes here and is reported by the surface that fails to resolve it, once, at the point
     * where the registry is actually reachable.
     *
     * @param checks the accumulator receiving the complaints
     * @param name   the loaded material name
     */
    private static void checkMaterial(@NotNull ConfigChecks checks, @Nullable String name) {
        final String declared = checks.text(ConfigFile.MENU, "picker.thumbnailMaterial", name);
        if (declared.isEmpty()) {
            return;
        }
        // The same normalisation Bukkit applies before it parses a material name.
        if (!MATERIAL_NAME.matcher(declared.toLowerCase(Locale.ROOT).replace(' ', '_')).matches()) {
            checks.reject(ConfigFile.MENU, "picker.thumbnailMaterial",
                    "is not shaped like a material name: '" + declared
                            + "'. Write it as ITEM_FRAME or minecraft:item_frame.");
        }
    }

    /**
     * Checks {@code view.yml}, including the three relationships between its own keys.
     *
     * <h2>What the individual bounds are</h2>
     * The distances share one platform-derived ceiling and are otherwise only required to be positive,
     * because no single one of them is wrong on its own — a range of 200 blocks is extravagant rather
     * than incorrect. The two angles are the exception: a half-angle is meaningful only strictly inside
     * {@code (0, 90)}, and that is geometry rather than taste. See {@link #checkHalfAngle}.
     *
     * <h2>Why all three relationships are reported here</h2>
     * {@link pandastic.studio.models.internal.view.ViewSettings} refuses every one of them from its own
     * canonical constructor, and that is the backstop rather than the report. An exception out of a
     * record names the component and not the file and the key it was read from, and two of these three
     * are about a <em>product</em> of keys, so the number at fault belongs to none of them individually
     * — an operator shown "exitMarginBlocks must exceed 40.0" with a file that says 8 would be entirely
     * right not to believe it. So each complaint names every key involved and spells the arithmetic out.
     *
     * <h2>Deliberately no cross-file check</h2>
     * Unlike {@code vehicle.yml}, nothing in another file participates. The culling pass reads a
     * published snapshot and emits packets, which are region-independent, so its rate is not coupled to
     * the simulation loop's; a sample older than a pass is stale rather than incoherent, and the exit
     * margin is already proven against exactly how stale it can be.
     *
     * @param view   the loaded configuration
     * @param checks the accumulator receiving the complaints
     */
    private static void checkView(@NotNull ViewSettings view, @NotNull ConfigChecks checks) {
        final ConfigFile file = ConfigFile.VIEW;

        final double range = checks.above(file, "viewRangeBlocks", view.viewRangeBlocks(),
                0D, MAX_VIEW_DISTANCE_BLOCKS);
        final double near = checks.above(file, "nearRadiusBlocks", view.nearRadiusBlocks(),
                0D, MAX_VIEW_DISTANCE_BLOCKS);
        final double margin = checks.above(file, "exitMarginBlocks", view.exitMarginBlocks(),
                0D, MAX_VIEW_DISTANCE_BLOCKS);
        // Zero is legal for both: no third-person camera to allow for, and a model whose own radius is
        // always reported. Neither is a denominator anywhere.
        checks.inRange(file, "cameraOffsetBlocks", view.cameraOffsetBlocks(),
                0D, MAX_VIEW_DISTANCE_BLOCKS);
        checks.inRange(file, "defaultModelRadiusBlocks", view.defaultModelRadiusBlocks(),
                0D, MAX_VIEW_DISTANCE_BLOCKS);

        final double enter = checkHalfAngle(checks, "coneEnterDegrees", view.coneEnterDegrees());
        final double exit = checkHalfAngle(checks, "coneExitDegrees", view.coneExitDegrees());

        final double speed = checks.above(file, "maxViewerSpeedBlocksPerTick",
                view.maxViewerSpeedBlocksPerTick(), 0D, MAX_VIEWER_SPEED_BLOCKS_PER_TICK);
        final int interval = checks.inRange(file, "sampleIntervalTicks", view.sampleIntervalTicks(),
                1, MAX_SAMPLE_PERIOD_TICKS);
        checks.inRange(file, "spawnBudgetPerViewer", view.spawnBudgetPerViewer(),
                1, MAX_SPAWN_BUDGET_PER_VIEWER);

        checkNearInsideRange(checks, near, range);
        checkConeHysteresis(checks, enter, exit);
        checkMarginSurvivesSampling(checks, margin, speed, interval);
    }

    /**
     * Requires a half-angle to be strictly inside {@code (0, 90)} degrees.
     *
     * <p>Its own check rather than {@link ConfigChecks#above} because both ends are exclusive and the
     * upper one is not a preference: the angular test admits a model near the look axis in either
     * direction, so at 90 degrees the two halves of the bowtie meet and every direction is admitted. An
     * operator who sets a half-angle there has deleted the test rather than widened it, which is a
     * thing worth being told in a sentence.
     *
     * @param checks the accumulator receiving the complaint
     * @param key    the key being checked
     * @param value  the loaded value
     * @return {@code value}, unchanged
     */
    private static double checkHalfAngle(
            @NotNull ConfigChecks checks,
            @NotNull String key,
            double value
    ) {
        if (Double.isNaN(value)) {
            checks.reject(ConfigFile.VIEW, key, "is not a number");
            return value;
        }
        if (value <= 0D || value >= VIEW_CONE_LIMIT_DEGREES) {
            checks.reject(ConfigFile.VIEW, key, "must be greater than 0 and below "
                    + VIEW_CONE_LIMIT_DEGREES + " degrees but is " + value
                    + ": the angular test admits a model near the look axis in either direction, so at"
                    + " 90 the two halves meet and every direction is inside - which is the same as"
                    + " having no angular test at all.");
        }
        return value;
    }

    /**
     * Requires the omnidirectional zone to leave room for the angular test.
     *
     * @param checks the accumulator receiving the complaint
     * @param near   the loaded near radius
     * @param range  the loaded view range
     */
    private static void checkNearInsideRange(
            @NotNull ConfigChecks checks,
            double near,
            double range
    ) {
        if (!isUsableDistance(near) || !isUsableDistance(range)) {
            // Already rejected on its own key; a derived complaint would only add noise.
            return;
        }
        if (near < range) {
            return;
        }
        checks.reject(ConfigFile.VIEW, "nearRadiusBlocks", "must stay inside viewRangeBlocks, which is "
                + range + ", but is " + near + ": everything the range admits would then be inside the"
                + " omnidirectional zone and the angular test could never run, so the direction keys"
                + " would do nothing.");
    }

    /**
     * Requires the exit angle to be wider than the enter angle.
     *
     * @param checks the accumulator receiving the complaint
     * @param enter  the loaded enter half-angle
     * @param exit   the loaded exit half-angle
     */
    private static void checkConeHysteresis(
            @NotNull ConfigChecks checks,
            double enter,
            double exit
    ) {
        if (!isUsableHalfAngle(enter) || !isUsableHalfAngle(exit)) {
            // Already rejected on its own key; a derived complaint would only add noise.
            return;
        }
        if (enter < exit) {
            return;
        }
        checks.reject(ConfigFile.VIEW, "coneEnterDegrees", "must stay below coneExitDegrees, which is "
                + exit + ", but is " + enter + ": with no angular hysteresis a model sitting on the"
                + " boundary is taken away and handed back on alternate passes, and every hand-back"
                + " re-sends every bone.");
    }

    /**
     * Requires the distance hysteresis band to be wider than a viewer's reach between two samples.
     *
     * <p>The anti-flap guarantee, and the one relationship in this file that is about a product rather
     * than a pair. A viewer who can cross the whole band between two samples gets no hysteresis at all:
     * inside on one pass, outside on the next, with a full respawn of every bone per flip. The symptom
     * is a model that strobes while somebody runs past it, which looks like a rendering fault rather
     * than like a configuration one.
     *
     * <p>Reported against the margin because that is the key whose job is to be large enough, and with
     * every term spelled out because the figure at fault is in none of the three keys.
     *
     * @param checks   the accumulator receiving the complaint
     * @param margin   the loaded exit margin
     * @param speed    the loaded assumed viewer speed
     * @param interval the loaded sampling interval
     */
    private static void checkMarginSurvivesSampling(
            @NotNull ConfigChecks checks,
            double margin,
            double speed,
            int interval
    ) {
        if (!isUsableDistance(margin) || !isUsableDistance(speed) || interval < 1) {
            // Already rejected on its own key; a derived complaint would only add noise.
            return;
        }
        final double reach = speed * interval;
        if (margin > reach) {
            return;
        }
        checks.reject(ConfigFile.VIEW, "exitMarginBlocks", "is " + margin
                + " blocks, which does not exceed the " + reach + " blocks a viewer covers between two"
                + " samples at maxViewerSpeedBlocksPerTick " + speed + " over sampleIntervalTicks "
                + interval + ": the hysteresis band could be crossed between samples, so a model would"
                + " flip on and off while somebody runs past it and every flip re-sends every bone."
                + " Raise exitMarginBlocks, lower sampleIntervalTicks, or lower"
                + " maxViewerSpeedBlocksPerTick.");
    }

    /**
     * Whether a positive quantity from {@code view.yml} is worth deriving a second complaint from.
     *
     * <p>Every relationship in that file is between values that are already required to be finite and
     * positive on their own keys, so one that is not has produced a diagnostic the operator will read
     * first. A second complaint derived from it would name a key that is not at fault.
     *
     * @param value the loaded value
     * @return {@code true} when the value passed its own check
     */
    private static boolean isUsableDistance(double value) {
        return !Double.isNaN(value) && value > 0D;
    }

    /**
     * Whether a half-angle from {@code view.yml} is worth deriving a second complaint from.
     *
     * @param degrees the loaded value
     * @return {@code true} when the angle is strictly inside the legal interval
     */
    private static boolean isUsableHalfAngle(double degrees) {
        return !Double.isNaN(degrees) && degrees > 0D && degrees < VIEW_CONE_LIMIT_DEGREES;
    }

    /**
     * Checks {@code binding.yml}.
     *
     * <p>One key, and the floor on it is a platform constraint rather than a preference: the entity
     * scheduler treats any period below one tick as one, so a file saying zero would configure a value
     * that is not the value that runs. Refusing it is what keeps the configured number and the scheduled
     * number the same number.
     *
     * <p>No cross-file check, deliberately. A sampling period longer than the simulation's step means the
     * simulation reads a sample that is a few ticks old, which is the trade the key exists to offer — not
     * an incoherent combination, and the same reasoning that keeps the decoration driver's rate
     * un-cross-checked against the loop.
     *
     * @param binding the loaded configuration
     * @param checks  the accumulator receiving the complaints
     */
    private static void checkBinding(@NotNull BindingSettings binding, @NotNull ConfigChecks checks) {
        checks.inRange(ConfigFile.BINDING, "samplePeriodTicks", binding.samplePeriodTicks(),
                1, MAX_SAMPLE_PERIOD_TICKS);
    }

    /**
     * Checks {@code command.yml}.
     *
     * <h2>Only text, and only presence</h2>
     * There is no number in this file and nothing to put a range on, so every check is one of the two
     * text checks — which is the whole asymmetry worth pinning down. Four of the lines are the command's
     * <em>only</em> answer to whoever typed it, so blanking one produces a command that silently appears
     * to do nothing; those are refused with the key named. The other three are lines an operator may
     * legitimately not want, and emptying a key is how they turn one off, so for those only a key that
     * is present with no value at all is refused.
     *
     * <p>The MiniMessage templates themselves are not parsed here. A malformed tag renders as literal
     * text rather than throwing, and this class deliberately knows nothing about Adventure — see
     * {@link #menuSettings}, which makes the same choice for the same reason.
     *
     * @param command the loaded configuration
     * @param checks  the accumulator receiving the complaints
     */
    private static void checkCommand(@NotNull CommandSettings command, @NotNull ConfigChecks checks) {
        final ConfigFile file = ConfigFile.COMMAND;

        checks.text(file, "menu.notPlayerMessage", command.menuNotPlayerMessage());
        checks.optionalText(file, "reload.startedMessage", command.reloadStartedMessage());
        checks.text(file, "reload.appliedMessage", command.reloadAppliedMessage());
        checks.optionalText(file, "reload.rebuildMessage", command.reloadRebuildMessage());
        checks.text(file, "reload.refusedMessage", command.reloadRefusedMessage());
        checks.text(file, "reload.diagnosticLine", command.reloadDiagnosticLine());
        checks.text(file, "reload.busyMessage", command.reloadBusyMessage());
        checks.text(file, "reload.failedMessage", command.reloadFailedMessage());
    }

    /**
     * Checks the invariants that span two files.
     *
     * @param physics    the loaded {@code physics.yml}
     * @param render     the loaded {@code render.yml}
     * @param simulation the loaded {@code simulation.yml}
     * @param vehicle    the loaded {@code vehicle.yml}
     * @param checks     the accumulator receiving the complaints
     */
    private static void checkAcrossFiles(
            @NotNull PhysicsSettings physics,
            @NotNull RenderSettings render,
            @NotNull SimulationSettings simulation,
            @NotNull VehicleSettings vehicle,
            @NotNull ConfigChecks checks
    ) {
        checkVehicleAgainstDriverBounds(vehicle, simulation, checks);

        // A rig the solver has declared settled must be a rig the renderer declares silent. With the
        // thresholds the other way round, the last few substeps of float noise stay above the
        // renderer's epsilon forever and a motionless model emits packets for the rest of its life.
        checks.atMost(ConfigFile.PHYSICS, "restEpsilonRadians", physics.restEpsilonRadians(),
                ConfigFile.RENDER, "boneDirtyEpsilonRadians", render.boneDirtyEpsilonRadians());

        checkRateCoherence(render.emissionHz(), simulation.loopRateHz(), checks);
    }

    /**
     * Checks that the fastest permitted vehicle is still movement rather than a teleport.
     *
     * <p>The pose pipeline differences an entity's position once per tick and discards any delta above
     * {@code maxStepBlocks} as a teleport, because a teleport fed to the wind term launches every bone in
     * the rig at once. That filter is exactly right for a portal and exactly wrong for a vehicle whose
     * top speed happens to sit above the threshold: at full speed the gait is handed a motionless owner,
     * so the legs stop moving precisely when the vehicle is going fastest, and nothing anywhere says why.
     *
     * <p>Checked against the boosted top speed rather than the plain one, since that is the fastest delta
     * the sampler will ever see, and reported against the vehicle file because {@code maxStepBlocks} is a
     * statement about what entities on this server do in general while the vehicle ceiling is a statement
     * about what this plugin may add to that.
     *
     * @param vehicle    the loaded {@code vehicle.yml}
     * @param simulation the loaded {@code simulation.yml}
     * @param checks     the accumulator receiving the complaints
     */
    private static void checkVehicleAgainstDriverBounds(
            @NotNull VehicleSettings vehicle,
            @NotNull SimulationSettings simulation,
            @NotNull ConfigChecks checks
    ) {
        final float maxSpeed = vehicle.maxSpeedBlocksPerTick();
        final float maxBoost = vehicle.maxBoostMultiplier();
        final float maxStep = simulation.maxStepBlocks();
        if (Float.isNaN(maxSpeed) || Float.isNaN(maxBoost) || Float.isNaN(maxStep)) {
            // Already rejected on its own key; a derived complaint would only add noise.
            return;
        }
        final float boosted = maxSpeed * maxBoost;
        if (boosted > maxStep) {
            // Reported with the product spelled out rather than through the plain two-value check,
            // because the number that is too large belongs to neither key on its own and an operator
            // reading "maxSpeedBlocksPerTick is 1.25" when the file says 0.5 would stop trusting the
            // diagnostics.
            checks.reject(ConfigFile.VEHICLE, "maxSpeedBlocksPerTick", "times maxBoostMultiplier is "
                    + boosted + " blocks per tick, which exceeds maxStepBlocks in simulation.yml, set to "
                    + maxStep + ": the pose pipeline would discard a full-speed vehicle's movement as a"
                    + " teleport, so its gait would freeze exactly when it is going fastest. Lower the"
                    + " speed, lower the boost, or raise maxStepBlocks.");
        }
    }

    /**
     * Checks that the loop rate and the emission rate can produce a cadence at all.
     *
     * <p>Emission happens every <em>whole</em> number of simulation steps, so the two rates have to be
     * a clean multiple. They are not interchangeable settings that merely look odd together: a loop
     * slower than emission would emit poses that were never computed, and a loop that is not a multiple
     * of the emission rate makes the interval between two updates alternate between two lengths — so the
     * interpolation window, which is sized once, covers one of them and not the other, and the motion
     * eases for part of every second interval and then sits still. {@link EmissionCadence} refuses both,
     * loudly, at construction.
     *
     * <p>Which is exactly why they are checked here. Publishing a snapshot the layer that builds the
     * cadence cannot use would convert a diagnostic naming two keys into an exception thrown somewhere
     * downstream, after the reload had already been reported as applied.
     *
     * @param emissionHz the configured emission rate
     * @param loopRateHz the configured loop rate
     * @param checks     the accumulator receiving the complaints
     */
    private static void checkRateCoherence(int emissionHz, int loopRateHz, @NotNull ConfigChecks checks) {
        if (emissionHz < 1 || loopRateHz < 1) {
            // Already rejected on its own key; a derived complaint would only add noise.
            return;
        }
        if (loopRateHz < emissionHz) {
            checks.reject(ConfigFile.RENDER, "emissionHz", "must not exceed loopRateHz in simulation.yml,"
                    + " which is " + loopRateHz + ", but is " + emissionHz
                    + ": a slower loop would emit poses that were never computed");
            return;
        }
        if (loopRateHz % emissionHz != 0) {
            checks.reject(ConfigFile.RENDER, "emissionHz", "must divide loopRateHz in simulation.yml,"
                    + " which is " + loopRateHz + ", but " + loopRateHz + " / " + emissionHz
                    + " leaves a remainder: the interval between two updates would alternate between two"
                    + " lengths and the interpolation window can only match one of them");
        }
    }

    // =================================================================================================
    // Construction of the api value objects
    //
    // Every call into the api module lives in this block and nowhere else, so the shape of the api
    // value types is coupled to exactly one method each.
    // =================================================================================================

    /**
     * Builds the solver's calibration.
     *
     * @param physics the validated configuration
     * @return the tuning the solver reads every substep
     */
    private static @NotNull PhysicsTuning physicsTuning(@NotNull PhysicsSettings physics) {
        return new PhysicsTuning(
                physics.stiffnessToRate(),
                physics.dampingToRate(),
                physics.gravityToRate(),
                physics.windToRadians(),
                physics.turnToSpeed(),
                physics.substepRateHz(),
                physics.maxSubsteps(),
                physics.restEpsilonRadians());
    }

    /**
     * Builds the bounds a rig's physics profile is validated against.
     *
     * @param physics the validated configuration
     * @return the profile bounds
     */
    private static @NotNull ProfileBounds profileBounds(@NotNull PhysicsSettings physics) {
        return new ProfileBounds(
                physics.minMass(),
                physics.maxMass(),
                physics.maxGravity(),
                physics.maxWind(),
                physics.maxCollisionRadius(),
                physics.collisionRadiusToRadians(),
                physics.defaultLimitDegrees());
    }

    /**
     * Builds the coefficients a rig file inherits for anything it omits.
     *
     * <p>Chains {@link ProfileDefaults#requireWithin(ProfileBounds)} as a backstop rather than as the
     * check that reports the problem: {@link #checkProfileDefaults} has already refused this
     * combination with a diagnostic naming the file and the key, so reaching the throw means the
     * validator and the api disagree about what is legal — a defect in one of them, and one that should
     * not be papered over by publishing a snapshot no rig can load against.
     *
     * @param physics the validated configuration
     * @return the profile defaults
     */
    private static @NotNull ProfileDefaults profileDefaults(@NotNull PhysicsSettings physics) {
        return new ProfileDefaults(
                physics.defaultStiffness(),
                physics.defaultDamping(),
                physics.defaultLag(),
                physics.defaultMass(),
                physics.defaultWind(),
                physics.defaultPendulumGravity(),
                physics.defaultSpringGravity(),
                physics.defaultClothStripGravity(),
                physics.defaultDragGravity(),
                physics.defaultReachX(),
                physics.defaultReachY(),
                physics.defaultReachZ())
                .requireWithin(profileBounds(physics));
    }

    /**
     * Builds the bounds a rig as a whole is validated against.
     *
     * @param physics the validated configuration
     * @return the rig bounds
     */
    private static @NotNull RigBounds rigBounds(@NotNull PhysicsSettings physics) {
        return new RigBounds(physics.maxBones());
    }

    /**
     * Builds the walk cycle's calibration.
     *
     * <p>A builder rather than a constructor because thirty positional {@code float}s in a row is a
     * defect waiting to happen: two transposed arguments compile, pass every type check, and produce a
     * model that walks slightly wrong.
     *
     * @param gait the validated configuration
     * @return the gait tuning the walk cycle reads
     */
    private static @NotNull GaitTuning gaitTuning(@NotNull GaitSettings gait) {
        return GaitTuning.builder()
                .counterPhaseCycles(gait.counterPhaseCycles())
                .restingLegExtension(gait.restingLegExtension())
                .movementDeadbandBlocksPerTick(gait.movementDeadbandBlocksPerTick())
                .sneakTorsoPitchRadians(gait.sneakTorsoPitchRadians())
                .fullStrideSpeedBlocksPerTick(gait.fullStrideSpeedBlocksPerTick())
                .sprintSpeedBlocksPerTick(gait.sprintSpeedBlocksPerTick())
                .strideToFootReach(gait.strideToFootReach())
                .footLiftFraction(gait.footLiftFraction())
                .footToeOffRadians(gait.footToeOffRadians())
                .footHeelStrikeRadians(gait.footHeelStrikeRadians())
                .maxIkReachFraction(gait.maxIkReachFraction())
                .minIkDownFraction(gait.minIkDownFraction())
                .legSwingRadians(gait.legSwingRadians())
                .sprintLegSwingRadians(gait.sprintLegSwingRadians())
                .armSwingRadians(gait.armSwingRadians())
                .forearmCurlRadians(gait.forearmCurlRadians())
                .torsoLeanRadians(gait.torsoLeanRadians())
                .torsoBobRadians(gait.torsoBobRadians())
                .torsoBobHarmonic(gait.torsoBobHarmonic())
                .torsoSwayRadians(gait.torsoSwayRadians())
                .torsoTwistRadians(gait.torsoTwistRadians())
                .breathRateHz(gait.breathRateHz())
                .breathRadians(gait.breathRadians())
                .amplitudeBlendSeconds(gait.amplitudeBlendSeconds())
                .airborneBlendSeconds(gait.airborneBlendSeconds())
                .sneakBlendSeconds(gait.sneakBlendSeconds())
                .maxStepSeconds(gait.maxStepSeconds())
                .maxGaitSpeedBlocksPerTick(gait.maxGaitSpeedBlocksPerTick())
                .maxPhaseAdvancePerStep(gait.maxPhaseAdvancePerStep())
                .elapsedWrapBreathCycles(gait.elapsedWrapBreathCycles())
                .settledAmplitude(gait.settledAmplitude())
                .defaultStrideLength(gait.defaultStrideLength())
                .defaultHeadStabilise(gait.defaultHeadStabilise())
                .defaultHandStabilise(gait.defaultHandStabilise())
                .build();
    }

    /**
     * Builds the bounds a locomotion binding and a gait profile are validated against.
     *
     * @param gait the validated configuration
     * @return the binding bounds
     */
    private static @NotNull BindingBounds bindingBounds(@NotNull GaitSettings gait) {
        return new BindingBounds(
                gait.minSegmentLength(),
                gait.maxSegmentLength(),
                gait.minStrideLength(),
                gait.maxStrideLength(),
                gait.maxScale());
    }

    /**
     * Builds the window an emission cadence may choose from.
     *
     * @param render the validated configuration
     * @return the cadence bounds
     */
    private static @NotNull CadenceBounds cadenceBounds(@NotNull RenderSettings render) {
        return new CadenceBounds(render.minInterpolationTicks(), render.maxInterpolationTicks());
    }

    /**
     * Builds the per-tick movement a driver sample treats as real.
     *
     * @param simulation the validated configuration
     * @return the driver bounds
     */
    private static @NotNull DriverBounds driverBounds(@NotNull SimulationSettings simulation) {
        return new DriverBounds(simulation.maxStepBlocks(), simulation.maxVerticalStepBlocks());
    }

    /**
     * Builds the degenerate-target guard of the two-bone solver.
     *
     * @param simulation the validated configuration
     * @return the IK tolerance
     */
    private static @NotNull IkTolerance ikTolerance(@NotNull SimulationSettings simulation) {
        return new IkTolerance(simulation.ikDegenerateTargetBlocks());
    }

    /**
     * Collects the render values the api has no type for.
     *
     * @param render the validated configuration
     * @return the render options
     */
    private static @NotNull ConfigSnapshot.RenderOptions renderOptions(@NotNull RenderSettings render) {
        return new ConfigSnapshot.RenderOptions(
                render.emissionHz(),
                render.jitterTolerance(),
                render.boneDirtyEpsilonRadians(),
                render.boneNamesInMessage());
    }

    /**
     * Collects the simulation values the api has no type for.
     *
     * @param simulation the validated configuration
     * @return the simulation options
     */
    private static @NotNull ConfigSnapshot.SimulationOptions simulationOptions(
            @NotNull SimulationSettings simulation
    ) {
        return new ConfigSnapshot.SimulationOptions(
                simulation.loopRateHz(),
                simulation.errorLogIntervalSeconds());
    }

    /**
     * Collects the ambient field a decoration is driven by.
     *
     * <p>Separate from {@link #simulationOptions} even though both come out of {@code simulation.yml},
     * because the two are read by different layers at different rates — once per driver pass against
     * once per simulation step — and a reader that took one of them has no use for the other.
     *
     * @param simulation the validated configuration
     * @return the decoration options
     */
    private static @NotNull ConfigSnapshot.DecorationOptions decorationOptions(
            @NotNull SimulationSettings simulation
    ) {
        return new ConfigSnapshot.DecorationOptions(
                simulation.decorationDriverUpdateHz(),
                simulation.decorationWindBaseSpeedBlocksPerTick(),
                simulation.decorationWindGustSpeedBlocksPerTick(),
                simulation.decorationWindGustRateHz(),
                simulation.decorationWindGustHarmonicRatio(),
                simulation.decorationWindGustHarmonicWeight(),
                simulation.decorationWindGustWavelengthBlocks(),
                simulation.decorationWindDirectionDegrees(),
                simulation.decorationWindDirectionSwayDegrees(),
                simulation.decorationWindDirectionSwayRateHz(),
                simulation.decorationWindVerticalFraction());
    }

    /**
     * The limits a plan is validated against.
     *
     * <p>The tick counts and the radius are declared as {@code int} and {@code float} in the file and
     * widened here, because that is how the rest of this project's declarations are typed and because
     * an operator does not think in longs. The widening is exact for every value the validator admits:
     * the tick ceilings are well inside the range a {@code float} represents without loss, and the
     * radius is a quantity whose last bit has never mattered to anything.
     *
     * @param ai the loaded configuration
     * @return the bounds
     */
    private static @NotNull AiBounds aiBounds(@NotNull AiSettings ai) {
        return new AiBounds(
                ai.maxActivities(),
                ai.maxBehavioursPerActivity(),
                ai.maxPriority(),
                ai.maxSensors(),
                ai.minSensorPeriodTicks(),
                ai.maxSensorPeriodTicks(),
                ai.maxScanRadiusBlocks(),
                ai.maxMemoryExpiryTicks(),
                ai.maxMemoriesPerPlan());
    }

    /**
     * The values of {@code ai.yml} that no api type carries.
     *
     * @param ai the loaded configuration
     * @return the options
     */
    private static @NotNull ConfigSnapshot.AiOptions aiOptions(@NotNull AiSettings ai) {
        return new ConfigSnapshot.AiOptions(
                ai.faultLogIntervalSeconds(),
                ai.faultsBeforeDisable());
    }

    /**
     * The limits a vehicle profile is validated against.
     *
     * @param vehicle the loaded configuration
     * @return the bounds
     */
    private static @NotNull VehicleBounds vehicleBounds(@NotNull VehicleSettings vehicle) {
        return new VehicleBounds(
                vehicle.maxSpeedBlocksPerTick(),
                vehicle.maxAccelerationBlocksPerTickSquared(),
                vehicle.maxTurnRateDegreesPerTick(),
                vehicle.maxBoostMultiplier());
    }

    /**
     * The values of {@code vehicle.yml} that no api type carries.
     *
     * @param vehicle the loaded configuration
     * @return the options
     */
    private static @NotNull ConfigSnapshot.VehicleOptions vehicleOptions(
            @NotNull VehicleSettings vehicle
    ) {
        return new ConfigSnapshot.VehicleOptions(
                vehicle.controlPeriodTicks(),
                vehicle.maxStepTicks(),
                vehicle.maxLocalStepBlocks(),
                vehicle.restSpeedBlocksPerTick());
    }

    /**
     * Everything a menu says and every bound it offers, as one value.
     *
     * <h2>Why the whole record rather than an options record beside it</h2>
     * Every other file in this package is mapped into either an api value type or a nested
     * {@code ConfigSnapshot} options record. This one goes straight to the menu layer's own value, and
     * the reason is arithmetic: the record has one component per key and there are seventy of them, so a
     * nested options record would be a second declaration of the same seventy components whose only job
     * is to be copied into the first. The duplication would be the defect, not the coupling.
     *
     * <h2>Why the text is not parsed here</h2>
     * The labels stay MiniMessage source rather than becoming components. Half of them carry a
     * placeholder whose value only exists when a screen is built — the model being previewed, the page
     * the player is on — so a component parsed at reload time would have to be re-parsed anyway. It also
     * keeps this class free of Adventure, which is what lets it stay a pure validator.
     *
     * @param menu the validated configuration
     * @return the menu settings
     */
    private static @NotNull pandastic.studio.models.internal.menu.MenuSettings menuSettings(
            @NotNull MenuSettings menu
    ) {
        return new pandastic.studio.models.internal.menu.MenuSettings(
                new Session(menu.sessionTimeoutSeconds(), menu.sessionMaxSessions()),
                new Layout(
                        menu.layoutBodyWidth(),
                        menu.layoutInputWidth(),
                        menu.layoutButtonWidth(),
                        menu.layoutPageIndicator(),
                        menu.layoutNextPageLabel(),
                        menu.layoutPreviousPageLabel(),
                        menu.layoutBackLabel(),
                        menu.layoutCloseLabel()),
                new Picker(
                        menu.pickerTitle(),
                        menu.pickerHeading(),
                        menu.pickerPageSize(),
                        menu.pickerChooserLabel(),
                        menu.pickerEmptyMessage(),
                        menu.pickerWearLabel(),
                        menu.pickerWearTooltip(),
                        menu.pickerRemoveLabel(),
                        menu.pickerRemoveTooltip(),
                        menu.pickerRefreshLabel(),
                        menu.pickerRefreshTooltip(),
                        menu.pickerReloadLabel(),
                        menu.pickerReloadTooltip(),
                        menu.pickerAppearanceLabel(),
                        menu.pickerAnimationsLabel(),
                        menu.pickerWornLine(),
                        menu.pickerNothingWornLine(),
                        menu.pickerUnknownModelMessage(),
                        menu.pickerWornMessage(),
                        menu.pickerRemovedMessage(),
                        menu.pickerReloadedMessage(),
                        menu.pickerThumbnailMaterial(),
                        menu.pickerThumbnailItemModel(),
                        menu.pickerThumbnailWidth(),
                        menu.pickerThumbnailHeight(),
                        menu.pickerThumbnailDescription()),
                new Appearance(
                        menu.appearanceTitle(),
                        menu.appearanceHeading(),
                        menu.appearanceGlowLabel(),
                        menu.appearanceRedLabel(),
                        menu.appearanceGreenLabel(),
                        menu.appearanceBlueLabel(),
                        menu.appearanceChannelLabelFormat(),
                        menu.appearanceScaleLabel(),
                        menu.appearanceScaleLabelFormat(),
                        menu.appearancePhysicsLabel(),
                        menu.appearanceFirstPersonArmsLabel(),
                        menu.appearanceApplyLabel(),
                        menu.appearanceResetLabel(),
                        menu.appearanceSwatchGlyph(),
                        menu.appearanceSwatchLine(),
                        menu.appearanceSwatchNote(),
                        menu.appearanceAppliedMessage(),
                        menu.appearanceResetMessage(),
                        menu.appearanceNoModelMessage(),
                        menu.appearanceMinChannel(),
                        menu.appearanceMaxChannel(),
                        menu.appearanceChannelStep(),
                        menu.appearanceMinScale(),
                        menu.appearanceMaxScale(),
                        menu.appearanceScaleStep(),
                        new ModelAppearance(
                                menu.appearanceDefaultGlow(),
                                menu.appearanceDefaultRed(),
                                menu.appearanceDefaultGreen(),
                                menu.appearanceDefaultBlue(),
                                menu.appearanceDefaultScale(),
                                menu.appearanceDefaultPhysics(),
                                menu.appearanceDefaultFirstPersonArms())),
                new Animation(
                        menu.animationTitle(),
                        menu.animationHeading(),
                        menu.animationPageSize(),
                        menu.animationEmptyMessage(),
                        menu.animationEntryLabel(),
                        menu.animationEntryTooltip(),
                        menu.animationStopLabel(),
                        menu.animationStopTooltip(),
                        menu.animationPlayedMessage(),
                        menu.animationStoppedMessage(),
                        menu.animationUnknownAnimationMessage(),
                        menu.animationNoModelMessage()));
    }

    /**
     * What governs the culling decision, as one value.
     *
     * <h2>Why the view layer's own type rather than an options record</h2>
     * For the reason {@link #menuSettings} gives in the small: the record has one component per key and
     * nothing else, so a nested options record beside it would be a second declaration of the same
     * eleven components whose only job is to be copied into the first. It also happens to be the type
     * with the most to say about <em>why</em> these eleven numbers are what they are, and a reader
     * following a value back from the culler should arrive there rather than here.
     *
     * <h2>The constructor is a backstop, not the check</h2>
     * It refuses all three of the relationships {@link #checkView} has already refused with a diagnostic
     * naming the keys. Reaching the throw therefore means the validator and the value type disagree
     * about what is legal — a defect in one of them, and not one to be papered over by publishing a
     * snapshot whose settings no pass can be run with. Exactly the arrangement
     * {@link #profileDefaults} uses.
     *
     * @param view the validated configuration
     * @return the view settings
     */
    private static @NotNull pandastic.studio.models.internal.view.ViewSettings viewSettings(
            @NotNull ViewSettings view
    ) {
        return new pandastic.studio.models.internal.view.ViewSettings(
                view.viewRangeBlocks(),
                view.nearRadiusBlocks(),
                view.exitMarginBlocks(),
                view.coneEnterDegrees(),
                view.coneExitDegrees(),
                view.cameraOffsetBlocks(),
                view.defaultModelRadiusBlocks(),
                view.maxViewerSpeedBlocksPerTick(),
                view.sampleIntervalTicks(),
                view.honourClientViewDistance(),
                view.spawnBudgetPerViewer());
    }

    /**
     * What the binding layer reads when it dispatches a sampler, as one value.
     *
     * <p>The period is declared as an {@code int} in the file and widened to the {@code long} the
     * platform's scheduler takes, because that is how the rest of this project's declarations are typed
     * and because an operator does not think in longs. The widening is exact for every value the
     * validator admits, all of which are under a second's worth of ticks.
     *
     * @param binding the validated configuration
     * @return the binding settings
     */
    private static @NotNull pandastic.studio.models.internal.binding.BindingSettings bindingSettings(
            @NotNull BindingSettings binding
    ) {
        return new pandastic.studio.models.internal.binding.BindingSettings(
                binding.samplePeriodTicks());
    }

    /**
     * Every line the reload command says, as one value.
     *
     * <p>The command layer's own type rather than a nested options record, for the reason
     * {@link #menuSettings} gives: it has one component per key and nothing else, so an options record
     * beside it would be a second declaration of the same seven whose only job is to be copied into the
     * first.
     *
     * <p>The templates stay MiniMessage source. Every one of them carries a placeholder whose value only
     * exists when the command answers — the revision that was published, the rig count, the diagnostic
     * being listed — so a component parsed here would have to be re-parsed anyway, and parsing it here
     * would put Adventure into a class whose whole value is being a pure validator.
     *
     * @param command the validated configuration
     * @return the command settings
     */
    private static @NotNull pandastic.studio.models.internal.command.CommandSettings commandSettings(
            @NotNull CommandSettings command
    ) {
        return new pandastic.studio.models.internal.command.CommandSettings(
                command.menuNotPlayerMessage(),
                command.reloadStartedMessage(),
                command.reloadAppliedMessage(),
                command.reloadRebuildMessage(),
                command.reloadRefusedMessage(),
                command.reloadDiagnosticLine(),
                command.reloadBusyMessage(),
                command.reloadFailedMessage());
    }

    /**
     * What came out of one mapping attempt: the snapshot, or everything that was wrong.
     *
     * <p>The same shape as {@link pandastic.studio.models.internal.rig.RigLoadResult}, deliberately —
     * a reader who has met one has met the other. The difference is that this one has no partial
     * success to report: either every file was good and there is a snapshot, or there is none.
     *
     * @param snapshot    the validated snapshot, or {@code null} when it was refused
     * @param diagnostics everything that was wrong, empty on success
     */
    public record Result(@Nullable ConfigSnapshot snapshot, @NotNull List<ConfigDiagnostic> diagnostics) {

        public Result {
            diagnostics = List.copyOf(diagnostics);
            if ((snapshot == null) == diagnostics.isEmpty()) {
                throw new IllegalArgumentException(
                        "A mapping result carries either a snapshot or at least one diagnostic");
            }
        }

        /** Whether a snapshot came out of this mapping. */
        public boolean isSuccess() {
            return this.snapshot != null;
        }

        /**
         * The snapshot, for a caller that treats a refusal as a programming error rather than as
         * operator input — which is exactly what {@link ConfigSnapshot#defaults()} is.
         *
         * @return the snapshot
         * @throws IllegalStateException if the mapping was refused, with every diagnostic in the message
         */
        public @NotNull ConfigSnapshot snapshotOrThrow() {
            if (this.snapshot == null) {
                final StringJoiner joined = new StringJoiner("; ");
                for (final ConfigDiagnostic diagnostic : this.diagnostics) {
                    joined.add(diagnostic.format());
                }
                throw new IllegalStateException("The configuration was refused: " + joined);
            }
            return this.snapshot;
        }
    }
}
