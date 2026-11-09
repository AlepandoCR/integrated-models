package pandastic.studio.models.internal.decoration;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.decoration.DecorationPlacement;
import pandastic.studio.models.api.physics.DriverBounds;
import pandastic.studio.models.api.physics.PhysicsTuning;
import pandastic.studio.models.api.render.CadenceBounds;
import pandastic.studio.models.api.render.EmissionCadence;
import pandastic.studio.models.api.rig.PhysicsKind;
import pandastic.studio.models.api.rig.PhysicsProfile;
import pandastic.studio.models.api.rig.ProfileBounds;
import pandastic.studio.models.api.rig.ProfileDefaults;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.rig.RigBounds;
import pandastic.studio.models.internal.physics.AngularPhysicsSolver;
import pandastic.studio.models.internal.sim.SimulationSettings;
import pandastic.studio.models.internal.sim.SimulationSettingsSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * The configuration and the rig the decoration tests run against.
 *
 * <p>The rates are the shipped ones, because a cadence has to agree with the loop's rate or
 * registration refuses. Everything the decoration layer <em>reads</em> — every coefficient of the
 * ambient field, the pass rate, the teleport thresholds — is given a deliberately unusual value by the
 * test that reads it, because a suite that only ever used the defaults cannot tell a layer that honours
 * its configuration from one that ignores it and agrees by accident.
 */
final class DecorationFixtures {

    static final int SIMULATION_HZ = 60;
    static final int EMISSION_HZ = 20;
    static final long ERROR_LOG_INTERVAL_NANOS = 5_000_000_000L;

    static final CadenceBounds CADENCE_BOUNDS = new CadenceBounds(1, 59);
    static final DriverBounds DRIVER_BOUNDS = new DriverBounds(1.5F, 6F);
    static final RigBounds RIG_BOUNDS = new RigBounds(256);

    static final ProfileBounds PROFILE_BOUNDS =
            new ProfileBounds(0.05F, 10F, 4F, 4F, 2F, 1.7F, 45F);

    static final ProfileDefaults PROFILE_DEFAULTS = new ProfileDefaults(
            0.08F, 0.015F, 0.6F, 1F, 1F,
            0.25F, 0F, 0.25F, 0.25F,
            0F, -1F, 0F);

    /** Where a decoration stands unless a test moves it. Nothing round, so a copy-paste shows up. */
    static final DecorationPlacement PLACEMENT = new DecorationPlacement(11.5D, 64.25D, -7.5D, 0F, 0F);

    private DecorationFixtures() {
    }

    /** The stock calibration, which every bone of {@link #rig()} is stable under. */
    static PhysicsTuning tuning() {
        return PhysicsTuning.builder()
                .stiffnessToRate(260F)
                .dampingToRate(120F)
                .gravityToRate(40F)
                .windToRadians(60F)
                .turnToSpeed(2F)
                .substepRateHz(SIMULATION_HZ)
                .maxSubsteps(4)
                .restEpsilonRadians(1.0E-5F)
                .build();
    }

    /** Two bones: an anchor and one pendulum hanging off it, which is what a banner is. */
    static Rig rig() {
        return rig("test-decoration");
    }

    static Rig rig(@NotNull String name) {
        return Rig.builder(name, "test-model", RIG_BOUNDS, PROFILE_BOUNDS)
                .root("body")
                .bone("cloth", "body", PhysicsProfile
                        .builder(PhysicsKind.CLOTH_STRIP, PROFILE_BOUNDS, PROFILE_DEFAULTS)
                        .damping(0.1F)
                        .mass(1F)
                        .build())
                .build();
    }

    /** The bone names a model matching {@link #rig()} would report. */
    static List<String> modelBoneNames() {
        return List.of("body", "cloth", "pole");
    }

    static SimulationSettings simulationSettings() {
        return simulationSettings(SIMULATION_HZ, EMISSION_HZ, DRIVER_BOUNDS);
    }

    /** A snapshot at chosen rates and thresholds, so a test can prove which of them is read. */
    static SimulationSettings simulationSettings(
            int simulationHz,
            int emissionHz,
            @NotNull DriverBounds driverBounds
    ) {
        return new SimulationSettings(
                new AngularPhysicsSolver(tuning()),
                simulationHz,
                EmissionCadence.of(simulationHz, emissionHz, CADENCE_BOUNDS),
                driverBounds,
                ERROR_LOG_INTERVAL_NANOS);
    }

    static SimulationSettingsSource simulationSource() {
        return SimulationSettingsSource.fixed(simulationSettings());
    }

    /**
     * A field nothing in the shipped configuration matches, for the tests that only need <em>some</em>
     * valid field.
     *
     * <p>Every number here differs from its default, which is what makes a value appearing in an
     * assertion evidence that the layer read it rather than a coincidence.
     */
    static DecorationSettings decorationSettings() {
        return new DecorationSettings(
                20, 0.03F, 0.05F, 0.37F, 3.3F, 0.4F, 17F, 33F, 9F, 0.11F, 0.3F);
    }

    /** A field that moves nothing: both speeds zero, so even a fully exposed decoration settles. */
    static DecorationSettings calmSettings() {
        return new DecorationSettings(
                20, 0F, 0F, 0.37F, 3.3F, 0.4F, 17F, 33F, 9F, 0.11F, 0.3F);
    }

    /**
     * A field with a steady wind and nothing else moving: no gust and no direction sway.
     *
     * <p>Both have to be off for the wind to be <em>constant</em>, and that is the whole subject of the
     * test that uses this — a constant force is what the solver can settle under. A field with a
     * swaying direction and no gust is still a moving field, which is a mistake worth having a named
     * fixture to avoid.
     */
    static DecorationSettings steadySettings() {
        return new DecorationSettings(
                20, 0.03F, 0F, 0.37F, 3.3F, 0.4F, 17F, 33F, 0F, 0.11F, 0.3F);
    }

    /**
     * A decoration settings source a test can move, which is what a reload looks like from in here.
     *
     * <p>It also counts reads, which is how the once-per-pass contract becomes testable: a pass that
     * consulted the source per decoration is indistinguishable from one that read it once until
     * somebody counts.
     */
    static final class MutableDecorationSource implements DecorationSettingsSource {

        private volatile DecorationSettings settings;
        private int reads;

        MutableDecorationSource(@NotNull DecorationSettings settings) {
            this.settings = settings;
        }

        @Override
        public DecorationSettings current() {
            this.reads++;
            return this.settings;
        }

        void publish(@NotNull DecorationSettings replacement) {
            this.settings = replacement;
        }

        int reads() {
            return this.reads;
        }

        void resetReads() {
            this.reads = 0;
        }
    }

    /**
     * What happened, in order, with each loop's membership observed at every entry.
     *
     * <p>The ordering claims the decoration layer makes span four collaborators — the simulation loop,
     * the driver loop, the rendering and the viewer list — and two of them cannot record anything. So
     * every entry carries how many simulations and how many drivers were registered when it was
     * written, which turns "unregistered before anything was released" from an assertion about an
     * invisible ordering into one about recorded text.
     */
    static final class Journal {

        private final List<String> entries = new ArrayList<>();
        private @Nullable IntSupplier stepping;
        private @Nullable IntSupplier driving;

        Journal observing(@NotNull IntSupplier simulations, @NotNull IntSupplier drivers) {
            this.stepping = simulations;
            this.driving = drivers;
            return this;
        }

        void record(@NotNull String event) {
            if (this.stepping == null || this.driving == null) {
                this.entries.add(event);
                return;
            }
            this.entries.add(event + "(stepping=" + this.stepping.getAsInt()
                    + ",driving=" + this.driving.getAsInt() + ")");
        }

        List<String> entries() {
            return List.copyOf(this.entries);
        }

        void clear() {
            this.entries.clear();
        }
    }
}
