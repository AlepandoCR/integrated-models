package pandastic.studio.models.internal;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.IntegratedModels;
import pandastic.studio.models.api.ai.CreatureAi;
import pandastic.studio.models.api.binding.ModelBinder;
import pandastic.studio.models.api.decoration.DecorationBinder;
import pandastic.studio.models.api.model.ModelManager;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.vehicle.VehicleBinder;
import pandastic.studio.models.internal.command.MenuCommand;
import pandastic.studio.models.internal.command.ReloadCommand;
import pandastic.studio.models.internal.command.ReloadReport;
import pandastic.studio.models.internal.config.ConfigReloadResult;
import pandastic.studio.models.internal.model.ModelManagerImpl;
import pandastic.studio.models.internal.menu.EntitySchedulerMenuPresenter;
import pandastic.studio.models.internal.menu.LibraryModelCatalogue;
import pandastic.studio.models.internal.menu.MenuService;
import pandastic.studio.models.internal.menu.MenuSessions;
import pandastic.studio.models.internal.menu.ModelLibrary;
import pandastic.studio.models.internal.menu.RiggedModelWardrobe;
import pandastic.studio.models.internal.menu.WardrobeRendering;
import pandastic.studio.models.internal.menu.PaperMenuSurface;
import pandastic.studio.models.internal.sim.SimulationClock;
import pandastic.studio.models.internal.render.RenderSettingsSource;
import pandastic.studio.models.internal.sim.SimulationLoop;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/**
 * The plugin entry point, and the only place in this project that knows about the server lifecycle.
 *
 * <p>Almost everything lives in {@link ModelRuntime}; this class exists to attach it to the lifecycle
 * and publish the API. The split matters because the <em>order</em> of what happens here is
 * load-bearing in ways that fail quietly, and keeping it to a handful of statements makes that order
 * reviewable.
 *
 * <h2>Why the runtime is built in onEnable rather than onLoad</h2>
 * Configuration reads the data folder, the binders need the server's entity schedulers, and the
 * simulation loop binds to the server's asynchronous scheduler. That scheduler is usable this early —
 * its executors are constructed before any plugin loads and do not depend on the server having ticked —
 * which is the whole reason this library never touches the Bukkit scheduler: that one only dispatches
 * once the server ticks, and plugins enable before the first tick. {@code onLoad} is left for work that
 * must precede world loading, and none of this does.
 *
 * <h2>Why shutdown is the first statement of onDisable</h2>
 * A plugin's classloader is closed <em>before</em> the platform cancels its tasks, and region- and
 * entity-scheduled repeating tasks are never cancelled for a plugin at all. So a sampler, a control
 * loop or a sensor still holding these classes fails on the first lazy class load after that close.
 * {@link ModelRuntime#shutdown()} is what cancels every one of them, in the one order that is safe, and
 * nothing may be added above that line.
 */
public class IntegratedModelsPlugin extends JavaPlugin implements IntegratedModels {

    private static @Nullable IntegratedModelsPlugin instance;

    private @Nullable ModelManager modelManager;
    private @Nullable ModelRuntime runtime;
    private @Nullable MenuService menu;

    @Override
    public void onLoad() {
        instance = this;
        this.modelManager = new ModelManagerImpl(this);
    }

    @Override
    public void onEnable() {
        this.runtime = ModelRuntime.create(this);
        this.runtime.start();
        this.menu = new MenuService(
                runtime().configs()::menu,
                new PaperMenuSurface(getComponentLogger()),
                new MenuSessions(runtime().configs()::menu, SimulationClock.SYSTEM),
                new LibraryModelCatalogue(ModelLibrary.renderer()),
                new RiggedModelWardrobe(runtime().models(), runtime()::rigForModel,
                        WardrobeRendering.trackers(),
                        (message, thrown) -> getComponentLogger()
                                .error(Component.text(message, NamedTextColor.RED), thrown)),
                new EntitySchedulerMenuPresenter(this));

        // Published so a consumer can reach the API without naming this class. getInstance() still
        // works, but a service lookup is what survives this plugin being reloaded or replaced.
        getServer().getServicesManager()
                .register(IntegratedModels.class, this, this, ServicePriority.Normal);

        registerCommands();
    }

    @Override
    public void onDisable() {
        // FIRST. See the class documentation — nothing may go above this.
        if (this.runtime != null) {
            this.runtime.shutdown();
            this.runtime = null;
        }
        if (this.menu != null) {
            this.menu.shutdown();
            this.menu = null;
        }
        getServer().getServicesManager().unregisterAll(this);
    }

    @Override
    public @NotNull ModelManager getModelManager() {
        return Objects.requireNonNull(this.modelManager, "Model manager is not initialized yet");
    }

    @Override
    public @NotNull ModelBinder getModelBinder() {
        return runtime().models();
    }

    @Override
    public @NotNull DecorationBinder getDecorationBinder() {
        return runtime().decorations();
    }

    @Override
    public @NotNull VehicleBinder getVehicleBinder() {
        return runtime().vehicles();
    }

    @Override
    public @NotNull Optional<CreatureAi> getCreatureAi() {
        return runtime().creatureAi();
    }

    @Override
    public @NotNull Collection<Rig> getRigs() {
        return runtime().rigs();
    }

    @Override
    public @NotNull Optional<Rig> getRig(@NotNull String name) {
        return runtime().rig(name);
    }

    /**
     * The loop every simulation is driven from.
     *
     * <p>Not part of the API: a {@link SimulationLoop} is how this plugin schedules its own work, not
     * something a consumer should reach into. A consumer that changed its rate would change it for every
     * other plugin's models at the same time.
     */
    public @NotNull SimulationLoop getSimulationLoop() {
        return runtime().loop();
    }

    /** Where the render layer reads its configured settings. Not part of the API, for the same reason. */
    public @NotNull RenderSettingsSource getRenderSettings() {
        return runtime().renderSettings();
    }

    /**
     * Re-reads every configuration file and every rig, and reports what happened.
     *
     * <p>Refused wholesale if any file fails to validate, leaving the previous values live: a
     * half-applied reload would put the server in a state no file describes. On success every layer that
     * owns a repeating task is told, because a task's rate belongs to the platform and cannot be changed
     * from inside the task.
     *
     * <p>Not part of the API: a reload is an operator action, and a consumer able to trigger one could
     * re-dispatch every other plugin's samplers.
     */
    public @NotNull ConfigReloadResult reloadConfiguration() {
        return runtime().reload();
    }

    /**
     * Registers the operator-facing commands.
     *
     * <h2>Why not {@code plugin.yml}</h2>
     * A command declared there is static: the plugin gets a {@code PluginCommand} it can only attach an
     * executor to, with no permission predicate the client's command tree can see and no way to take
     * part in anything the lifecycle offers. The lifecycle path below is the modern one, and the only
     * one that works for a command whose availability is a predicate — {@code plugin.yml} consequently
     * has no {@code commands:} block and must not grow one, or the same label would be claimed twice.
     *
     * <h2>What is wired into what</h2>
     * The commands own none of this behaviour. The reload command is handed, in order: where the reply
     * text comes from — the live configuration, read once per invocation; what a reload <em>is</em>,
     * which is {@link #reloadConfiguration()} plus the rig count it produced; somewhere to run that
     * which is not a region thread; and somewhere for a failure's stack trace to go. The menu command
     * receives the same live command settings for its non-player response and the already assembled menu
     * service for player navigation.
     *
     * <p>The two halves of {@link ReloadReport} are read in that order on purpose: the rig count is
     * meaningful only after the reload that re-read the rigs, and it is taken on the same thread
     * immediately afterwards so that nothing can have changed it in between.
     */
    private void registerCommands() {
        final ReloadCommand reload = new ReloadCommand(
                runtime().configs()::command,
                () -> new ReloadReport(reloadConfiguration(), getRigs().size()),
                ReloadCommand.asyncOn(this),
                (message, thrown) -> getComponentLogger()
                        .error(Component.text(message, NamedTextColor.RED), thrown));

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                {
                    event.registrar().register(ReloadCommand.NAME, ReloadCommand.DESCRIPTION,
                            ReloadCommand.ALIASES, reload);
                    event.registrar().register(MenuCommand.NAME, MenuCommand.DESCRIPTION,
                            MenuCommand.ALIASES, new MenuCommand(
                                    Objects.requireNonNull(this.menu, "Menu service is not initialized"),
                                    runtime().configs()::command));
                });
    }

    private @NotNull ModelRuntime runtime() {
        return Objects.requireNonNull(this.runtime, "Integrated Models is not enabled");
    }

    public static @NotNull IntegratedModelsPlugin getInstance() {
        return Objects.requireNonNull(instance, "Integrated models instance is not initialized yet");
    }
}
