package pandastic.studio.models.internal.menu;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A catalogue a test edits between two clicks.
 *
 * <p>Which is the case worth testing. A real catalogue is re-read by the picker's own Reload button and
 * by a configuration reload, so a page number and a selected id routinely outlive the list they index —
 * and that is not a pathological scenario, it is the ordinary consequence of a menu that has no way to
 * stay open.
 */
final class FakeModelCatalogue implements ModelCatalogue {

    private final @NotNull List<String> models = new ArrayList<>();
    private final @NotNull Map<String, List<String>> animations = new LinkedHashMap<>();

    private int reloads;

    /** A catalogue holding {@code ids}, in that order. */
    static @NotNull FakeModelCatalogue of(@NotNull String... ids) {
        final FakeModelCatalogue catalogue = new FakeModelCatalogue();
        catalogue.models.addAll(List.of(ids));
        return catalogue;
    }

    /** A catalogue holding {@code count} models called {@code model1}, {@code model2} and so on. */
    static @NotNull FakeModelCatalogue sized(int count) {
        final FakeModelCatalogue catalogue = new FakeModelCatalogue();
        for (int index = 1; index <= count; index++) {
            catalogue.models.add("model" + index);
        }
        return catalogue;
    }

    /** Declares the animations {@code modelId} offers. */
    @NotNull FakeModelCatalogue withAnimations(@NotNull String modelId, @NotNull String... ids) {
        this.animations.put(modelId, List.of(ids));
        return this;
    }

    /** Replaces the model list, as a reload between two clicks would. */
    void replaceModels(@NotNull String... ids) {
        this.models.clear();
        this.models.addAll(List.of(ids));
    }

    @Override
    public @NotNull List<String> modelIds() {
        return List.copyOf(this.models);
    }

    @Override
    public boolean hasModel(@NotNull String modelId) {
        return this.models.contains(modelId);
    }

    @Override
    public @NotNull List<String> animationIds(@NotNull String modelId) {
        return this.animations.getOrDefault(modelId, List.of());
    }

    @Override
    public int reload() {
        this.reloads++;
        return this.models.size();
    }

    /** How many times Reload was pressed. */
    int reloads() {
        return this.reloads;
    }
}
