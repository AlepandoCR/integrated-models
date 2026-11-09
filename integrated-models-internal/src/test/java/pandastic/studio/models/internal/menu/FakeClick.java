package pandastic.studio.models.internal.menu;

import io.papermc.paper.dialog.DialogResponseView;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.core.dialog.api.factory.DialogContext;

import java.util.HashMap;
import java.util.Map;

/**
 * One click, with whatever the client is pretending to have submitted.
 *
 * <h2>Why the inputs are a map and not a form</h2>
 * Because that is what the platform hands a handler: a view keyed by input name, answering {@code null}
 * for a key the client did not send. A client is free to send nothing, to send a key no screen declared,
 * or to send a number no slider offered — and the only thing standing between those and a model is the
 * handler. So the double lets a test send exactly that, including the shapes a real client would have to
 * be modified to produce.
 */
final class FakeClick implements DialogContext, DialogResponseView {

    private final @NotNull Audience clicker;
    private final @NotNull Map<String, String> text = new HashMap<>();
    private final @NotNull Map<String, Boolean> flags = new HashMap<>();
    private final @NotNull Map<String, Float> numbers = new HashMap<>();

    private FakeClick(@NotNull Audience clicker) {
        this.clicker = clicker;
    }

    /** A click from {@code player} with no inputs submitted. */
    static @NotNull FakeClick by(@NotNull Player player) {
        return new FakeClick(player);
    }

    /** A click from somebody who is not the screen's viewer. */
    static @NotNull FakeClick byStranger() {
        return new FakeClick(Audience.empty());
    }

    /** Submits {@code value} under {@code key}, as a chooser or a text input would. */
    @NotNull FakeClick with(@NotNull String key, @NotNull String value) {
        this.text.put(key, value);
        return this;
    }

    /** Submits {@code value} under {@code key}, as a toggle would. */
    @NotNull FakeClick with(@NotNull String key, boolean value) {
        this.flags.put(key, value);
        return this;
    }

    /** Submits {@code value} under {@code key}, as a slider would. */
    @NotNull FakeClick with(@NotNull String key, float value) {
        this.numbers.put(key, value);
        return this;
    }

    @Override
    public @NotNull Audience getAudience() {
        return this.clicker;
    }

    @Override
    public @NotNull DialogResponseView dialogResponseView() {
        return this;
    }

    @Override
    public @Nullable BinaryTagHolder payload() {
        return null;
    }

    @Override
    public @Nullable String getText(@NotNull String key) {
        return this.text.get(key);
    }

    @Override
    public @Nullable Boolean getBoolean(@NotNull String key) {
        return this.flags.get(key);
    }

    @Override
    public @Nullable Float getFloat(@NotNull String key) {
        return this.numbers.get(key);
    }
}
