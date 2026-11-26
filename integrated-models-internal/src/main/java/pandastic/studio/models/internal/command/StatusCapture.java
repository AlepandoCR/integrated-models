package pandastic.studio.models.internal.command;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.ai.CreatureAi;
import pandastic.studio.models.api.input.PlayerInputController;
import pandastic.studio.models.api.model.Model;
import pandastic.studio.models.api.model.ModelManager;
import pandastic.studio.models.api.render.EmissionCadence;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.decoration.DecorationDriverLoop;
import pandastic.studio.models.internal.sim.SimulationLoop;
import pandastic.studio.models.internal.sim.SimulationSettingsSource;
import pandastic.studio.models.internal.spi.ModelRenderBackendResolution;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Reads the seven live layers once and hands back a {@link StatusSnapshot}.
 *
 * <h2>This is the platform boundary of the status commands, and it is the whole of it</h2>
 * Everything that decides what a report <em>says</em> — the verdict, the verbosity, which gaps are named,
 * the column alignment — is in {@link StatusSnapshot} and {@link StatusReport}, both of which are pure
 * and tested. This class is the part that cannot be: it names the live loops, the live binders and the
 * live registry, none of which exists without a server. Keeping it to one method of straight-line reads
 * is what makes that acceptable, and is the same bargain {@code ReloadCommand.asyncOn} and the plugin's
 * own {@link ReloadOperation} lambda make.
 *
 * <h2>Nothing here is counted on demand</h2>
 * Every figure below is a field read, a map size or a copied collection. Two of them walk a collection —
 * the rigs' bone totals and the registered models' — and both walk pure data at the size of the server's
 * model count, which is the same work the registry's own {@code getModels()} already did to hand it over.
 * So a capture is cheap enough to run inline on the thread that typed the command, which is why the
 * status commands do not hop to the asynchronous scheduler the way a reload has to.
 *
 * <h2>The one rule a capture has to obey</h2>
 * <strong>It may not read an entity, a block or a chunk.</strong> A command body runs on the region that
 * owns the sender, and a model's entity belongs to whichever region owns it — which on Folia is a
 * different thread, with no protection and no report. That is why the bone total is taken from
 * {@link pandastic.studio.models.api.model.Model#getRig()}, which is immutable data, and why nothing here
 * calls {@code getEntity()}, {@code isAlive()} or anything that would reach through a handle into the
 * world.
 *
 * <h2>Why the loops are named concretely and the binders are not</h2>
 * The two loops are asked five questions each, including the two periods whose disagreement is the one
 * loop fault that is otherwise invisible, and no narrower seam would carry that without restating their
 * whole surface. The three binders are asked for one number each, so they arrive as
 * {@link IntSupplier}s — which keeps this class from naming three implementation types to learn their
 * sizes, and keeps the constructor readable at the wiring site.
 */
public final class StatusCapture implements StatusSource {

    /** Nanoseconds in a second. An SI unit conversion, which is why it is a literal. */
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final @NotNull ModelRenderBackendResolution backend;
    private final @NotNull LongSupplier revision;
    private final @NotNull ReloadHistory reloads;
    private final @NotNull Supplier<Collection<Rig>> rigs;
    private final @NotNull SimulationLoop simulation;
    private final @NotNull DecorationDriverLoop drivers;
    private final @NotNull SimulationSettingsSource simulationSettings;
    private final @NotNull ModelManager models;
    private final @NotNull IntSupplier boundModels;
    private final @NotNull IntSupplier boundDecorations;
    private final @NotNull IntSupplier boundVehicles;
    private final @NotNull Supplier<Optional<CreatureAi>> creatureAi;
    private final @NotNull Supplier<String> aiUnavailableReason;
    private final @NotNull PlayerInputController inputs;

    /**
     * A capture over everything the two reports read.
     *
     * @param backend             what the backend lookup found, resolved once at enable and immutable
     * @param revision            the publication counter of the live configuration snapshot
     * @param reloads             what the last observed reload did; the only instrumentation this
     *                            feature added
     * @param rigs                every rig currently loaded
     * @param simulation          the loop every model's secondary motion is stepped from
     * @param drivers             the loop every decoration's ambient driver is published from
     * @param simulationSettings  where the emission cadence is read from
     * @param models              the published registry
     * @param boundModels         how many live handles the model binder has handed out
     * @param boundDecorations    how many live handles the decoration binder has handed out
     * @param boundVehicles       how many live handles the vehicle binder has handed out
     * @param creatureAi          the AI service, or empty
     * @param aiUnavailableReason why there is none, or blank
     * @param inputs              what a consumer reaches to read and bind a player's input
     */
    public StatusCapture(
            @NotNull ModelRenderBackendResolution backend,
            @NotNull LongSupplier revision,
            @NotNull ReloadHistory reloads,
            @NotNull Supplier<Collection<Rig>> rigs,
            @NotNull SimulationLoop simulation,
            @NotNull DecorationDriverLoop drivers,
            @NotNull SimulationSettingsSource simulationSettings,
            @NotNull ModelManager models,
            @NotNull IntSupplier boundModels,
            @NotNull IntSupplier boundDecorations,
            @NotNull IntSupplier boundVehicles,
            @NotNull Supplier<Optional<CreatureAi>> creatureAi,
            @NotNull Supplier<String> aiUnavailableReason,
            @NotNull PlayerInputController inputs
    ) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.revision = Objects.requireNonNull(revision, "revision");
        this.reloads = Objects.requireNonNull(reloads, "reloads");
        this.rigs = Objects.requireNonNull(rigs, "rigs");
        this.simulation = Objects.requireNonNull(simulation, "simulation");
        this.drivers = Objects.requireNonNull(drivers, "drivers");
        this.simulationSettings = Objects.requireNonNull(simulationSettings, "simulationSettings");
        this.models = Objects.requireNonNull(models, "models");
        this.boundModels = Objects.requireNonNull(boundModels, "boundModels");
        this.boundDecorations = Objects.requireNonNull(boundDecorations, "boundDecorations");
        this.boundVehicles = Objects.requireNonNull(boundVehicles, "boundVehicles");
        this.creatureAi = Objects.requireNonNull(creatureAi, "creatureAi");
        this.aiUnavailableReason = Objects.requireNonNull(aiUnavailableReason, "aiUnavailableReason");
        this.inputs = Objects.requireNonNull(inputs, "inputs");
    }

    /**
     * {@inheritDoc}
     *
     * <p>The simulation settings are read <strong>once</strong>, here, and the cadence and the loop rate
     * both come out of that one read. Two reads could straddle a reload and report an emission rate from
     * one generation beside a simulation rate from the next, which is the mixed-generation read this
     * project avoids everywhere else — and in a report it would be the kind of inconsistency an operator
     * spends an afternoon on.
     */
    @Override
    public @NotNull StatusSnapshot capture() {
        final EmissionCadence cadence = this.simulationSettings.current().cadence();
        final Collection<Rig> loadedRigs = this.rigs.get();
        final Optional<CreatureAi> ai = this.creatureAi.get();

        return new StatusSnapshot(
                new StatusSnapshot.Renderer(
                        this.backend.isPresent(),
                        this.backend.rendererName() == null ? "" : this.backend.rendererName(),
                        this.backend.rejections()),
                this.reloads.describe(this.revision.getAsLong()),
                StatusSnapshot.Rigs.of(loadedRigs),
                new StatusSnapshot.Loop(
                        this.simulation.isRunning(),
                        this.simulation.simulationHz(),
                        this.simulation.periodNanos(),
                        this.simulation.scheduledPeriodNanos(),
                        this.simulation.size()),
                new StatusSnapshot.Loop(
                        this.drivers.isRunning(),
                        // The driver loop publishes its period rather than its rate, so the rate is
                        // derived. Rounded, because it is read by a human and the period is held in
                        // nanoseconds precisely so that a rate the files ask for is not quantised.
                        hzOf(this.drivers.periodNanos()),
                        this.drivers.periodNanos(),
                        this.drivers.scheduledPeriodNanos(),
                        this.drivers.size()),
                new StatusSnapshot.Emission(cadence.emissionHz(), cadence.interpolationTicks()),
                new StatusSnapshot.Ai(
                        ai.isPresent(),
                        this.aiUnavailableReason.get(),
                        ai.map(service -> service.supportedSpecies().size()).orElse(0)),
                new StatusSnapshot.Input(
                        this.inputs.capabilities(),
                        this.inputs.unavailableReason().orElse(""),
                        this.inputs.bindingCount(),
                        this.inputs.audit().size()),
                world());
    }

    /**
     * What this plugin has standing in the world.
     *
     * <p>The registry is copied once and walked once: {@code getModels()} already copies, by contract, so
     * asking it for a size and then for its contents would copy twice and could see a bind in between.
     */
    private @NotNull StatusSnapshot.World world() {
        final Collection<Model> registered = this.models.getModels();
        return new StatusSnapshot.World(
                this.boundModels.getAsInt(),
                this.boundDecorations.getAsInt(),
                this.boundVehicles.getAsInt(),
                registered.size(),
                StatusSnapshot.World.posedBones(registered));
    }

    /** {@code periodNanos} as whole hertz, floored at one so that a rate is always reportable. */
    private static int hzOf(long periodNanos) {
        if (periodNanos <= 0L) {
            return 1;
        }
        return Math.max(1, (int) Math.round((double) NANOS_PER_SECOND / (double) periodNanos));
    }
}
