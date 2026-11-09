package pandastic.studio.models.internal.util.namespacedkey;

import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.internal.IntegratedModelsPlugin;

public enum IntegratedModelsNamespacedKeys {

    MODEL_IDENTIFIER("model")

    ;

    private final @NotNull String key;
    IntegratedModelsNamespacedKeys(@NotNull String key){
        this.key = key;
    }

    public @NotNull NamespacedKey toNamespacedKey(){
        return new NamespacedKey(IntegratedModelsPlugin.getInstance(), this.key);
    }
}
