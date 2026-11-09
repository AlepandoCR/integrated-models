package pandastic.studio.models.internal.menu;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A wardrobe that writes to a map instead of to a model.
 *
 * <p>It is also where the menus' idempotence assumption is checked: removing nothing, stopping an
 * animation that is not playing and dressing a player in what they already wear are all defined as
 * doing nothing, and this double behaves that way rather than throwing — so a test that drives a click
 * for state that has already moved on sees the same no-op the real implementation promises.
 */
final class FakeModelWardrobe implements ModelWardrobe {

    private final @NotNull Map<UUID, String> worn = new LinkedHashMap<>();
    private final @NotNull Map<UUID, ModelAppearance> appearances = new LinkedHashMap<>();
    private final @NotNull Map<UUID, String> animations = new LinkedHashMap<>();

    private int removals;
    private int stops;

    /** Dresses {@code player} without going through a menu, to set a test up. */
    void dress(@NotNull Player player, @NotNull String modelId) {
        this.worn.put(player.getUniqueId(), modelId);
    }

    /** Sets {@code player}'s stored appearance without going through a menu. */
    void store(@NotNull Player player, @NotNull ModelAppearance appearance) {
        this.appearances.put(player.getUniqueId(), appearance);
    }

    @Override
    public @NotNull Optional<String> wornModel(@NotNull Player viewer) {
        return Optional.ofNullable(this.worn.get(viewer.getUniqueId()));
    }

    @Override
    public void wear(@NotNull Player viewer, @NotNull String modelId) {
        this.worn.put(viewer.getUniqueId(), modelId);
    }

    @Override
    public void remove(@NotNull Player viewer) {
        this.removals++;
        this.worn.remove(viewer.getUniqueId());
        this.animations.remove(viewer.getUniqueId());
    }

    @Override
    public @NotNull ModelAppearance readAppearance(
            @NotNull Player viewer,
            @NotNull ModelAppearance defaults
    ) {
        return this.appearances.getOrDefault(viewer.getUniqueId(), defaults);
    }

    @Override
    public void applyAppearance(@NotNull Player viewer, @NotNull ModelAppearance appearance) {
        this.appearances.put(viewer.getUniqueId(), appearance);
    }

    @Override
    public void playAnimation(@NotNull Player viewer, @NotNull String animationId) {
        this.animations.put(viewer.getUniqueId(), animationId);
    }

    @Override
    public void stopAnimation(@NotNull Player viewer) {
        this.stops++;
        this.animations.remove(viewer.getUniqueId());
    }

    /** What {@code player} is wearing, or {@code null}. */
    @Nullable String wornBy(@NotNull Player player) {
        return this.worn.get(player.getUniqueId());
    }

    /** What {@code player} looks like, or {@code null} when nothing was ever applied. */
    @Nullable ModelAppearance appearanceOf(@NotNull Player player) {
        return this.appearances.get(player.getUniqueId());
    }

    /** What is playing on {@code player}'s model, or {@code null}. */
    @Nullable String animationOf(@NotNull Player player) {
        return this.animations.get(player.getUniqueId());
    }

    /** How many times Remove was pressed. */
    int removals() {
        return this.removals;
    }

    /** How many times Stop was pressed. */
    int stops() {
        return this.stops;
    }
}
