package pandastic.studio.models.internal;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.ai.AiActivity;
import pandastic.studio.models.api.ai.AiActivityPlan;
import pandastic.studio.models.api.ai.AiBehaviour;
import pandastic.studio.models.api.ai.AiBounds;
import pandastic.studio.models.api.ai.AiContext;
import pandastic.studio.models.api.ai.AiPlan;
import pandastic.studio.models.api.rig.PhysicsKind;
import pandastic.studio.models.api.rig.PhysicsProfile;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.vehicle.VehicleProfile;
import pandastic.studio.models.internal.ai.AiRuntime;
import pandastic.studio.models.internal.ai.AiSettings;
import pandastic.studio.models.internal.ai.AiSettingsSource;
import pandastic.studio.models.internal.binding.AnchorFactory;
import pandastic.studio.models.internal.binding.LocomotionStageSource;
import pandastic.studio.models.internal.binding.RigBindValidator;
import pandastic.studio.models.internal.config.ConfigFile;
import pandastic.studio.models.internal.config.ConfigService;
import pandastic.studio.models.internal.config.ConfigSnapshot;
import pandastic.studio.models.internal.decoration.DecorationAnchorFactory;
import pandastic.studio.models.internal.nms.NmsBridgeResolution;
import pandastic.studio.models.internal.render.RenderSettingsSource;
import pandastic.studio.models.internal.sim.SimulationClock;
import pandastic.studio.models.internal.sim.SimulationLogger;
import pandastic.studio.models.internal.vehicle.VehicleHostFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The fake platform the wiring tests assemble a {@link ModelRuntime} over.
 *
 * <h2>What this proves, and why it is the only way to prove it</h2>
 * Everything the runtime does that can be got wrong silently is an <em>order</em>: the order the object
 * graph is built in, the order it is torn down in, and which layers a reload reaches. None of those is
 * visible from any one layer's own tests — each of those suites holds one collaborator and a fake for
 * the rest — and none of them is reachable from a running server either, because the failure is a task
 * nobody cancelled rather than an exception.
 *
 * <p>So the subject here is the seam list on
 * {@link ModelRuntime#ModelRuntime(ComponentLogger, ConfigService, Path, RenderSettingsSource,
 * pandastic.studio.models.internal.sim.SimulationScheduler, SimulationLogger, SimulationClock,
 * AnchorFactory, DecorationAnchorFactory, VehicleHostFactory, RigBindValidator, LocomotionStageSource,
 * AiRuntime)}. Every parameter of it is something only the platform can supply, and every one of them
 * has a recording double here, which makes the whole graph — including both off-tick loops, all three
 * binders and the AI layer — assertable with no {@code Bukkit.getServer()} anywhere.
 *
 * <h2>Configuration is real</h2>
 * {@link ConfigService#inDirectory} against a temporary directory, not a fixed source. That is
 * deliberate and it is what makes the reload tests mean anything: a fixed source cannot be reloaded, so
 * a suite built on one would pass for a runtime that never re-read a file — which is precisely the
 * defect the settings-identity test exists to catch, one layer up.
 */
final class RuntimeFixtures {

    /** The bounds the AI plans here are validated against. Small and odd rather than the shipped ones. */
    static final @NotNull AiBounds AI_BOUNDS = new AiBounds(3, 2, 7, 2, 2L, 40L, 11.0D, 500L, 8);

    private RuntimeFixtures() {
    }

    // ---------------------------------------------------------------- the graph

    /**
     * A runtime over recording doubles, with its configuration read from {@code directory}.
     *
     * <p>Built the way {@link ModelRuntime#create} builds the real one, in the same order and through
     * the same seams — the render source is read out of the configuration and then handed to the three
     * factories that are built from it, which is the one construction constraint the production factory
     * exists to satisfy.
     *
     * @param directory where the configuration files live; created and loaded if they do not exist yet
     * @param journal   where every double records what it was asked to do
     * @return the assembled runtime, with nothing started
     */
    static @NotNull Harness harness(@NotNull Path directory, @NotNull Journal journal) {
        final ConfigService configs = ConfigService.inDirectory(directory.toFile());
        configs.reloadNow();

        // The production factory, not a lambda of our own: it is what makes the render seam answer one
        // object per snapshot, and a fixture that built its own would be testing the fixture.
        final RenderSettingsSource renderSettings = ModelRuntime.renderSource(configs);

        final RecordingScheduler scheduler = new RecordingScheduler(journal);
        final RecordingBrains brains = new RecordingBrains(journal);
        final List<RecordingEntityAnchor> anchors = new ArrayList<>();
        final List<RecordingWorldAnchor> worlds = new ArrayList<>();
        final List<RecordingVehicleHost> hosts = new ArrayList<>();

        final AiRuntime ai = AiRuntime.create(
                plugin(),
                new NmsBridgeResolution(brains.bridge(), "test", List.of()),
                AiSettingsSource.fixed(new AiSettings(AI_BOUNDS, 0L, 4)),
                (message, failure) -> journal.record("ai-fault"),
                brains::hostFor);

        final ModelRuntime runtime = new ModelRuntime(
                logger(),
                configs,
                directory,
                renderSettings,
                scheduler,
                (message, thrown) -> journal.record("sim-error"),
                SimulationClock.SYSTEM,
                entity -> {
                    // Named by creation order, which is what lets a teardown test say *which* model was
                    // released: the vehicle's visual is bound through this same factory, one call after
                    // the standalone model the test bound for itself.
                    final RecordingEntityAnchor anchor =
                            new RecordingEntityAnchor(journal, "model" + anchors.size());
                    anchors.add(anchor);
                    return anchor;
                },
                world -> {
                    final RecordingWorldAnchor anchor =
                            new RecordingWorldAnchor(journal, "decoration" + worlds.size());
                    worlds.add(anchor);
                    return anchor;
                },
                root -> {
                    final RecordingVehicleHost host =
                            new RecordingVehicleHost(journal, "vehicle" + hosts.size());
                    hosts.add(host);
                    return host;
                },
                // The offline check, against a bone list rather than the renderer's registry. Every bone
                // of rig() is in it, so a bind fails here only if the runtime wired the validator wrong.
                RigBindValidator.over(modelBoneNames(), renderSettings),
                LocomotionStageSource.NONE,
                ai);

        return new Harness(runtime, configs, scheduler, brains, anchors, worlds, hosts);
    }

    /**
     * A runtime and the doubles underneath it.
     *
     * <p>A record rather than fields on the test so that two runtimes can exist in one method, which is
     * what the shutdown-does-not-touch-anything-else cases need.
     *
     * @param runtime   the subject
     * @param configs   the live configuration, for writing a file and reloading it
     * @param scheduler the one scheduler both off-tick loops were dispatched through
     * @param brains    the AI seam, for asserting an installation was uninstalled
     * @param anchors   every entity anchor handed out, in creation order
     * @param worlds    every world anchor handed out, in creation order
     * @param hosts     every vehicle host handed out, in creation order
     */
    record Harness(
            @NotNull ModelRuntime runtime,
            @NotNull ConfigService configs,
            @NotNull RecordingScheduler scheduler,
            @NotNull RecordingBrains brains,
            @NotNull List<RecordingEntityAnchor> anchors,
            @NotNull List<RecordingWorldAnchor> worlds,
            @NotNull List<RecordingVehicleHost> hosts
    ) {
    }

    // ---------------------------------------------------------------- the rig

    /** Two bones: a root and one pendulum hanging off it. */
    static @NotNull Rig rig() {
        final ConfigSnapshot defaults = ConfigSnapshot.defaults();
        return Rig.builder("test-rig", "test-model", defaults.rigBounds(), defaults.profileBounds())
                .root("body")
                .bone("hair", "body", PhysicsProfile
                        .builder(PhysicsKind.PENDULUM, defaults.profileBounds(),
                                defaults.profileDefaults())
                        .build())
                .build();
    }

    /** The bone names a model matching {@link #rig()} would report. */
    static @NotNull List<String> modelBoneNames() {
        return List.of("body", "hair", "cape");
    }

    /**
     * A vehicle profile the shipped {@code vehicle.yml} bounds admit.
     *
     * <p>Every figure is below the declared default ceiling, so the profile is accepted by the live
     * bounds the runtime reads rather than by bounds a fixture chose — which is what makes
     * "the vehicle binder was wired to the configuration" part of what these tests prove.
     */
    static @NotNull VehicleProfile vehicleProfile() {
        return VehicleProfile.builder(ConfigSnapshot.defaults().vehicleBounds())
                .forwardSpeed(0.4F)
                .reverseSpeed(0.2F)
                .acceleration(0.1F)
                .braking(0.2F)
                .friction(0.5F)
                .turnRate(8F)
                .build();
    }

    // ---------------------------------------------------------------- platform stubs

    /**
     * A plugin that answers nothing.
     *
     * <p>The only thing that wants one here is {@link AiRuntime#create}, whose own documentation says
     * the parameter is unused by the layer and required so that its two factories cannot diverge in
     * what they demand. A proxy is therefore not a shortcut — there is nothing to implement.
     */
    static @NotNull Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(
                RuntimeFixtures.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakePlugin";
                    default -> null;
                });
    }

    /**
     * A logger that discards everything.
     *
     * <p>Not {@code ComponentLogger.logger(name)}: that resolves an Adventure provider over SLF4J,
     * whose binding is the server's. A test source set has no binding, so the real factory would at
     * best write every lifecycle line to stderr and at worst depend on which jars happened to be on the
     * classpath. The runtime's logging is one-line reporting with no decisions in it, so there is
     * nothing here worth asserting — what matters is that building one needs no server.
     */
    static @NotNull ComponentLogger logger() {
        return (ComponentLogger) Proxy.newProxyInstance(
                RuntimeFixtures.class.getClassLoader(),
                new Class<?>[]{ComponentLogger.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString", "getName" -> "DiscardingComponentLogger";
                    // Every is*Enabled on the SLF4J surface. A proxy returning null for a primitive
                    // return type throws, so the booleans have to be answered explicitly.
                    default -> method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                });
    }

    /** An entity of no particular kind, which is what a legitimate model owner and vehicle root is. */
    static @NotNull Entity entity() {
        return proxy(Entity.class, "FakeEntity");
    }

    /** A creature to install an AI plan on. */
    static @NotNull Mob mob() {
        return proxy(Mob.class, "FakeMob");
    }

    private static <PlatformType> @NotNull PlatformType proxy(
            @NotNull Class<PlatformType> type,
            @NotNull String name
    ) {
        return type.cast(Proxy.newProxyInstance(
                RuntimeFixtures.class.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, arguments) -> switch (method.getName()) {
                    // Proxy routes these to the handler, so reference semantics have to be restated.
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> name;
                    default -> null;
                }));
    }

    // ---------------------------------------------------------------- the AI plan

    /** The smallest plan that builds: one activity holding one behaviour that does nothing. */
    static @NotNull AiPlan plan() {
        return AiPlan.builder("test-plan", AI_BOUNDS)
                .activity(AiActivityPlan.builder(AiActivity.IDLE)
                        .behaviour(0, new AiBehaviour() {
                            @Override
                            public @NotNull String name() {
                                return "idle-body";
                            }

                            @Override
                            public boolean canStart(@NotNull AiContext context) {
                                return true;
                            }

                            @Override
                            public void tick(@NotNull AiContext context, long ticksRunning) {
                                // Intentionally empty: this plan exists to be installed, not to run.
                            }
                        })
                        .build())
                .defaultActivity(AiActivity.IDLE)
                .build();
    }

    // ---------------------------------------------------------------- configuration

    /**
     * Writes {@code lines} as the entire contents of one configuration file.
     *
     * <p>Partial on purpose: a generated configuration keeps its declared default for every key the
     * file does not carry, so a fixture only names the keys under test. That is the behaviour the reload
     * path depends on rather than a shortcut.
     *
     * @param directory the directory the service was pointed at
     * @param file      which file to write
     * @param lines     the YAML lines, each a complete {@code key: value} entry
     */
    static void write(@NotNull Path directory, @NotNull ConfigFile file, String @NotNull ... lines) {
        final StringBuilder content = new StringBuilder();
        for (final String line : lines) {
            content.append(line).append(System.lineSeparator());
        }
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(file.fileName()), content.toString());
        } catch (final IOException failed) {
            throw new UncheckedIOException("Could not write the fixture " + file.fileName(), failed);
        }
    }

    // ---------------------------------------------------------------- the journal

    /**
     * What happened, in order, with the state of both off-tick loops observed at every entry.
     *
     * <h2>Why the entries carry context</h2>
     * The shutdown sequence's first claim is that <em>nothing is still stepping</em> by the time
     * anything in the world is torn down, and neither loop can record anything: stopping one is
     * cancelling a task, and the cancellation is the double's event rather than the loop's. Observing
     * both loops' running state alongside every subsequent entry turns that claim from an assertion
     * about an invisible ordering into one about recorded text — a teardown entry that reads
     * {@code @loop=stopped,drivers=stopped} could not have been written while either was live.
     *
     * <p>The same trick {@code BindingFixtures.Journal} uses for the simulation loop's membership, for
     * the same reason.
     */
    static final class Journal {

        private final @NotNull List<String> annotated = new ArrayList<>();
        private final @NotNull List<String> bare = new ArrayList<>();

        private @Nullable ModelRuntime observed;

        /** Observes {@code runtime}'s two loops alongside every subsequent entry. */
        @NotNull Journal observing(@NotNull ModelRuntime runtime) {
            this.observed = runtime;
            return this;
        }

        void record(@NotNull String event) {
            this.bare.add(event);
            this.annotated.add(this.observed == null ? event : event + context());
        }

        /** Every event, in order, without the loop state. */
        @NotNull List<String> events() {
            return List.copyOf(this.bare);
        }

        /** Every event, in order, each carrying the loop state at the moment it was written. */
        @NotNull List<String> annotated() {
            return List.copyOf(this.annotated);
        }

        void clear() {
            this.bare.clear();
            this.annotated.clear();
        }

        private @NotNull String context() {
            final ModelRuntime runtime = this.observed;
            if (runtime == null) {
                return "";
            }
            return "@loop=" + (runtime.loop().isRunning() ? "running" : "stopped")
                    + ",drivers=" + (runtime.drivers().isRunning() ? "running" : "stopped");
        }
    }

    /** A fresh identity, for a double that needs one and has nothing to derive it from. */
    static @NotNull UUID id() {
        return UUID.randomUUID();
    }
}
