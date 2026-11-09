package pandastic.studio.models.internal.menu;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A presenter that builds the screen and shows it to nobody.
 *
 * <p>It invokes the supplier, which is the point: construction is where a screen reads what the player
 * is wearing and decides what it contains, so invoking it is what makes the whole navigation graph
 * assertable — every re-show after an action, every refusal that lands on the picker, and the controller
 * that would have been built in each case.
 *
 * <p>What it deliberately does not do is build a dialog. That step needs the server's registry, and the
 * line between "the screen was decided" and "the screen was serialised" is exactly where this double
 * sits.
 */
final class FakeMenuPresenter implements MenuPresenter {

    /**
     * Every screen shown, in order.
     *
     * @param viewer     who it was shown to
     * @param controller the controller that was built
     */
    record Shown(@NotNull Player viewer, @NotNull Object controller) {
    }

    private final @NotNull List<Shown> shown = new ArrayList<>();

    @Override
    public void show(@NotNull Player viewer, @NotNull java.util.function.Supplier<@NotNull Object> controller) {
        this.shown.add(new Shown(viewer, controller.get()));
    }

    /** Every screen shown, in order. */
    @NotNull List<Shown> shown() {
        return List.copyOf(this.shown);
    }

    /** How many screens have been shown. */
    int count() {
        return this.shown.size();
    }

    /** The most recently shown controller, or {@code null} when none has been. */
    @Nullable Object last() {
        return this.shown.isEmpty() ? null : this.shown.getLast().controller();
    }

    /**
     * The most recently shown controller, asserted to be of {@code type}.
     *
     * @param type the screen expected
     * @param <ScreenType> the screen type
     * @return the controller
     */
    <ScreenType> @NotNull ScreenType last(@NotNull Class<ScreenType> type) {
        final Object controller = last();
        if (!type.isInstance(controller)) {
            throw new AssertionError("The last screen shown was "
                    + (controller == null ? "none" : controller.getClass().getSimpleName())
                    + " rather than " + type.getSimpleName());
        }
        return type.cast(controller);
    }

    /** Forgets what has been shown. */
    void reset() {
        this.shown.clear();
    }
}
