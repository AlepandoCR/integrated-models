package pandastic.studio.models.internal.command;

import org.jetbrains.annotations.NotNull;

/**
 * Where a status command reads what this server is doing.
 *
 * <h2>Why one capture rather than a dozen accessors</h2>
 * Because the alternative puts the report in charge of consistency. Seven live sources asked seven
 * questions while a report formats can be interrupted by a bind, a reload or a shutdown, and then the
 * lines disagree — eleven models on one line and nine models' worth of bones on the next. One call
 * produces one {@link StatusSnapshot}, and the reporter never holds anything live.
 *
 * <p>It is also the whole platform boundary of this feature. The production implementation is one lambda
 * over the runtime; everything that decides what the report <em>says</em> — the verdict, the verbosity,
 * the column alignment, which gaps are named — is on this side of the seam and needs no server.
 *
 * <h2>Called from a region thread, and that is fine</h2>
 * Unlike a reload, a capture does no I/O: every figure is an in-memory read of a counter, a copied
 * collection or one volatile reference. So a status command runs inline rather than hopping to the
 * asynchronous scheduler, which is what lets it answer in the same tick it was typed in.
 *
 * <p>An implementation must therefore read <strong>nothing that belongs to another region</strong>. In
 * practice that rules out exactly one thing — a model's entity — which is why
 * {@link StatusSnapshot.World#posedBones} is written against the rig and says so.
 */
@FunctionalInterface
public interface StatusSource {

    /**
     * Everything the two status commands report, as of now.
     *
     * @return the snapshot
     */
    @NotNull StatusSnapshot capture();

    /** A source that always answers {@code snapshot}. For tests and for a fixed report. */
    static @NotNull StatusSource fixed(@NotNull StatusSnapshot snapshot) {
        return () -> snapshot;
    }
}
