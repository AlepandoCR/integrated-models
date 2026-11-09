package pandastic.studio.models.internal.model.component.decoration;

import kr.toxicity.model.api.platform.PlatformBillboard;
import kr.toxicity.model.api.tracker.ModelRotation;
import kr.toxicity.model.api.tracker.ModelScaler;
import kr.toxicity.model.api.tracker.TrackerUpdateAction;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.model.component.decoration.ModelDecorator;
import pandastic.studio.models.internal.model.ModelImpl;
import pandastic.studio.models.internal.model.component.ModelComponentImpl;

public class ModelDecoratorImpl<BaseEntityType extends Entity> extends ModelComponentImpl<BaseEntityType> implements ModelDecorator<BaseEntityType> {

    public ModelDecoratorImpl(@NotNull ModelImpl<BaseEntityType> model) {
        super(model);
    }

    @Override
    public void setScale(float scale) {
        this.model.getEntityTracker()
                .ifPresent(tracker -> tracker.scaler(ModelScaler.value(scale)));
    }
    @Override
    public void setDamageTint(int rgb) {
        this.model.getEntityTracker()
                .ifPresent(tracker -> tracker.damageTintValue(rgb));
    }

    @Override
    public void setGlowColor(int rgb) {
        this.model.getEntityTracker()
                .ifPresent(tracker -> tracker.update(TrackerUpdateAction.glowColor(rgb)));
    }

    @Override
    public void setGlowing(boolean glowing) {
        this.model.getEntityTracker()
                .ifPresent(tracker -> tracker.update(TrackerUpdateAction.glow(glowing)));
    }

    @Override
    public void setEnchantmentGlint(boolean enchanted) {
        this.model.getEntityTracker()
                .ifPresent(tracker -> tracker.update(TrackerUpdateAction.enchant(enchanted)));
    }

    @Override
    public void setBillboard(@NotNull Display.Billboard billboard) {
        final var platformBillboard = switch (billboard) {
            case Display.Billboard.FIXED -> PlatformBillboard.FIXED;
            case Display.Billboard.VERTICAL -> PlatformBillboard.VERTICAL;
            case Display.Billboard.HORIZONTAL -> PlatformBillboard.HORIZONTAL;
            case Display.Billboard.CENTER -> PlatformBillboard.CENTER;
        };

        this.model.getEntityTracker()
                .ifPresent(tracker -> tracker.update(TrackerUpdateAction.billboard(platformBillboard)));
    }

    @Override
    public void setBrightness(int blockLightLevel, int skyLightLevel) {
        this.model.getEntityTracker()
                .ifPresent(tracker -> tracker.update(TrackerUpdateAction.brightness(blockLightLevel, skyLightLevel)));
    }

    @Override
    public void setViewRange(float viewRange) {
        this.model.getEntityTracker()
                .ifPresent(tracker -> tracker.update(TrackerUpdateAction.viewRange(viewRange)));
    }

    @Override
    public void setTint(int rgb) {
        this.model.getEntityTracker()
                .ifPresent(tracker -> tracker.update(TrackerUpdateAction.tint(rgb)));
    }

    @Override
    public void setRotation(float x, float y) {
        this.model.getEntityTracker()
                .ifPresent(tracker -> tracker.rotation(() -> new ModelRotation(x, y)));
    }
}
