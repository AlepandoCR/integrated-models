package pandastic.studio.models.api.model.component;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.model.EntityModel;

/**
 * One capability of an {@link EntityModel}.
 *
 * <p>A component belongs to the entity-bound model specifically, not to
 * {@link pandastic.studio.models.api.model.Model}: a component is defined by the model it hangs off and,
 * for {@link pandastic.studio.models.api.model.component.scheduled.ModelScheduledComponent}, by being
 * ticked from that model's own entity-scheduler task. The kinds of model that have no entity have no such
 * task and nothing that could ever call a component, so a component list on the root would have been a
 * field nothing reads.
 */
public interface ModelComponent<BaseEntityType extends Entity> {

    /**
     * The model this component belongs to.
     *
     * @return the owning model
     */
    @NotNull EntityModel<BaseEntityType> getModel();

}
