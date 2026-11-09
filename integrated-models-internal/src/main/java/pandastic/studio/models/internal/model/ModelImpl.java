package pandastic.studio.models.internal.model;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.tracker.EntityTracker;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.model.Model;
import pandastic.studio.models.api.model.component.ModelComponent;
import pandastic.studio.models.api.model.component.decoration.ModelDecorator;
import pandastic.studio.models.api.model.component.scheduled.ModelScheduledComponent;
import pandastic.studio.models.internal.IntegratedModelsPlugin;
import pandastic.studio.models.internal.model.component.decoration.ModelDecoratorImpl;
import pandastic.studio.models.internal.util.namespacedkey.IntegratedModelsNamespacedKeys;
import pandastic.studio.models.internal.util.schedule.ScheduledTaskTickerControllerImpl;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public abstract class ModelImpl<BaseEntityType extends Entity> extends ScheduledTaskTickerControllerImpl implements Model<BaseEntityType> {

    private final @NotNull Class<BaseEntityType> entityTypeClass;

    private final @NotNull ModelRenderer modelRenderer;

    private final @NotNull UUID uniqueIdentifier;

    private final @NotNull List<ModelComponent<BaseEntityType>> components;

    private final @NotNull IntegratedModelsPlugin plugin;

    private final @NotNull ModelDecoratorImpl<BaseEntityType> modelDecorator;

    private final @NotNull String name;

    protected @Nullable EntityTracker entityTracker;

    private @Nullable BaseEntityType baseEntity;

    protected ModelImpl(
            @NotNull Class<BaseEntityType> entityTypeClass,
            @NotNull String modelId,
            @NotNull IntegratedModelsPlugin plugin
    ) {
        this.entityTypeClass = entityTypeClass;
        this.modelRenderer = BetterModel.model(modelId).orElseThrow(() -> new IllegalArgumentException("Model not found: " + modelId));
        this.name = modelId;
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

        var betterModelEntity = BukkitAdapter.adapt(this.baseEntity);

        this.entityTracker = this.modelRenderer.create(betterModelEntity);
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

    @Override
    public void hide(@NotNull Player player) {
        if (isSpawned()) return;

        BetterModel.player(player.getUniqueId()).ifPresent(channelHandler -> {
            assert this.entityTracker != null; // isSpawned checks for nullability
            this.entityTracker.hide(channelHandler.player());
        });
        assert this.baseEntity != null;
        player.hideEntity(plugin, this.baseEntity);
    }

    @Override
    public void show(@NotNull Player player) {
        if (isSpawned()) return;

        BetterModel.player(player.getUniqueId()).ifPresent(channelHandler -> {
            assert this.entityTracker != null; // isSpawned checks for nullability
            this.entityTracker.show(channelHandler.player());
        });
        assert this.baseEntity != null;
        player.showEntity(plugin, this.baseEntity);
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

    public @NotNull Optional<EntityTracker> getEntityTracker() {
        return Optional.ofNullable(this.entityTracker);
    }

    public @NotNull IntegratedModelsPlugin getPlugin(){
        return plugin;
    }

    public @NotNull Class<BaseEntityType> getBaseEntityType(){
        return entityTypeClass;
    }
}
