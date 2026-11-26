package pandastic.studio.models.internal.command;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.model.Model;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.config.ConfigSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The snapshots, rigs and settings the status tests are driven with.
 *
 * <h2>Why the settings come from the real defaults</h2>
 * Same argument {@link CommandFixtures} makes about the reload command's wording: "the configured line is
 * the one that was printed" is a claim about what an operator typed, and the only path from a typed value
 * to a live setting runs through the declaration, the validator and the mapper. A hand-built record would
 * assert that the reporter reads a field, which nothing doubts — and would not catch a key added to
 * {@code command.yml} and never wired into the value type.
 *
 * <h2>Why the snapshots are hand-built</h2>
 * The opposite reason. A {@link StatusSnapshot} is what the <em>server</em> said, and the states worth
 * asserting — a stopped loop, a refused reload, an input layer missing its packet source — are states a
 * test cannot get a real server into. The snapshot being a platform-free value is exactly what makes
 * them reachable.
 */
final class StatusFixtures {

    private StatusFixtures() {
    }

    /** The declared defaults, which is what a server starts with. */
    static @NotNull StatusSettings settings() {
        return ConfigSnapshot.defaults().command().status();
    }

    /** The defaults with the verbosity switch on, which is the long form of both reports. */
    static @NotNull StatusSettings verbose() {
        final StatusSettings defaults = settings();
        return new StatusSettings(true, defaults.showKnownGaps(), defaults.box(),
                defaults.health(), defaults.statistics());
    }

    /** The defaults with the known gaps suppressed. */
    static @NotNull StatusSettings withoutGaps() {
        final StatusSettings defaults = settings();
        return new StatusSettings(defaults.verbose(), false, defaults.box(),
                defaults.health(), defaults.statistics());
    }

    /** A server where every part is working as configured. */
    static @NotNull StatusSnapshot healthy() {
        return new StatusSnapshot(
                new StatusSnapshot.Renderer(true, "TestRenderer", List.of()),
                new StatusSnapshot.Configuration(7L, ReloadOutcome.APPLIED, 0),
                new StatusSnapshot.Rigs(3, 24),
                runningLoop(60, 4),
                runningLoop(20, 2),
                new StatusSnapshot.Emission(20, 2),
                new StatusSnapshot.Ai(true, "", 11),
                new StatusSnapshot.Input(Set.of(), "", 5, 0),
                new StatusSnapshot.World(4, 2, 1, 7, 48));
    }

    /** A live loop at {@code hz} driving {@code registrations} things. */
    static @NotNull StatusSnapshot.Loop runningLoop(int hz, int registrations) {
        final long period = 1_000_000_000L / hz;
        return new StatusSnapshot.Loop(true, hz, period, period, registrations);
    }

    /**
     * A loop running at a period the configuration no longer asks for.
     *
     * <p>The state a reload that missed a layer leaves behind, and the one loop fault that is otherwise
     * invisible.
     */
    static @NotNull StatusSnapshot.Loop staleLoop(int configuredHz, int dispatchedHz) {
        return new StatusSnapshot.Loop(true, configuredHz, 1_000_000_000L / configuredHz,
                1_000_000_000L / dispatchedHz, 1);
    }

    /** A loop whose repeating task is not dispatched at all. */
    static @NotNull StatusSnapshot.Loop stoppedLoop(int hz) {
        return new StatusSnapshot.Loop(false, hz, 1_000_000_000L / hz, 0L, 0);
    }

    /** {@code healthy()} with its simulation loop replaced. */
    static @NotNull StatusSnapshot withSimulation(@NotNull StatusSnapshot.Loop loop) {
        final StatusSnapshot base = healthy();
        return new StatusSnapshot(base.renderer(), base.configuration(), base.rigs(), loop,
                base.drivers(), base.emission(), base.ai(), base.input(), base.world());
    }

    /** {@code healthy()} with its configuration part replaced. */
    static @NotNull StatusSnapshot withConfiguration(@NotNull StatusSnapshot.Configuration configuration) {
        final StatusSnapshot base = healthy();
        return new StatusSnapshot(base.renderer(), configuration, base.rigs(), base.simulation(),
                base.drivers(), base.emission(), base.ai(), base.input(), base.world());
    }

    /** {@code healthy()} with its AI part replaced. */
    static @NotNull StatusSnapshot withAi(@NotNull StatusSnapshot.Ai ai) {
        final StatusSnapshot base = healthy();
        return new StatusSnapshot(base.renderer(), base.configuration(), base.rigs(), base.simulation(),
                base.drivers(), base.emission(), ai, base.input(), base.world());
    }

    /** {@code healthy()} with its input part replaced. */
    static @NotNull StatusSnapshot withInput(@NotNull StatusSnapshot.Input input) {
        final StatusSnapshot base = healthy();
        return new StatusSnapshot(base.renderer(), base.configuration(), base.rigs(), base.simulation(),
                base.drivers(), base.emission(), base.ai(), input, base.world());
    }

    /** {@code healthy()} with its world part replaced. */
    static @NotNull StatusSnapshot withWorld(@NotNull StatusSnapshot.World world) {
        final StatusSnapshot base = healthy();
        return new StatusSnapshot(base.renderer(), base.configuration(), base.rigs(), base.simulation(),
                base.drivers(), base.emission(), base.ai(), base.input(), world);
    }

    /**
     * A rig with {@code bones} bones, built against the shipped bounds.
     *
     * <p>Every bone after the root is declared with no physics profile, which is legal and is what a
     * purely animation-owned bone looks like. The bone <em>count</em> is the only thing the status layer
     * reads off a rig, so a rig with profiles would be asserting something else.
     */
    static @NotNull Rig rig(@NotNull String name, int bones) {
        if (bones < 1) {
            throw new IllegalArgumentException("A rig has at least its root");
        }
        final ConfigSnapshot defaults = ConfigSnapshot.defaults();
        final Rig.Builder builder = Rig.builder(name, "test-model",
                defaults.rigBounds(), defaults.profileBounds()).root("root");
        for (int index = 1; index < bones; index++) {
            builder.bone("bone" + index, "root", null);
        }
        return builder.build();
    }

    /** {@code counts.length} models, the n-th of them carrying a rig of {@code counts[n]} bones. */
    static @NotNull List<Model> modelsWithBones(int @NotNull ... counts) {
        final List<Model> models = new ArrayList<>(counts.length);
        for (int index = 0; index < counts.length; index++) {
            models.add(new FixedRigModel(rig("rig" + index, counts[index])));
        }
        return models;
    }

    /**
     * A model that answers its rig and refuses everything else.
     *
     * <p>The refusal is the assertion. The status layer may read a model's rig, which is immutable data,
     * and may <strong>not</strong> read its entity: a command body runs on the region that owns the
     * sender, and a model's entity belongs to whichever region owns it. A capture that started reaching
     * for one would fail here rather than quietly passing and then racing a region thread in production,
     * which is a defect no test could otherwise see.
     */
    private record FixedRigModel(@NotNull Rig rig) implements Model {

        @Override
        public @NotNull UUID getUniqueIdentifier() {
            return UUID.nameUUIDFromBytes(this.rig.getName().getBytes());
        }

        @Override
        public @NotNull Rig getRig() {
            return this.rig;
        }

        @Override
        public boolean isSpawned() {
            throw new UnsupportedOperationException(
                    "The status layer asked a model whether it is spawned, which reaches the world");
        }

        @Override
        public void despawn() {
            throw new UnsupportedOperationException("A status report may not despawn anything");
        }
    }
}
