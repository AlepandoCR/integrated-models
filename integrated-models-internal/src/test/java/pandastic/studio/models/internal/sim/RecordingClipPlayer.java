package pandastic.studio.models.internal.sim;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A {@link ClipPlayer} that writes down what it was asked to do.
 *
 * <p>Records calls as {@code "play:<clip>:<in>:<out>"} and {@code "stop:<clip>"}, in order, which makes
 * both the sequence and the blend durations assertable in one list — the sequence matters because a
 * replacement has to start before the clip it replaces is stopped, and the durations matter because they
 * are configuration that has to reach the renderer rather than being whatever the default happened to be.
 */
final class RecordingClipPlayer implements ClipPlayer {

    private final @NotNull Set<String> known;
    private final @NotNull List<String> calls = new ArrayList<>();

    /** A player that accepts every clip name. */
    RecordingClipPlayer() {
        this.known = Set.of();
    }

    /** A player that only accepts {@code known}, declining anything else as the renderer would. */
    RecordingClipPlayer(@NotNull Set<String> known) {
        this.known = known;
    }

    @Override
    public boolean play(@NotNull String clip, int blendInTicks, int blendOutTicks) {
        this.calls.add("play:" + clip + ":" + blendInTicks + ":" + blendOutTicks);
        return this.known.isEmpty() || this.known.contains(clip);
    }

    @Override
    public boolean stop(@NotNull String clip) {
        this.calls.add("stop:" + clip);
        return true;
    }

    @NotNull List<String> calls() {
        return List.copyOf(this.calls);
    }

    void clear() {
        this.calls.clear();
    }
}
