package pandastic.studio.models.internal.command;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.internal.config.ConfigReloadResult;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What the last reload did, remembered for exactly as long as the server runs.
 *
 * <h2>The one piece of instrumentation the status commands add</h2>
 * Every other figure a health or statistics report prints is read from something that already knew it —
 * the backend resolution, the registry, the two loops, the input controller, the live snapshot's
 * revision. This is the single exception, and it exists because the configuration layer deliberately
 * keeps no history: {@code ConfigService} publishes one snapshot through one reference and forgets how it
 * got there, which is the right design for a reader on the simulation thread and leaves nobody able to
 * answer "did my last edit take". A refused reload is also the one degradation in this plugin that is
 * completely invisible afterwards — the previous values stay live <em>by identity</em>, so a server
 * running on refused files looks exactly like a server nobody has edited.
 *
 * <p>The cost is one reference write per reload, on the thread that performed it. That is cheap enough
 * that it does not need a switch, and a switch would be a configurable way to turn off the answer to the
 * question the health command exists to ask.
 *
 * <h2>Why a decorator rather than a hook inside the command</h2>
 * {@link #observing(ReloadOperation)} wraps the operation the reload command was already given, so the
 * command itself is untouched: it still takes one {@link ReloadOperation}, still cannot tell whether
 * anything is watching, and its behaviour — the gate, the dispatch, the reporting — is unchanged. It also
 * means a reload triggered through any other path that goes through the same operation is recorded, and a
 * reload triggered through the plugin's own public method is not, which is the honest outcome: this type
 * records what it was wired in front of and nothing else.
 *
 * <h2>Threading</h2>
 * Written from the thread a reload ran on, which is never a region thread, and read from whichever thread
 * typed a status command. One {@link AtomicReference} to one immutable value, for the reason the
 * configuration layer uses one: two fields would let a reader see an outcome from one reload beside a
 * revision from another.
 */
public final class ReloadHistory {

    /** {@code null} until the first reload goes through {@link #observing(ReloadOperation)}. */
    private final @NotNull AtomicReference<Entry> last = new AtomicReference<>();

    /**
     * The last reload that was observed.
     *
     * @return the entry, or empty when none has been
     */
    public @NotNull Optional<Entry> last() {
        return Optional.ofNullable(this.last.get());
    }

    /**
     * What the configuration half of a status report says, including the "nothing yet" case.
     *
     * <p>Assembled here rather than in the reporter because the fallback is the interesting half: with no
     * reload observed, the outcome is {@link ReloadOutcome#UNKNOWN} and the revision is still the live
     * one, which is a fact the caller has and this class does not.
     *
     * @param liveRevision the revision of the snapshot in force right now
     * @return the configuration part of a status snapshot
     */
    public @NotNull StatusSnapshot.Configuration describe(long liveRevision) {
        final Entry entry = this.last.get();
        if (entry == null) {
            return new StatusSnapshot.Configuration(liveRevision, ReloadOutcome.UNKNOWN, 0);
        }
        // The live revision wins over the recorded one. They differ only if something published a
        // snapshot without going through this history, and in that case the number an operator wants is
        // the one that is in force rather than the one this class last saw.
        return new StatusSnapshot.Configuration(liveRevision, entry.outcome(), entry.problems());
    }

    /**
     * The same operation, with its outcome recorded.
     *
     * <p>A failure is recorded and then rethrown, unchanged: the reload command's own failure path is
     * what reports it to the operator and to the console, and swallowing it here would turn a reload that
     * threw into a reload that silently did nothing.
     *
     * @param operation what a reload is
     * @return the operation, wrapped
     */
    public @NotNull ReloadOperation observing(@NotNull ReloadOperation operation) {
        Objects.requireNonNull(operation, "operation");
        return () -> {
            final ReloadReport report;
            try {
                report = operation.perform();
            } catch (final RuntimeException failure) {
                this.last.set(new Entry(ReloadOutcome.FAILED, -1L, 0));
                throw failure;
            }
            record(report.result());
            return report;
        };
    }

    /**
     * Records one result directly.
     *
     * <p>Package-private: the production path is {@link #observing(ReloadOperation)}, and a second public
     * way in would let two callers disagree about which reload was the last one. The tests use it to pin
     * down what each outcome records.
     *
     * @param result what the configuration layer did
     */
    void record(@NotNull ConfigReloadResult result) {
        Objects.requireNonNull(result, "result");
        this.last.set(new Entry(
                result.applied() ? ReloadOutcome.APPLIED : ReloadOutcome.REFUSED,
                result.snapshot().revision(),
                result.diagnostics().size()));
    }

    @Override
    public @NotNull String toString() {
        final Entry entry = this.last.get();
        return "ReloadHistory[" + (entry == null ? "nothing observed" : entry.toString()) + "]";
    }

    /**
     * One observed reload.
     *
     * @param outcome  what it did
     * @param revision the revision that was live afterwards, or {@code -1} for a reload that threw —
     *                 which is a structural sentinel rather than a figure, because a reload that threw
     *                 may not have reached the point where a revision exists to report
     * @param problems how many diagnostics it produced; zero for anything but a refusal
     */
    public record Entry(@NotNull ReloadOutcome outcome, long revision, int problems) {

        public Entry {
            Objects.requireNonNull(outcome, "outcome");
            if (problems < 0) {
                throw new IllegalArgumentException("problems cannot be negative but was " + problems);
            }
            if (outcome == ReloadOutcome.REFUSED && problems == 0) {
                throw new IllegalArgumentException("A refused reload has to carry why it was refused");
            }
        }

        /** Whether this is the outcome an operator has to act on. */
        public boolean isTrouble() {
            return this.outcome == ReloadOutcome.REFUSED || this.outcome == ReloadOutcome.FAILED;
        }

        /** Whether a revision was recorded at all; see the {@code revision} note. */
        public boolean hasRevision() {
            return this.revision >= 0L;
        }

        @Override
        public @NotNull String toString() {
            return this.outcome + " at revision " + (hasRevision() ? this.revision : "unknown")
                    + (this.problems == 0 ? "" : " with " + this.problems + " problem(s)");
        }
    }
}
