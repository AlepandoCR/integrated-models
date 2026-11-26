package pandastic.studio.models.internal.view;

import org.jetbrains.annotations.NotNull;

/**
 * Whatever keeps a {@link ViewerIndex} filled: the lifecycle of the one part of this layer that has to
 * touch the platform.
 *
 * <h2>Why the seam is this shape</h2>
 * Reading a player's position is the single thing in the view layer that cannot be done anywhere but on
 * the region that owns that player, so it is also the single thing a test cannot exercise. Everything
 * else here — the publication, the decision, the gate, the pass rate — is arithmetic over values, and
 * putting the platform half behind three lifecycle methods is what keeps it that way.
 *
 * <p>The production implementation is {@link ViewerSampler}. A runtime assembled without one gets
 * {@link #NONE}, and the consequence is stated rather than discovered: an empty index means every model
 * is held by nobody, which means every model is a candidate for suspension. That is the right behaviour
 * for a test, where nothing is being rendered anyway, and it is why a production runtime must not use it.
 */
public interface ViewerPublisher {

    /** Begins publishing. Idempotent. */
    void start();

    /**
     * Stops publishing and empties the index. Idempotent, and must run on disable.
     *
     * @return how many per-player tasks were cancelled
     */
    int stop();

    /**
     * Re-dispatches at the configured interval, if it moved.
     *
     * @return how many per-player tasks were re-dispatched
     */
    int reload();

    /** How many viewers are currently being sampled. */
    int size();

    /** A publisher that publishes nothing. For a runtime with no platform under it. */
    @NotNull ViewerPublisher NONE = new ViewerPublisher() {

        @Override
        public void start() {
        }

        @Override
        public int stop() {
            return 0;
        }

        @Override
        public int reload() {
            return 0;
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public @NotNull String toString() {
            return "ViewerPublisher.NONE";
        }
    };
}
