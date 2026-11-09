package pandastic.studio.models.api.model.component.decoration;

import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.model.component.ModelComponent;

public interface ModelDecorator<BaseEntityType extends Entity> extends ModelComponent<BaseEntityType> {

    void setScale(float scale);

    void setDamageTint(int rgb);

    void setGlowColor(int rgb);

    void setGlowing(boolean glowing);

    void setEnchantmentGlint(boolean enchanted);

    void setBillboard(@NotNull Display.Billboard billboard);

    void setBrightness(int blockLightLevel, int skyLightLevel);

    void setViewRange(float viewRange);

    void setTint(int rgb);

    void setRotation(float x, float y);

}
