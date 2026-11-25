package pandastic.studio.models.internal.model.component.decoration;

import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.model.component.decoration.ModelDecorator;
import pandastic.studio.models.internal.model.ModelImpl;
import pandastic.studio.models.internal.model.component.ModelComponentImpl;

/**
 * The visual state of a component-API model, one setter per renderer operation.
 *
 * <p>Ten delegations and no decision. Every one of them is guarded by
 * {@link ModelImpl#getEntityTracker()} returning a value, so a call on an unbound model is a no-op rather
 * than an NPE — which is the whole of this class's own behaviour.
 *
 * <p>What each setter <em>means</em> to a renderer is
 * {@link pandastic.studio.models.internal.spi.ModelHandle}'s contract, and the implementation is in the
 * backend module. Two of them differ deliberately from the rig pipeline's equivalents: a scale here
 * replaces whatever the entity's own scale would have contributed rather than multiplying it, and a
 * brightness here is passed through unclamped. See {@code ModelHandle} for why the two paths are not
 * merged.
 */
public class ModelDecoratorImpl<BaseEntityType extends Entity> extends ModelComponentImpl<BaseEntityType> implements ModelDecorator<BaseEntityType> {

    public ModelDecoratorImpl(@NotNull ModelImpl<BaseEntityType> model) {
        super(model);
    }

    @Override
    public void setScale(float scale) {
        this.model.getEntityTracker()
                .ifPresent(handle -> handle.setScale(scale));
    }
    @Override
    public void setDamageTint(int rgb) {
        this.model.getEntityTracker()
                .ifPresent(handle -> handle.setDamageTint(rgb));
    }

    @Override
    public void setGlowColor(int rgb) {
        this.model.getEntityTracker()
                .ifPresent(handle -> handle.setGlowColor(rgb));
    }

    @Override
    public void setGlowing(boolean glowing) {
        this.model.getEntityTracker()
                .ifPresent(handle -> handle.setGlowing(glowing));
    }

    @Override
    public void setEnchantmentGlint(boolean enchanted) {
        this.model.getEntityTracker()
                .ifPresent(handle -> handle.setEnchantmentGlint(enchanted));
    }

    @Override
    public void setBillboard(@NotNull Display.Billboard billboard) {
        this.model.getEntityTracker()
                .ifPresent(handle -> handle.setBillboard(billboard));
    }

    @Override
    public void setBrightness(int blockLightLevel, int skyLightLevel) {
        this.model.getEntityTracker()
                .ifPresent(handle -> handle.setBrightness(blockLightLevel, skyLightLevel));
    }

    @Override
    public void setViewRange(float viewRange) {
        this.model.getEntityTracker()
                .ifPresent(handle -> handle.setViewRange(viewRange));
    }

    @Override
    public void setTint(int rgb) {
        this.model.getEntityTracker()
                .ifPresent(handle -> handle.setTint(rgb));
    }

    @Override
    public void setRotation(float x, float y) {
        this.model.getEntityTracker()
                .ifPresent(handle -> handle.setRotation(x, y));
    }
}
