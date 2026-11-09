package pandastic.studio.models.api.model;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.model.component.ModelComponent;
import pandastic.studio.models.api.model.component.decoration.ModelDecorator;
import pandastic.studio.models.api.util.schedule.TickerController;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface Model<BaseEntityType extends Entity> extends TickerController<ScheduledTask> {

    void spawn(@NotNull Location location);

    void apply(@NotNull BaseEntityType entity);

    void despawn();

    @NotNull ModelDecorator<BaseEntityType> getDecorator();

    @NotNull UUID getUniqueIdentifier();

    @NotNull Optional<BaseEntityType> getBaseEntity();

    @NotNull String getName();

    @NotNull Class<BaseEntityType> getBaseEntityType();

    void hideForAllExcept(@NotNull Player player);

    void showForAllExcept(@NotNull Player player);

    void hide(@NotNull Player player);

    void show(@NotNull Player player);

    @NotNull Collection<ModelComponent<BaseEntityType>> getComponents();

    <ComponentType extends ModelComponent<BaseEntityType>> @NotNull Collection<ComponentType> getComponentsByType(@NotNull Class<ComponentType> componentType);

    void addComponent(@NotNull ModelComponent<BaseEntityType> component);

    boolean isSpawned();

}
