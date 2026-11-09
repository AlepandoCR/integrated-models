package pandastic.studio.models.internal.config;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.core.service.api.config.GeneratedConfig;
import pandastic.studio.core.service.api.config.io.ConfigFactory;
import pandastic.studio.core.service.api.config.io.ConfigFolder;
import pandastic.studio.core.service.api.config.io.ConfigLoader;
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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the live {@link ConfigSnapshot} and the only way to replace it.
 *
 * <h2>Snapshot publishing, not mutation</h2>
 * The simulation runs on an asynchronous loop at tens of hertz and reads these values on every step, so
 * nothing here is ever edited in place. A reload builds a complete, fresh, immutable snapshot and
 * publishes it through one {@link AtomicReference}. A reader does a single volatile read, holds the
 * result for the whole step, and is therefore never exposed to a value changing underneath it — there
 * is no lock on the read path and no window in which two values disagree.
 *
 * <h2>All or nothing</h2>
 * {@link #reload()} re-reads every file, builds the value objects, validates them, and swaps the
 * reference only once every file has parsed and every value has passed. A failure at any point leaves
 * the previous snapshot live and comes back as a list of diagnostics naming the file and the key; see
 * {@link ConfigReloadResult}.
 *
 * <h2>Threading</h2>
 * {@link #snapshot()} and every accessor beside it are safe from any thread, including a region thread
 * and the simulation thread. {@link #reload()} is safe from any thread too and does its I/O on the
 * common {@link java.util.concurrent.ForkJoinPool}, so it never blocks the caller and never blocks the
 * simulation. Reloads are serialised against each other: a second reload queues behind the first rather
 * than racing it, which is what keeps the revision counter monotonic and stops two operators from
 * publishing out of order.
 *
 * <h2>Why not {@link ConfigLoader}</h2>
 * The batch loader in {@code pandastic-core} is the natural-looking choice and it is the wrong one
 * here, for two reasons stated in its own documentation. It contains each task's failure and
 * <em>always</em> completes normally, so a run in which two files were unreadable looks exactly like a
 * run in which none were — which is precisely the half-applied reload this class exists to refuse. And
 * it needs a {@link Plugin} for its data folder and its logger, which would put a running server
 * between this layer and any test of it. {@link ConfigFactory} is the right granularity: it loads one
 * file, it propagates what went wrong, and it needs nothing but a {@link File}.
 *
 * <h2>The narrow seam</h2>
 * The accessors below return api value types, so the solver, the rig loader and the renderer can be
 * written against {@code java.util.function.Supplier<PhysicsTuning>} and friends — satisfied by
 * {@code configService::physics} — rather than against this class. Nothing outside this package should
 * need to name {@code ConfigService} except the code that wires the plugin together.
 */
public final class ConfigService {

    /** Directory the configuration files live in. */
    private final @NotNull File directory;

    /** The published snapshot. One reference, swapped whole; never mutated. */
    private final @NotNull AtomicReference<ConfigSnapshot> live;

    /**
     * Tail of the reload chain, so reloads run one after another.
     *
     * <p>Guarded by {@link #reloadLock}, which is only ever held long enough to attach a continuation
     * — never across the file reads themselves. A caller of {@link #reload()} therefore cannot be made
     * to wait on another caller's disk access.
     */
    private @NotNull CompletableFuture<?> tail = CompletableFuture.completedFuture(null);

    private final @NotNull Object reloadLock = new Object();

    private ConfigService(@NotNull File directory, @NotNull ConfigSnapshot initial) {
        this.directory = directory;
        this.live = new AtomicReference<>(initial);
    }

    /**
     * A service reading every file from {@code plugin}'s data folder.
     *
     * <p>Starts out holding the declared defaults, so it is usable before {@link #reload()} has ever
     * run and stays usable if that first reload is refused. Nothing is read from disk here.
     *
     * @param plugin the plugin whose data folder holds the files
     * @return the service
     */
    @Contract(value = "_ -> new", pure = true)
    public static @NotNull ConfigService create(@NotNull Plugin plugin) {
        return inDirectory(ConfigFolder.root().resolve(plugin));
    }

    /**
     * A service reading every file from an explicit directory.
     *
     * <p>This is also the test seam: everything about loading, validating, refusing and publishing can
     * be exercised against a temporary directory with no server in sight.
     *
     * @param directory the directory holding the files; it does not have to exist yet
     * @return the service
     */
    @Contract(value = "_ -> new", pure = true)
    public static @NotNull ConfigService inDirectory(@NotNull File directory) {
        return new ConfigService(directory, ConfigSnapshot.defaults());
    }

    /**
     * The values in effect.
     *
     * <p>Read this <strong>once</strong> per simulation step and hold the result. Reading it twice
     * within one step lets a reload land between the two reads, which gives one step two different sets
     * of coefficients — the same failure mode as reading the driver sample twice.
     *
     * @return the live snapshot, never {@code null}
     */
    public @NotNull ConfigSnapshot snapshot() {
        return this.live.get();
    }

    /** The solver calibration in effect. Shaped to satisfy {@code Supplier<PhysicsTuning>}. */
    public @NotNull PhysicsTuning physics() {
        return snapshot().physics();
    }

    /** The physics profile bounds in effect. Shaped to satisfy {@code Supplier<ProfileBounds>}. */
    public @NotNull ProfileBounds profileBounds() {
        return snapshot().profileBounds();
    }

    /**
     * The coefficients a rig file inherits in effect. Shaped to satisfy
     * {@code Supplier<ProfileDefaults>}.
     *
     * <p>Already checked against {@link #profileBounds()} by the validator, so a rig loader can pair the
     * two straight off this service without re-deriving the relationship between them.
     */
    public @NotNull ProfileDefaults profileDefaults() {
        return snapshot().profileDefaults();
    }

    /** The rig bounds in effect. Shaped to satisfy {@code Supplier<RigBounds>}. */
    public @NotNull RigBounds rigBounds() {
        return snapshot().rigBounds();
    }

    /** The walk cycle calibration in effect. Shaped to satisfy {@code Supplier<GaitTuning>}. */
    public @NotNull GaitTuning gait() {
        return snapshot().gait();
    }

    /** The locomotion binding bounds in effect. Shaped to satisfy {@code Supplier<BindingBounds>}. */
    public @NotNull BindingBounds bindingBounds() {
        return snapshot().bindingBounds();
    }

    /** The interpolation window bounds in effect. Shaped to satisfy {@code Supplier<CadenceBounds>}. */
    public @NotNull CadenceBounds cadenceBounds() {
        return snapshot().cadenceBounds();
    }

    /** The driver movement bounds in effect. Shaped to satisfy {@code Supplier<DriverBounds>}. */
    public @NotNull DriverBounds driverBounds() {
        return snapshot().driverBounds();
    }

    /** The IK degenerate-target guard in effect. Shaped to satisfy {@code Supplier<IkTolerance>}. */
    public @NotNull IkTolerance ikTolerance() {
        return snapshot().ikTolerance();
    }

    /** The AI plan bounds in effect. Shaped to satisfy {@code Supplier<AiBounds>}. */
    public @NotNull AiBounds aiBounds() {
        return snapshot().aiBounds();
    }

    /** The vehicle profile bounds in effect. Shaped to satisfy {@code Supplier<VehicleBounds>}. */
    public @NotNull VehicleBounds vehicleBounds() {
        return snapshot().vehicleBounds();
    }

    /**
     * The menu text and bounds in effect. Shaped to satisfy {@code MenuSettingsSource}.
     *
     * <p>Written in full because the menu layer's value type and the declaration of {@code menu.yml} in
     * this package share a simple name, which is the deliberate shadow this project uses for every file
     * whose consumer-side value has no api counterpart.
     */
    public @NotNull pandastic.studio.models.internal.menu.MenuSettings menu() {
        return snapshot().menu();
    }

    /**
     * What governs the culling decision. Shaped to satisfy {@code ViewSettingsSource}.
     *
     * <p>Written in full for the reason {@link #menu()} is: the view layer's value type and the
     * declaration of {@code view.yml} in this package share a simple name on purpose.
     *
     * <p>No allocation, because the snapshot already holds the assembled value — which matters here in a
     * way it does not for the render seam, since a culling pass asks one question per pass but the
     * answer is read on the pass's hot path.
     */
    public @NotNull pandastic.studio.models.internal.view.ViewSettings view() {
        return snapshot().view();
    }

    /**
     * What the binding layer reads when it dispatches an anchor sampler. Shaped to satisfy
     * {@code BindingSettingsSource}.
     *
     * <p>Written in full for the same shadow. Also allocation-free, which is what lets the binding
     * layer's source be read on a region thread without putting an object on the per-model sampling
     * path.
     */
    public @NotNull pandastic.studio.models.internal.binding.BindingSettings binding() {
        return snapshot().binding();
    }

    /**
     * Every line the reload command says. Shaped to satisfy {@code CommandSettingsSource}.
     *
     * <p>Written in full for the same shadow. Allocation-free, because the snapshot holds the assembled
     * value — which matters less here than anywhere else, since the command reads it once per
     * invocation, but it is also what makes the value's identity stable for as long as the
     * configuration behind it is.
     */
    public @NotNull pandastic.studio.models.internal.command.CommandSettings command() {
        return snapshot().command();
    }

    /**
     * Re-reads every file and publishes the result, or refuses it.
     *
     * <p>Safe from any thread. The disk work happens on the common
     * {@link java.util.concurrent.ForkJoinPool}; this method only attaches a continuation and returns.
     * Missing files are created with the declared defaults and their comments, which is what makes a
     * fresh installation ship a documented set of files rather than a directory of empty ones.
     *
     * <p>The returned future always completes normally: a refused reload is an outcome, not an error.
     * It completes exceptionally only if something outside this layer's contract fails, which is why
     * {@link #reloadNow()} exists for callers that would rather deal with that synchronously.
     *
     * @return a future completing with what the reload did
     */
    public @NotNull CompletableFuture<ConfigReloadResult> reload() {
        synchronized (this.reloadLock) {
            // handle() first, so a previous reload that somehow failed does not poison this one: the
            // chain exists to order reloads, not to propagate their outcomes. The lock is held only
            // for these three statements, all of which just register a continuation.
            final CompletableFuture<ConfigReloadResult> next = this.tail
                    .handle((ignoredResult, ignoredFailure) -> null)
                    .thenApplyAsync(ignored -> loadAndPublish());
            this.tail = next;
            return next;
        }
    }

    /**
     * Re-reads every file and waits for the answer.
     *
     * <p>For start-up, where blocking is what you want — the plugin should not enable on defaults while
     * a load it could have waited for completes behind it — and for tests, which want the answer rather
     * than a future. Never call this from a region thread once the server is running.
     *
     * <p>Implemented on top of {@link #reload()} rather than beside it, so that a start-up load and a
     * reload racing it still go through the same queue and still produce consecutive revisions.
     *
     * @return what the reload did
     * @throws java.util.concurrent.CompletionException if the reload failed in a way this layer does
     *                                                  not model, which a refused reload is not
     */
    public @NotNull ConfigReloadResult reloadNow() {
        return reload().join();
    }

    /**
     * Reads, validates and - only if everything passed - publishes.
     *
     * <p>The revision is derived from the snapshot being replaced rather than from a counter of its
     * own, which is safe precisely because reloads are serialised: there is never a second publisher
     * reading the same previous revision.
     *
     * @return what the reload did
     */
    private @NotNull ConfigReloadResult loadAndPublish() {
        final ConfigSnapshot previous = this.live.get();
        final ConfigMapper.Result mapping = read();
        if (!mapping.isSuccess()) {
            return ConfigReloadResult.refused(previous, mapping.diagnostics());
        }

        final ConfigSnapshot fresh = mapping.snapshotOrThrow().withRevision(previous.revision() + 1L);
        this.live.set(fresh);
        return ConfigReloadResult.applied(previous, fresh);
    }

    /**
     * Loads every file and maps them, collecting every failure.
     *
     * <p>Every one is attempted even after the first one fails, because an operator who broke two files
     * would rather hear about two. A fresh generated instance is created per file per reload on purpose:
     * it starts at the declared defaults, so a key the operator deleted goes back to its default instead
     * of keeping whatever the previous reload happened to read. The file is the truth, including about
     * what it no longer says.
     *
     * @return the mapping result, successful only when every file loaded and every value passed
     */
    private @NotNull ConfigMapper.Result read() {
        final List<ConfigDiagnostic> failures = new ArrayList<>();
        final PhysicsConfig physics = open(ConfigFile.PHYSICS, PhysicsConfig.class, failures);
        final GaitConfig gait = open(ConfigFile.GAIT, GaitConfig.class, failures);
        final RenderConfig render = open(ConfigFile.RENDER, RenderConfig.class, failures);
        final SimulationConfig simulation = open(ConfigFile.SIMULATION, SimulationConfig.class, failures);
        final DiagnosticsConfig diagnostics =
                open(ConfigFile.DIAGNOSTICS, DiagnosticsConfig.class, failures);
        final AiConfig ai = open(ConfigFile.AI, AiConfig.class, failures);
        final VehicleConfig vehicle = open(ConfigFile.VEHICLE, VehicleConfig.class, failures);
        final MenuConfig menu = open(ConfigFile.MENU, MenuConfig.class, failures);
        final ViewConfig view = open(ConfigFile.VIEW, ViewConfig.class, failures);
        final BindingConfig binding = open(ConfigFile.BINDING, BindingConfig.class, failures);
        final CommandConfig command = open(ConfigFile.COMMAND, CommandConfig.class, failures);

        if (physics == null || gait == null || render == null || simulation == null
                || diagnostics == null || ai == null || vehicle == null || menu == null
                || view == null || binding == null || command == null) {
            return new ConfigMapper.Result(null, failures);
        }
        return ConfigMapper.map(physics, gait, render, simulation, diagnostics, ai, vehicle, menu,
                view, binding, command);
    }

    /**
     * Loads or creates one file, turning any failure into a diagnostic naming it.
     *
     * <p>Blocking, and deliberately so: the caller is already on a pool thread, and
     * {@link ConfigFactory#loadOrCreateAsync(File, Class)} would only hop to the same pool while making
     * the all-or-nothing join harder to read.
     *
     * @param file     which file this is, for the diagnostic
     * @param type     the generated configuration class
     * @param failures accumulator of whole-file failures
     * @param <GeneratedConfigType> the generated configuration type
     * @return the loaded configuration, or {@code null} when it could not be read
     */
    private <GeneratedConfigType extends GeneratedConfig> @Nullable GeneratedConfigType open(
            @NotNull ConfigFile file,
            @NotNull Class<GeneratedConfigType> type,
            @NotNull List<ConfigDiagnostic> failures
    ) {
        try {
            return ConfigFactory.loadOrCreate(new File(this.directory, file.fileName()), type);
        } catch (final RuntimeException exception) {
            // RuntimeException rather than IllegalStateException: the factory documents that one, but
            // broken YAML surfaces through Bukkit's parser and a reload must not die on a shape of
            // failure this layer failed to anticipate. The previous snapshot stays live either way.
            failures.add(ConfigDiagnostic.ofFile(file, "could not be read: " + describe(exception)));
            return null;
        }
    }

    /**
     * The most specific message an exception chain carries.
     *
     * <p>The outer message from the configuration factory is always "Unable to read &lt;file&gt;", which
     * the diagnostic already says; the cause is where the parser explains what it choked on.
     *
     * @param failure the exception to describe
     * @return a non-blank human readable description
     */
    private static @NotNull String describe(@NotNull Throwable failure) {
        Throwable deepest = failure;
        while (deepest.getCause() != null) {
            deepest = deepest.getCause();
        }
        final String message = deepest.getMessage();
        return message == null || message.isBlank() ? deepest.toString() : message;
    }
}
