package pandastic.studio.models.internal.decoration;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.decoration.DecorationPlacement;
import pandastic.studio.models.api.rig.Rig;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An anchor the test drives by hand.
 *
 * <p>It models the two platform behaviours the decoration lifecycle actually depends on: a thread may
 * or may not own the position being touched, and deferred work can be <strong>refused</strong> because
 * the world it was for is gone. A fake that always owned and always accepted would make two of this
 * layer's branches unreachable.
 *
 * <p>It also records which placement each question was asked about, because the seam's whole reason for
 * taking a placement per call is that the answer depends on it — an anchor asked about a stale position
 * would be an invisible bug on a regionised server.
 */
final class FakeDecorationAnchor implements DecorationAnchor {

    private final @NotNull DecorationFixtures.Journal journal;
    private final @NotNull UUID worldId = UUID.randomUUID();
    private final @NotNull List<Runnable> submitted = new ArrayList<>();
    private final @NotNull List<DecorationPlacement> ownershipQuestions = new ArrayList<>();

    private @Nullable FakeDecorationRendering rendering;
    private @Nullable RuntimeException openFailure;
    private @Nullable DecorationPlacement openedAt;

    private boolean owned = true;
    private boolean submissionRefused;

    FakeDecorationAnchor(@NotNull DecorationFixtures.Journal journal) {
        this.journal = journal;
    }

    // ---------------------------------------------------------------- the seam

    @Override
    public @NotNull UUID worldId() {
        return this.worldId;
    }

    @Override
    public boolean isOwnedByCurrentThread(@NotNull DecorationPlacement placement) {
        this.ownershipQuestions.add(placement);
        return this.owned;
    }

    @Override
    public boolean submit(@NotNull DecorationPlacement placement, @NotNull Runnable work) {
        this.journal.record("submit");
        if (this.submissionRefused) {
            return false;
        }
        this.submitted.add(work);
        return true;
    }

    @Override
    public @NotNull DecorationRendering open(
            @NotNull Rig rig,
            @NotNull DecorationPlacement placement
    ) {
        this.journal.record("open");
        if (this.openFailure != null) {
            throw this.openFailure;
        }
        this.openedAt = placement;
        final FakeDecorationRendering opened = new FakeDecorationRendering(this.journal);
        this.rendering = opened;
        return opened;
    }

    @Override
    public @NotNull String describe() {
        return "fake world";
    }

    // ------------------------------------------------------------ test driving

    void owned(boolean nowOwned) {
        this.owned = nowOwned;
    }

    void refuseSubmission() {
        this.submissionRefused = true;
    }

    void failOpenWith(@NotNull RuntimeException failure) {
        this.openFailure = failure;
    }

    /** Runs whatever was deferred through {@link #submit}. */
    int runSubmitted() {
        final List<Runnable> pending = List.copyOf(this.submitted);
        this.submitted.clear();
        pending.forEach(Runnable::run);
        return pending.size();
    }

    @Nullable FakeDecorationRendering rendering() {
        return this.rendering;
    }

    /** Where the tracker was opened, which must be the placement the handle held at that moment. */
    @Nullable DecorationPlacement openedAt() {
        return this.openedAt;
    }

    /** Every placement an ownership question was asked about, in order. */
    @NotNull List<DecorationPlacement> ownershipQuestions() {
        return List.copyOf(this.ownershipQuestions);
    }

    int pendingSubmissions() {
        return this.submitted.size();
    }
}
