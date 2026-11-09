package pandastic.studio.models.internal.decoration;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.decoration.AmbientMotion;
import pandastic.studio.models.api.decoration.DecorationPlacement;
import pandastic.studio.models.internal.sim.DriverHolder;

/**
 * What {@link DecorationDriverLoop} needs from a decoration: where it is, how exposed it is, and where
 * to publish the answer.
 *
 * <p>Three methods, deliberately. The loop has no business knowing about rigs, trackers, viewers or
 * lifecycles, and keeping it to this shape is what lets the whole driving layer — the arithmetic that
 * decides whether a decoration is silent — be tested against a handful of values rather than a server.
 *
 * <h2>Everything here is read from an arbitrary thread</h2>
 * The loop runs off the server tick with no thread affinity, so an implementation must answer all three
 * from state it publishes safely. In practice that means a {@code volatile} field per answer: a
 * placement and an exposure are both immutable values, so one reference store per change carries
 * everything a reader needs and a reader never sees half of a move.
 */
public interface DrivenDecoration {

    /**
     * Where this decoration currently stands.
     *
     * <p>Read <strong>once</strong> per pass by the loop, which then uses that one placement for both
     * the movement it differences and the phase of the gust it computes. Reading it twice would let a
     * move land between the two and have a decoration's movement disagree with the part of the weather
     * it is standing in.
     *
     * @return the current placement
     */
    @NotNull DecorationPlacement placement();

    /**
     * How much of the ambient field reaches this decoration.
     *
     * @return the current exposure, {@link AmbientMotion#NONE} for a sheltered decoration
     */
    @NotNull AmbientMotion ambientMotion();

    /**
     * Where the loop publishes, and the same holder this decoration's simulation reads.
     *
     * @return the driver seam between the loop and the simulation
     */
    @NotNull DriverHolder driver();
}
