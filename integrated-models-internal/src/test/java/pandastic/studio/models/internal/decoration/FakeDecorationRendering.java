package pandastic.studio.models.internal.decoration;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.decoration.DecorationPlacement;
import pandastic.studio.models.api.pose.Pose;
import pandastic.studio.models.api.render.PoseSink;
import pandastic.studio.models.api.rig.Rig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A rendering the test drives by hand, modelling the parts of the renderer's documented behaviour that
 * the layer above it has to cope with.
 *
 * <p>Three of those are the reason the decoration handle looks the way it does, so the fake reproduces
 * all three: a spawn can <strong>fail</strong>, because the player it was for went offline between the
 * decision and the packet; a detach stops emissions without releasing anything; and a close is latched,
 * so the ordering of the two is observable rather than theoretical.
 *
 * <p>The sink counts emissions and keeps the last pose it was given, copied — the real contract says the
 * pose is borrowed and will be mutated as soon as the call returns. Counting is the whole point: the
 * claim that a resting decoration is silent is a claim about this number not growing.
 */
final class FakeDecorationRendering implements DecorationRendering {

    private final @NotNull DecorationFixtures.Journal journal;
    private final @NotNull List<DecorationPlacement> moves = new ArrayList<>();
    private final @NotNull Set<UUID> spawned = new LinkedHashSet<>();
    private final @NotNull CountingSink sink = new CountingSink();

    private boolean detached;
    private boolean closed;
    private boolean refuseShow;

    FakeDecorationRendering(@NotNull DecorationFixtures.Journal journal) {
        this.journal = journal;
    }

    // ---------------------------------------------------------------- the seam

    @Override
    public @NotNull PoseSink sink() {
        return this.sink;
    }

    @Override
    public void detach() {
        if (this.detached) {
            return;
        }
        this.journal.record("detach");
        this.detached = true;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.journal.record("close");
        this.closed = true;
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void moveTo(@NotNull DecorationPlacement placement) {
        this.journal.record("moveTo");
        this.moves.add(placement);
    }

    @Override
    public boolean show(@NotNull UUID viewer) {
        this.journal.record("show");
        if (this.refuseShow || this.closed) {
            return false;
        }
        this.spawned.add(viewer);
        return true;
    }

    @Override
    public boolean hide(@NotNull UUID viewer) {
        this.journal.record("hide");
        if (this.closed) {
            return false;
        }
        return this.spawned.remove(viewer);
    }

    // ------------------------------------------------------------ test driving

    /** Makes every subsequent show fail, as it does for a player who just went offline. */
    void refuseShow() {
        this.refuseShow = true;
    }

    boolean isDetached() {
        return this.detached;
    }

    @NotNull List<DecorationPlacement> moves() {
        return List.copyOf(this.moves);
    }

    /** Who the renderer believes currently holds this decoration. */
    @NotNull Set<UUID> spawnedFor() {
        return Set.copyOf(this.spawned);
    }

    /** How many poses have been written, which is the bandwidth claim in one number. */
    int emissions() {
        return this.sink.emissions;
    }

    int lastInterpolationTicks() {
        return this.sink.lastInterpolationTicks;
    }

    /** The emissions that landed while the sink was detached, which must always be zero. */
    int emissionsWhileDetached() {
        return this.sink.whileDetached;
    }

    /**
     * Counts what goes on the wire.
     *
     * <p>Inner rather than static so it can see whether the rendering has been detached: an emission
     * that arrives after a detach is the one failure the teardown ordering exists to prevent, and
     * counting it separately is what turns that ordering into an assertion.
     */
    private final class CountingSink implements PoseSink {

        private int emissions;
        private int whileDetached;
        private int lastInterpolationTicks;

        @Override
        public void accept(@NotNull Rig rig, @NotNull Pose pose, int interpolationTicks) {
            this.emissions++;
            this.lastInterpolationTicks = interpolationTicks;
            if (FakeDecorationRendering.this.detached) {
                this.whileDetached++;
            }
        }
    }
}
