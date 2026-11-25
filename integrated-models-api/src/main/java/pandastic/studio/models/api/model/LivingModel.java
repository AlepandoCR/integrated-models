package pandastic.studio.models.api.model;

import org.bukkit.entity.LivingEntity;

/**
 * An {@link AnimatedModel} whose base entity is a {@link LivingEntity}.
 *
 * <p>Narrows the entity bound and adds nothing: everything a living model can do, it can do because its
 * entity is a living one, and a consumer reaches that through {@link #getBaseEntity()}.
 */
public interface LivingModel<BaseEntityType extends LivingEntity> extends AnimatedModel<BaseEntityType> {
}
