package pandastic.studio.models.internal.binding;

import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.internal.render.RenderSettingsSource;

/**
 * Turns an entity into a {@link ModelAnchor}.
 *
 * <p>One method, and it exists to keep {@link ModelBinderImpl} free of the two things only an anchor
 * needs: the plugin instance that owns its scheduled tasks, and the render settings its sink reads. The
 * binder's own job — validate, hand out a handle, remember it — involves neither, and a binder that held
 * a {@code Plugin} could not be constructed in a test at all.
 *
 * <p>That is not a testing convenience; it is the same division of labour the rest of this package
 * follows. Platform objects live behind the anchor seam, and the layers above it deal in anchors.
 */
@FunctionalInterface
public interface AnchorFactory {

    /** An anchor following {@code entity}. */
    @NotNull ModelAnchor anchorFor(@NotNull Entity entity);

    /**
     * The production factory: entity anchors scheduled against {@code plugin}.
     *
     * @param renderSettings where a bound sink reads its dirty epsilon from, once per emission
     */
    static @NotNull AnchorFactory entities(
            @NotNull Plugin plugin,
            @NotNull RenderSettingsSource renderSettings
    ) {
        return entity -> new EntityAnchor(plugin, entity, renderSettings);
    }
}
