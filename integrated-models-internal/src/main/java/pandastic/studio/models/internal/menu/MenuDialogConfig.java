package pandastic.studio.models.internal.menu;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.dialog.api.factory.DialogConfig;

import java.util.function.Supplier;

/**
 * The one thing the dialog layer takes from configuration on its own: a screen's title.
 *
 * <p>{@link DialogConfig} declares exactly one method, so this is the whole of what a controller can
 * hand the factory from outside its own annotations. Everything else a screen reads from
 * {@code menu.yml} — every label, every bound, every page size — goes through the controller's provider
 * methods instead, because that is where the layer offers a hook.
 *
 * <p>A supplier rather than a component, because the factory re-reads the annotated field on every
 * build: a title that is computed on demand resolves against the configuration live at the moment the
 * screen is shown, which is the behaviour the factory documents and the reason it re-reads at all.
 *
 * <p>The component is called {@code source} rather than {@code title} because a record component by
 * that name would generate an accessor clashing with the interface method it is here to satisfy.
 *
 * @param source computes the title; invoked once per build
 */
record MenuDialogConfig(@NotNull Supplier<Component> source) implements DialogConfig {

    @Override
    public @NotNull Component title() {
        return this.source.get();
    }
}
