package pandastic.studio.models.internal.menu;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * What one player has in flight across the menus: the page they are on, the model they are looking at,
 * and the one line waiting to be shown to them.
 *
 * <h2>Why there is state at all</h2>
 * Because every click closes the dialog. The dialog layer commits a button press and dismisses the
 * screen; there is no way to keep one open and no way to update one in place. So a menu that looks like
 * it stayed open is a menu that was rebuilt, and everything the rebuild has to know that the player did
 * not just type into an input has to be remembered here.
 *
 * <p>That is three things and no more: which page, which model is selected, and what to say about the
 * last action. The appearance itself is deliberately <em>not</em> here — it lives on the model once
 * applied, and before that it lives in the dialog's own inputs, which arrive with the click.
 *
 * <h2>Why every field is volatile</h2>
 * A session is written only by the thread that owns its player — dialog callbacks and the presenter both
 * run there — but {@link #lastTouchedNanos()} is read by whichever thread next prunes the store, and a
 * session can be handed to a different thread's eviction pass. Volatile on all of it costs a fence on a
 * click and removes a category of question nobody should have to answer while reading this.
 *
 * <h2>Why it expires</h2>
 * Because nothing reports that a dialog was closed. See {@link MenuSessions}.
 */
public final class MenuSession {

    private final @NotNull UUID player;

    private volatile int pickerPage;
    private volatile int animationPage;
    private volatile @Nullable String selectedModel;
    private volatile @Nullable Component notice;
    private volatile long lastTouchedNanos;

    /**
     * @param player           who the session belongs to
     * @param createdAtNanos   the monotonic reading to count the idle timeout from
     */
    MenuSession(@NotNull UUID player, long createdAtNanos) {
        this.player = player;
        this.lastTouchedNanos = createdAtNanos;
    }

    /** Who the session belongs to. */
    public @NotNull UUID player() {
        return this.player;
    }

    /**
     * The picker page the player was last on.
     *
     * <p>Never clamped on the way in or out: the catalogue it indexes can change between two clicks, so
     * the clamp belongs to {@link MenuPage#of} where the current size is known, and doing it here as
     * well would make a stale index look valid.
     */
    public int pickerPage() {
        return this.pickerPage;
    }

    /** Moves the player to {@code page} of the picker. */
    public void pickerPage(int page) {
        this.pickerPage = page;
    }

    /** The animation page the player was last on. */
    public int animationPage() {
        return this.animationPage;
    }

    /** Moves the player to {@code page} of the animation chooser. */
    public void animationPage(int page) {
        this.animationPage = page;
    }

    /**
     * The model the picker is currently previewing, if any.
     *
     * <p>This is the <em>selection</em>, not what the player is wearing. The two differ for as long as a
     * player has pressed the preview button without pressing Wear, which is the whole reason the preview
     * exists as a separate step.
     */
    public @NotNull Optional<String> selectedModel() {
        return Optional.ofNullable(this.selectedModel);
    }

    /** Records the model the picker should preview. */
    public void selectedModel(@Nullable String modelId) {
        this.selectedModel = modelId;
    }

    /**
     * Takes the line waiting to be shown, clearing it.
     *
     * <p>One-shot on purpose. A notice describes what the last click did, so it belongs to exactly the
     * next screen the player sees; leaving it in place would have "Model removed" still on screen three
     * pages later.
     *
     * @return the pending line, or {@code null} when there is none
     */
    public @Nullable Component takeNotice() {
        final Component pending = this.notice;
        this.notice = null;
        return pending;
    }

    /** Queues {@code line} to be shown on the next screen this player is sent. */
    public void notice(@Nullable Component line) {
        this.notice = line;
    }

    /** The monotonic reading of the player's last interaction. */
    public long lastTouchedNanos() {
        return this.lastTouchedNanos;
    }

    /** Records that the player just interacted, restarting the idle timeout. */
    void touch(long nowNanos) {
        this.lastTouchedNanos = nowNanos;
    }

    /**
     * Whether this session has been idle for at least {@code timeoutNanos}.
     *
     * <p>The subtraction is deliberately {@code now - last >= timeout} rather than
     * {@code now >= last + timeout}: a monotonic clock may start anywhere, including near
     * {@link Long#MAX_VALUE}, and only the difference is meaningful.
     *
     * @param nowNanos     the current monotonic reading
     * @param timeoutNanos the configured idle timeout
     * @return {@code true} when the session may be discarded
     */
    boolean isExpired(long nowNanos, long timeoutNanos) {
        return nowNanos - this.lastTouchedNanos >= timeoutNanos;
    }
}
