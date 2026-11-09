package pandastic.studio.models.internal.view;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * What the culler needs from a model: where it is, who can currently see it, and how to change that.
 *
 * <p>Narrow on purpose. The culler has no business knowing about rigs, poses, solvers or trackers, and
 * keeping the interface to this shape is what lets the whole decision layer be tested with a handful of
 * fakes rather than a server.
 *
 * <h2>The position is a published sample, not a live read</h2>
 * An implementation must answer {@link #cullX()} and friends from a value some region thread published,
 * never by reading an entity. The culler runs off the server tick, where reading an entity's position
 * is undefined rather than merely racy. A model that has not been sampled yet should report the
 * position it was placed at.
 */
public interface CullableModel {

    /** This model's identity, for diagnostics and for ordering a budgeted pass deterministically. */
    @NotNull UUID modelId();

    /** The world this model is in. A viewer in another world never sees it. */
    @NotNull UUID worldId();

    /** Last published x. */
    double cullX();

    /**
     * Last published y.
     *
     * <p>Measured against a viewer's eye, so this should be the model's visual centre rather than its
     * feet — a tall model culled on its feet disappears while its head is still on screen.
     */
    double cullY();

    /** Last published z. */
    double cullZ();

    /**
     * Bounding radius of this model, in blocks, or a non-positive value to use the configured default.
     *
     * <p>Feeds the angular test, where it is what stops a large model being culled while part of it is
     * still on screen: the test is sphere-against-bowtie, not point-against-bowtie. A model reporting
     * too small a radius pops at the edge of the view; too large a one simply costs a little bandwidth.
     * When in doubt, over-report.
     */
    default double cullRadius() {
        return 0D;
    }

    /** Who currently holds this model's bones. */
    @NotNull ModelViewport viewport();

    /** How to spawn and despawn this model's bones per viewer. */
    @NotNull ModelViewportSink viewportSink();

    /**
     * Whether this model is eligible to be shown at all.
     *
     * <p>A model mid-bind, mid-unbind or explicitly hidden by a plugin is not a culling decision and
     * must not be treated as one: the culler's job is distance, and overriding it is somebody else's.
     * Answering {@code false} makes every viewer a hide.
     */
    default boolean isCullable() {
        return true;
    }
}
