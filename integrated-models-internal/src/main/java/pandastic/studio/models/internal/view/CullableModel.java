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
     *
     * <p>It also takes the model out of {@link #setSimulated(boolean)}'s reach entirely — see there. A
     * model that cannot be culled cannot be suspended either, because the two answers would contradict
     * each other: "nobody holds this" would otherwise be indistinguishable from "this is not a decision
     * I am allowed to make".
     */
    default boolean isCullable() {
        return true;
    }

    /**
     * Stops or restarts the work this model only does for the benefit of a viewer.
     *
     * <h2>This is where the saving is, and the packets are not it</h2>
     * Suppressing packets for a model nobody holds is worth having and is <em>not</em> what makes a
     * culler pay for itself. The per-step cost of a bound model is the simulation: a pose reset, a gait
     * evaluation, a spring integration over every bone at the configured substep rate, and the emission
     * that follows. All of it runs whether or not a single client is listening, because
     * {@link pandastic.studio.models.internal.sim.SimulationLoop} knows nothing about viewers.
     *
     * <p>So the culler drives this. {@code false} takes the model's simulation off the loop's per-iteration
     * work without unregistering it, and {@code true} puts it back. An implementation that cannot do that
     * returns {@code false} from both and simply never saves anything, which is the honest answer for a
     * model with no simulation of its own.
     *
     * <h2>What resuming does not have to do</h2>
     * Nothing. In particular it must not reset the simulation, and this method must not touch one: the
     * culling pass runs on the off-tick pass thread while the simulation loop is stepping on its own, so
     * reaching into a {@code ModelSimulation} from here would be a race against the one thread that is
     * allowed to. A single volatile flag is the whole mechanism.
     *
     * <p>That is safe because both layers already clamp a long interval. The first step after a resume
     * measures the entire suspended gap, and the solver banks fractional time up to
     * {@code PhysicsTuning#maxSubsteps()} and discards the rest, while the walk cycle clamps its own step
     * to {@code GaitTuning#maxStepSeconds()}. So a model that was suspended for a minute catches up by a
     * bounded amount and carries on, rather than either diverging or snapping back to its rest pose.
     *
     * @param simulated whether this model should be simulated
     * @return whether this call changed the state
     */
    default boolean setSimulated(boolean simulated) {
        return false;
    }

    /**
     * Whether this model's simulation is currently being stepped.
     *
     * <p>{@code true} for a model that cannot be suspended, so a diagnostic counting what is live is not
     * misled by a model that was never a candidate.
     */
    default boolean isSimulated() {
        return true;
    }
}
