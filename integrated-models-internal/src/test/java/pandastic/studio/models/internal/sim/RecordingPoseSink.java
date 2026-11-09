package pandastic.studio.models.internal.sim;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.pose.Pose;
import pandastic.studio.models.api.render.PoseSink;
import pandastic.studio.models.api.rig.Rig;

import java.util.ArrayList;
import java.util.List;

/**
 * Records every emission, snapshotting the pose because the real contract says it is borrowed and will
 * be mutated as soon as the call returns.
 *
 * <p>It does keep the borrowed reference in {@link #lastBorrowed()} — for identity comparison only, so
 * a test can show the simulation reuses one buffer rather than allocating a pose per emission.
 */
final class RecordingPoseSink implements PoseSink {

    /** One emission, with the pose copied out of the simulation's live buffer. */
    record Emission(@NotNull Rig rig, @NotNull Pose pose, int interpolationTicks) {
    }

    private final @NotNull List<Emission> emissions = new ArrayList<>();

    private @Nullable Pose lastBorrowed;
    private @Nullable RuntimeException failure;

    @Override
    public void accept(@NotNull Rig rig, @NotNull Pose pose, int interpolationTicks) {
        this.lastBorrowed = pose;
        final Pose copy = new Pose(pose.boneCount());
        copy.copyFrom(pose);
        this.emissions.add(new Emission(rig, copy, interpolationTicks));
        if (this.failure != null) {
            throw this.failure;
        }
    }

    /** Makes every subsequent emission throw. Pass {@code null} to stop failing. */
    void failWith(@Nullable RuntimeException failure) {
        this.failure = failure;
    }

    @NotNull List<Emission> emissions() {
        return this.emissions;
    }

    int count() {
        return this.emissions.size();
    }

    @NotNull Emission last() {
        return this.emissions.get(this.emissions.size() - 1);
    }

    @Nullable Pose lastBorrowed() {
        return this.lastBorrowed;
    }
}
