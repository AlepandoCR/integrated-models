package pandastic.studio.models.internal.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Turns a configured label into something a dialog can carry.
 *
 * <h2>Why MiniMessage</h2>
 * Because it collapses two problems into one key. Without it every label needs a colour key beside it,
 * and a label that wants one word emphasised needs three. With it the operator writes
 * {@code <red><bold>Remove} in the same string and the plugin declares no opinion about styling at all.
 *
 * <h2>Why every substitution is unparsed</h2>
 * The values substituted into these templates are <strong>not the operator's text</strong>. A model id
 * and an animation id come from a renderer asset folder, and a file there can be called anything,
 * including {@code <red>}. Substituting such a name through a parsed placeholder would let a file name
 * decide what the rest of the line looks like — and in the limit let it inject a click or hover event
 * into a line an operator wrote. {@link Placeholder#unparsed} ends that: the value lands in the
 * component as literal text and whatever it contains is shown rather than interpreted.
 *
 * <p>The one exception is {@link #swatch}, which is a component by construction rather than text from
 * disk.
 *
 * <h2>Blank means absent</h2>
 * {@link #optional} answers {@code null} for a blank template, which is exactly what the dialog layer
 * wants: a body or input provider returning {@code null} is skipped. So an operator turns a line off by
 * emptying its key, and no screen needs a second key to say whether the first one applies.
 */
public final class MenuText {

    /**
     * The serialiser. Stateless and thread-safe by contract, so one instance serves every screen on
     * every thread rather than one per call — the parser is the expensive part of a build and
     * re-creating it per label would be the only measurable cost in this layer.
     */
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private MenuText() {
    }

    /**
     * Renders a template with no substitutions.
     *
     * @param template the MiniMessage source
     * @return the rendered component
     */
    @Contract(pure = true)
    public static @NotNull Component of(@NotNull String template) {
        return MINI_MESSAGE.deserialize(template);
    }

    /**
     * Renders a template with the given substitutions.
     *
     * @param template  the MiniMessage source
     * @param resolvers the placeholders to substitute
     * @return the rendered component
     */
    @Contract(pure = true)
    public static @NotNull Component of(@NotNull String template, @NotNull TagResolver @NotNull ... resolvers) {
        return MINI_MESSAGE.deserialize(template, resolvers);
    }

    /**
     * Renders a template, or answers {@code null} when it is blank.
     *
     * <p>A {@code null} from a dialog body or input provider is skipped, so this is how an emptied key
     * removes a line instead of leaving an empty one.
     *
     * @param template  the MiniMessage source; blank to omit the element
     * @param resolvers the placeholders to substitute
     * @return the rendered component, or {@code null} to omit it
     */
    @Contract(pure = true)
    public static @Nullable Component optional(
            @NotNull String template,
            @NotNull TagResolver @NotNull ... resolvers
    ) {
        if (template.isBlank()) {
            return null;
        }
        return MINI_MESSAGE.deserialize(template, resolvers);
    }

    /**
     * A placeholder carrying text that is not the operator's.
     *
     * @param name  the tag name, as it appears in angle brackets
     * @param value the text to substitute, shown literally whatever it contains
     * @return the resolver
     */
    @Contract(value = "_, _ -> new", pure = true)
    public static @NotNull TagResolver placeholder(@NotNull String name, @NotNull String value) {
        return Placeholder.unparsed(name, value);
    }

    /**
     * A placeholder carrying a number.
     *
     * @param name  the tag name, as it appears in angle brackets
     * @param value the number to substitute
     * @return the resolver
     */
    @Contract(value = "_, _ -> new", pure = true)
    public static @NotNull TagResolver placeholder(@NotNull String name, int value) {
        return Placeholder.unparsed(name, Integer.toString(value));
    }

    /**
     * The {@code swatch} placeholder: the configured glyph, tinted.
     *
     * <p>A dialog cannot draw a rectangle, so a colour is shown by colouring text. The glyph is taken as
     * <strong>plain text rather than MiniMessage</strong>, which is the point: a glyph that carried its
     * own colour tag would override the tint and the swatch would stop tracking the player's colour,
     * silently and only for the operator who styled it. That is also why this is the layer's one
     * component placeholder — there is a component to substitute precisely because the text was never
     * parsed.
     *
     * @param glyph      the configured swatch text
     * @param appearance the appearance whose colour to tint it with
     * @return the resolver
     */
    @Contract(value = "_, _ -> new", pure = true)
    public static @NotNull TagResolver swatch(
            @NotNull String glyph,
            @NotNull ModelAppearance appearance
    ) {
        return Placeholder.component("swatch", Component.text(glyph)
                .color(TextColor.color(appearance.packedRgb())));
    }
}
