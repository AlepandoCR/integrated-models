package pandastic.studio.models.internal.menu;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * What the menus are allowed to offer.
 *
 * <h2>Why the menus do not ask the renderer directly</h2>
 * Two reasons, and the second is the one that matters. The obvious one is that nothing in this package
 * would otherwise be testable: a catalogue backed by the renderer's asset folder needs a server with
 * that renderer installed. The real one is that <strong>this interface is the server's answer about what
 * exists</strong>, and the picker has to re-ask it on every click rather than trust the id a client sent
 * back. Keeping the question behind one method makes "is this a real model" a single call that cannot be
 * skipped by accident.
 *
 * <h2>Order is the order a player sees</h2>
 * The lists are paged and shown as given, so an implementation that returns an unstable order makes a
 * player's page number meaningless between two clicks. Sort once, in the implementation.
 */
public interface ModelCatalogue {

    /**
     * Every model id a player may be offered, in a stable order.
     *
     * @return the ids; never live, safe to page
     */
    @NotNull List<String> modelIds();

    /**
     * Whether {@code modelId} is one this catalogue offers.
     *
     * <p>A method rather than a {@code contains} at the call site because the comparison is the
     * implementation's business: the renderer matches an id one way and a configuration file another, and
     * a picker that guessed would reject an id that works or accept one that does not.
     *
     * @param modelId the id a click carried
     * @return {@code true} when it names a model this server has
     */
    boolean hasModel(@NotNull String modelId);

    /**
     * Every animation {@code modelId} declares, in a stable order.
     *
     * @param modelId the model to ask about
     * @return the animation ids, empty when the model has none or does not exist
     */
    @NotNull List<String> animationIds(@NotNull String modelId);

    /**
     * Re-reads what is installed.
     *
     * <p>The picker's Reload button. The count is what the player is told, so it is the number of models
     * available <em>after</em> the reload rather than how many changed — a player who reloads wants to
     * know whether the thing they just installed showed up.
     *
     * @return how many models are available afterwards
     */
    int reload();
}
