package pandastic.studio.models.internal.model;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.model.EntityModel;
import pandastic.studio.models.api.model.component.ModelComponent;
import pandastic.studio.models.api.model.component.decoration.ModelDecorator;
import pandastic.studio.models.api.model.component.scheduled.ModelScheduledComponent;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.IntegratedModelsPlugin;
import pandastic.studio.models.internal.model.component.decoration.ModelDecoratorImpl;
import pandastic.studio.models.internal.spi.ModelHandle;
import pandastic.studio.models.internal.spi.ModelRenderBackends;
import pandastic.studio.models.internal.util.namespacedkey.IntegratedModelsNamespacedKeys;
import pandastic.studio.models.internal.util.schedule.ScheduledTaskTickerControllerImpl;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The component API's model: a renderer model bound to an entity this class spawns or attaches to.
 *
 * <p>An {@link EntityModel}, and therefore one of the four kinds of
 * {@link pandastic.studio.models.api.model.Model}. It implemented a model interface of its own until the
 * four were given a common root; what it gained in the merge is {@link #getRig()}, a boolean return on
 * {@link #show(Player)} and {@link #hide(Player)}, and — through {@code EntityModel}'s defaults — the
 * flat appearance surface routed into {@link #getDecorator()}.
 *
 * <p>Still abstract, still with no concrete subclass in this project: a consumer is expected to extend
 * it. The rig pipeline's models ({@code RiggedModelImpl}, {@code DecorationImpl}, {@code VehicleImpl})
 * are siblings of this class rather than subclasses, because none of them spawns its own entity or owns
 * an entity-scheduler task per model — they are stepped by one shared off-tick loop.
 */
public abstract class ModelImpl<BaseEntityType extends Entity> extends ScheduledTaskTickerControllerImpl implements EntityModel<BaseEntityType> {

    private final @NotNull Class<BaseEntityType> entityTypeClass;

    private final @NotNull UUID uniqueIdentifier;

    /**
     * The renderer's authored model with none of our bones driven.
     *
     * <p>Built once here rather than per call because {@link pandastic.studio.models.api.model.Model}'s
     * contract is that a rig is fixed for a model's life, and because the rig pipeline compares settings
     * and definitions by identity — a fresh instance per call is the shape that has already cost this
     * project one silent bug.
     */
    private final @NotNull Rig rig;

    private final @NotNull List<ModelComponent<BaseEntityType>> components;

    private final @NotNull IntegratedModelsPlugin plugin;

    private final @NotNull ModelDecoratorImpl<BaseEntityType> modelDecorator;

    private final @NotNull String name;

    /**
     * The renderer's handle on this model, or {@code null} while nothing is bound.
     *
     * <p>Was an {@code EntityTracker} before the rendering backend moved into its own module; it is now
     * the renderer-agnostic {@link ModelHandle}, which carries exactly the sixteen operations this class
     * and its two components perform. The field keeps its name so that the subclasses this project does
     * not contain — {@code ModelImpl} is abstract and consumers are expected to extend it — see the same
     * member they always did.
     */
    protected @Nullable ModelHandle entityTracker;

    private @Nullable BaseEntityType baseEntity;

    protected ModelImpl(
            @NotNull Class<BaseEntityType> entityTypeClass,
            @NotNull String modelId,
            @NotNull IntegratedModelsPlugin plugin
    ) {
        this.entityTypeClass = entityTypeClass;
        // Resolved and discarded: the constructor's only interest is refusing a model the renderer does
        // not have. A definition may not be retained — see ModelDefinition — and createTracker() names
        // the model again when it actually needs it.
        ModelRenderBackends.require().definition(modelId).orElseThrow(() -> new IllegalArgumentException("Model not found: " + modelId));
        this.name = modelId;
        this.rig = Rig.modelOnly(modelId);
        this.plugin = plugin;
        this.uniqueIdentifier = UUID.randomUUID();
        this.components = new CopyOnWriteArrayList<>();
        this.modelDecorator = new ModelDecoratorImpl<>(this);
    }

    @Override
    public void spawn(@NotNull Location location) {
        this.baseEntity = location.getWorld().spawn(location, entityTypeClass);

        this.createTracker();
    }

    @Override
    public void apply(@NotNull BaseEntityType entity) {
        if(this.baseEntity != null || this.entityTracker != null){
            despawn();
        }

        this.baseEntity = entity;

        this.createTracker();
    }

    private void createTracker() {
        if(this.baseEntity == null) return;

        var persistentDataContainer = this.baseEntity.getPersistentDataContainer();

        persistentDataContainer.set(IntegratedModelsNamespacedKeys.MODEL_IDENTIFIER.toNamespacedKey(), PersistentDataType.BOOLEAN, true);

        this.entityTracker = ModelRenderBackends.require().attach(this.baseEntity, this.name);
    }

    @Override
    public void despawn() {
        if(this.entityTracker != null){
            this.entityTracker.close();
            this.entityTracker = null;
        }
        if (this.baseEntity != null) {
            this.baseEntity.remove();
            this.baseEntity = null;
        }
    }

    @Override
    public @NotNull ModelDecorator<BaseEntityType> getDecorator() {
        return this.modelDecorator;
    }

    @Override
    public @NotNull UUID getUniqueIdentifier() {
        return this.uniqueIdentifier;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A model-only rig naming this model and driving no bones, which is what a component-API model is:
     * the renderer owns the animation and nothing here poses a bone itself.
     */
    @Override
    public @NotNull Rig getRig() {
        return this.rig;
    }
    @Override
    public @NotNull Optional<BaseEntityType> getBaseEntity() {
        return Optional.ofNullable(this.baseEntity);
    }

    @Override
    public void hideForAllExcept(@NotNull Player player) {
        plugin.getServer().
                getOnlinePlayers()
                .forEach(onlinePlayer -> {
                    if(!onlinePlayer.getUniqueId().equals(player.getUniqueId())){
                        hide(onlinePlayer);
                    }else{
                        show(player);
                    }
                });
    }

    @Override
    public void showForAllExcept(@NotNull Player player) {
        plugin.getServer()
                .getOnlinePlayers()
                .forEach(onlinePlayer -> {
                    if(!onlinePlayer.getUniqueId().equals(player.getUniqueId())){
                        show(onlinePlayer);
                    }else{
                        hide(onlinePlayer);
                    }
                });
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>The guard below is inverted and is left that way deliberately.</strong> It was ported
     * verbatim from the project this one came from, it reads as a defect because it is one — the body
     * runs precisely when the model is <em>not</em> spawned, and then asserts non-null on the path where
     * both fields can be null — and changing it is a behaviour change nobody has signed off. The return
     * type moved from {@code void} to {@code boolean} when the model kinds were unified; the condition
     * did not. See {@link #isSpawned()}, which is inverted in the matching way.
     */
    @Override
    public boolean hide(@NotNull Player player) {
        if (isSpawned()) return false;

        assert this.entityTracker != null; // isSpawned checks for nullability
        // Answers false for a player the renderer has never seen, which is what the renderer-side
        // presence check used to express before the backend moved behind ModelHandle.
        this.entityTracker.hide(player);
        assert this.baseEntity != null;
        player.hideEntity(plugin, this.baseEntity);
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The same inverted guard as {@link #hide(Player)}, left alone for the same reason.
     */
    @Override
    public boolean show(@NotNull Player player) {
        if (isSpawned()) return false;

        assert this.entityTracker != null; // isSpawned checks for nullability
        this.entityTracker.show(player);
        assert this.baseEntity != null;
        player.showEntity(plugin, this.baseEntity);
        return true;
    }

    @Override
    public @NotNull Collection<ModelComponent<BaseEntityType>> getComponents() {
        return this.components;
    }

    @Override
    public @NotNull String getName(){
        return this.name;
    }

    @Override
    public @NotNull <ComponentType extends ModelComponent<BaseEntityType>> Collection<ComponentType> getComponentsByType(@NotNull Class<ComponentType> componentType) {
        return this.components
                .stream()
                .filter(componentType::isInstance)
                .map(componentType::cast)
                .toList();
    }

    @Override
    public void addComponent(@NotNull ModelComponent<BaseEntityType> component) {
        this.components.add(component);
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>Inverted, and left that way deliberately.</strong> The third condition answers "not
     * spawned" for a live tracker and "spawned" for a closed one. Ported verbatim, listed as a known
     * pre-existing defect, and not fixed here: it interacts with {@link #hide(Player)},
     * {@link #show(Player)} and {@link #start()}, so correcting one of the four in isolation changes
     * observable behaviour. Note that {@code isAlive()}, which
     * {@link pandastic.studio.models.api.model.Model} defines as an alias of this method, therefore
     * inherits the same inversion — it is one bug under two names, not two bugs.
     */
    @Override
    public boolean isSpawned() {
        if(this.baseEntity == null || this.entityTracker == null ) return false;
        if(!this.entityTracker.isClosed()) return false;
        if(this.baseEntity.isDead()) return false;
        return this.baseEntity.isValid();
    }

    /**
     * We can assert since {@link #isSpawned}  checks nullability
     **/
    @Override
    @SuppressWarnings("unchecked")
    public void start() {
        if(isSpawned()){
            assert this.baseEntity != null;
            var scheduledTask = this.baseEntity.getScheduler().runAtFixedRate(plugin, task -> {
                    if(!isSpawned()){
                        task.cancel();
                    }

                this.getComponentsByType(ModelScheduledComponent.class)
                        .forEach(component -> ((ModelScheduledComponent<BaseEntityType>) component).onTick());
            },  this::despawn, 1L, 1L);

            if(scheduledTask != null){
                this.setTask(scheduledTask, ScheduledTask::cancel);
            }
        }

    }

    @Override
    public void stop() {
        this.getTask().ifPresent(_ -> this.setTask(null, ScheduledTask::cancel));
    }

    /**
     * The renderer's handle on this model, empty while nothing is bound.
     *
     * <p>Every operation the two components perform is guarded by this returning a value, so a call on an
     * unbound model is a no-op rather than an NPE.
     */
    public @NotNull Optional<ModelHandle> getEntityTracker() {
        return Optional.ofNullable(this.entityTracker);
    }

    public @NotNull IntegratedModelsPlugin getPlugin(){
        return plugin;
    }

    public @NotNull Class<BaseEntityType> getBaseEntityType(){
        return entityTypeClass;
    }
}
