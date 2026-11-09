package pandastic.studio.models.internal.menu;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The write path: what a menu is actually able to do to a player's model.
 *
 * <h2>Why the menus do not do it themselves</h2>
 * Because a menu's job ends at deciding, and a decision is a very different thing from a model
 * operation. Everything above this interface is arithmetic over strings and configuration and can be
 * exercised without a server; everything below it touches an entity, a tracker and a region. Keeping the
 * boundary at one interface is what makes the first half testable and the second half reviewable.
 *
 * <h2>The region contract — every method, no exceptions</h2>
 * <strong>Every method here is called on the thread that owns {@code viewer}</strong>, and an
 * implementation may assume that and nothing more. The presenter puts screen construction and every
 * click's consequences on the viewer's entity scheduler precisely so this is true, because on Folia
 * reading an entity from another region's thread is undefined rather than merely racy and the server
 * will not say so. An implementation that wants to touch a <em>different</em> entity — a mount, a
 * nearby display — has to schedule that itself.
 *
 * <h2>Everything is idempotent</h2>
 * A click can arrive for a state that has already moved on: a player whose model was removed by
 * something else between opening the screen and pressing Apply. So removing nothing, stopping an
 * animation that is not playing, and dressing a player in the model they are already wearing are all
 * defined as doing nothing rather than as errors. The menus check what they can and then stop
 * pretending they know; this is where that assumption is paid for.
 */
public interface ModelWardrobe {

    /**
     * The model {@code viewer} is wearing.
     *
     * @param viewer the player
     * @return the model id, or empty when they wear none
     */
    @NotNull Optional<String> wornModel(@NotNull Player viewer);

    /**
     * Puts {@code modelId} on {@code viewer}, replacing whatever they wore.
     *
     * <p>The id has already been checked against the catalogue by the caller. An implementation is still
     * entitled to refuse one it cannot resolve — the two checks can disagree after a reload — and a
     * refusal is a no-op rather than an exception.
     *
     * @param viewer  the player
     * @param modelId the model to put on
     */
    void wear(@NotNull Player viewer, @NotNull String modelId);

    /**
     * Takes {@code viewer}'s model off. A no-op when they wear none.
     *
     * @param viewer the player
     */
    void remove(@NotNull Player viewer);

    /**
     * {@code viewer}'s current visual state, or the configured defaults when they wear nothing.
     *
     * <p>Answering with the defaults rather than with empty is deliberate: the appearance screen opens
     * its controls at this value, and a screen that cannot open because a model was removed a moment ago
     * is worse than one that opens at the defaults.
     *
     * @param viewer   the player
     * @param defaults what to answer when there is nothing to read
     * @return the appearance to show
     */
    @NotNull ModelAppearance readAppearance(
            @NotNull Player viewer,
            @NotNull ModelAppearance defaults
    );

    /**
     * Applies {@code appearance} to {@code viewer}'s model.
     *
     * <p>Already clamped to the configured ranges by the caller, because the values arrive in a packet
     * the client composed and the slider bounds it was handed are a hint to the interface rather than a
     * constraint on the wire.
     *
     * @param viewer     the player
     * @param appearance the state to apply
     */
    void applyAppearance(@NotNull Player viewer, @NotNull ModelAppearance appearance);

    /**
     * Starts {@code animationId} on {@code viewer}'s model.
     *
     * @param viewer      the player
     * @param animationId the animation to play, already checked against the catalogue
     */
    void playAnimation(@NotNull Player viewer, @NotNull String animationId);

    /**
     * Stops whatever is playing on {@code viewer}'s model. A no-op when nothing is.
     *
     * @param viewer the player
     */
    void stopAnimation(@NotNull Player viewer);
}
