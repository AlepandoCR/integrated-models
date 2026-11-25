package pandastic.studio.models.internal.input;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.input.InputKey;
import pandastic.studio.models.api.input.InputSnapshot;
import pandastic.studio.models.api.input.InputState;
import pandastic.studio.models.api.input.InputTrace;

import java.util.Arrays;
import java.util.Objects;

/**
 * One player's input history, and the one place a snapshot of it is published.
 *
 * <h2>The safety argument this type exists to make</h2>
 * An input is observed on a thread that may not drive anything: the platform's notification fires on
 * the player's own region thread, a packet arrives on a network thread, and a vehicle's control step
 * runs on the region that owns the vehicle. So the input is <em>sampled where it arrives</em> and
 * handed over as an immutable value, which is precisely what
 * {@link pandastic.studio.models.internal.sim.DriverHolder} does for entity movement and for the same
 * reason. A reader that sees the new reference sees every field of it, and sees them all from the same
 * input.
 *
 * <h2>Why synchronized, where the driver holder needed no lock</h2>
 * Because this has two writers and the update is a read-modify-write. The driver holder's region thread
 * publishes a fully-built value it computed elsewhere; here, publishing means comparing against the
 * last state to find the edges, stamping press times, and advancing a sequence number — and two
 * threads can be doing it for the same player at once, since the packet source and the platform source
 * both observe movement. A monitor is the cheap, obvious answer: the critical section is a dozen array
 * writes, and contention requires two inputs for one player in the same microsecond.
 *
 * <p>Reads stay lock-free. {@link #current()} is one volatile read, which is what lets a control step
 * on another region thread poll it without ever touching this object's monitor.
 *
 * <h2>Why a momentary input lingers, and why not forever</h2>
 * A pulse is true for exactly one publication, which is correct for a <em>bound handler</em> — it is
 * dispatched synchronously with the publication and sees it. It is useless for a <em>poller</em>: a
 * control step running at twenty hertz would miss almost every click, because the next publication
 * would already have cleared it.
 *
 * <p>So the pulse bits stay in the published state until something clears them, and what clears them
 * is either the next publication or {@link #current(long, long)} finding them older than the
 * configured visibility window. That is a deliberate middle: zero would hide every action input from
 * every polling consumer, and unbounded would turn one click into an attack flag that reads as held
 * until the player next moves — the client only sends a movement packet when the movement changes, so
 * "the next publication" can be a long time coming.
 *
 * <h2>Last writer wins</h2>
 * A snapshot is a state, not an event queue. Two observations of the same player between two control
 * steps leave the later one published and the earlier one of no interest, because a control step wants
 * the current input rather than the history of the last 50 ms. Bound handlers, which <em>do</em> care
 * about every edge, are dispatched from the publication itself and so see both.
 */
public final class InputTrack {

    /**
     * {@link #lastPressNanos} entry for a key that has never been pressed.
     *
     * <p>Structure: a sentinel, and {@link Long#MIN_VALUE} rather than zero because the clock is
     * monotonic from an arbitrary origin and zero is a time it can legitimately report.
     */
    private static final long NEVER_PRESSED = Long.MIN_VALUE;

    /** Press time per key; meaningful only where the published state has the key down. */
    private final long[] downSinceNanos = new long[InputKey.COUNT];

    /** Most recent press time per key, or {@link #NEVER_PRESSED}. */
    private final long[] lastPressNanos = new long[InputKey.COUNT];

    /** Gap between the last two presses per key, or {@link InputTrace#NO_EARLIER_PRESS}. */
    private final long[] pressGapNanos = new long[InputKey.COUNT];

    /** The seven held bits as last observed, so a pulse publication does not lose them. */
    private int heldBits;

    private int hotbarSlot = InputState.UNKNOWN_HOTBAR_SLOT;

    private long sequence;

    /**
     * The published value.
     *
     * <p>Defaults to {@link InputSnapshot#IDLE} rather than to {@code null}, so a control step that
     * runs before the first input — which it will, since the step and the player's first packet are
     * independent — reads "nothing held" instead of needing a null check on its hot path.
     */
    private volatile @NotNull InputSnapshot published = InputSnapshot.IDLE;

    /** A track for a player nothing has been observed for yet. */
    public InputTrack() {
        Arrays.fill(this.lastPressNanos, NEVER_PRESSED);
        Arrays.fill(this.pressGapNanos, InputTrace.NO_EARLIER_PRESS);
    }

    /**
     * Records the seven held movement bits.
     *
     * <p>Clears any momentary bit from the previous publication, which is what makes a pulse last one
     * publication. Returns {@code null} when the resulting state is the one already published, so a
     * repeated identical observation — which happens routinely, because two capture sources see the
     * same movement change — costs one comparison and dispatches nothing.
     *
     * @param movementBits the held bits; anything outside the movement family is ignored
     * @param nowNanos     the monotonic time of the observation
     * @return the new snapshot, or {@code null} when nothing changed
     */
    public synchronized @Nullable InputSnapshot held(int movementBits, long nowNanos) {
        this.heldBits = movementBits & InputKey.MOVEMENT_BITS;
        return publishIfChanged(this.heldBits, nowNanos);
    }

    /**
     * Records one momentary input.
     *
     * <p>Always publishes, even when the same pulse was the last thing published: two clicks are two
     * inputs, and collapsing them would make a double tap undetectable. The press times are stamped
     * from the previous published state, so the second click of a double tap sees the first one's gap.
     *
     * @param key      the momentary key
     * @param nowNanos the monotonic time of the observation
     * @return the new snapshot, never {@code null}
     */
    public synchronized @NotNull InputSnapshot pulse(@NotNull InputKey key, long nowNanos) {
        Objects.requireNonNull(key, "key");
        return publish(this.heldBits | key.mask(), this.hotbarSlot, nowNanos, key.mask());
    }

    /**
     * Records a change of selected hotbar slot.
     *
     * <p>Both halves of the input at once: the slot number, which goes on the state, and the
     * {@link InputKey#SELECT_HOTBAR_SLOT} pulse, which is what a combination can name.
     *
     * @param slot     the new slot
     * @param nowNanos the monotonic time of the observation
     * @return the new snapshot, never {@code null}
     */
    public synchronized @NotNull InputSnapshot hotbarSlot(int slot, long nowNanos) {
        this.hotbarSlot = slot;
        return publish(this.heldBits | InputKey.SELECT_HOTBAR_SLOT.mask(), slot, nowNanos,
                InputKey.SELECT_HOTBAR_SLOT.mask());
    }

    /**
     * The published snapshot, with no clock involved.
     *
     * <p>One volatile read. For the dispatcher, which already has the snapshot it just published, and
     * for a caller that does not care whether a momentary bit has outlived its visibility window.
     *
     * @return the published snapshot
     */
    public @NotNull InputSnapshot current() {
        return this.published;
    }

    /**
     * The published snapshot, with any expired momentary bit cleared.
     *
     * <p>What {@link PlayerInputControllerImpl#snapshot} calls. The clearing is lazy and republished,
     * so the cost is one extra publication per pulse rather than one per read, and a second reader
     * arriving after the first sees the cleared value without doing the work again.
     *
     * @param nowNanos             the monotonic time of the read
     * @param pulseVisibilityNanos how long a momentary bit stays visible
     * @return the snapshot to answer with
     */
    public @NotNull InputSnapshot current(long nowNanos, long pulseVisibilityNanos) {
        final InputSnapshot snapshot = this.published;
        if (snapshot.state().actionBits() == 0
                || !snapshot.isStale(nowNanos, pulseVisibilityNanos)) {
            return snapshot;
        }
        return clearPulses(nowNanos);
    }

    /**
     * Forgets everything about this player.
     *
     * <p>For a player who has disconnected. The stale state would otherwise keep whatever was held at
     * the moment the connection dropped — a client that goes away mid-key never sends the release —
     * and anything polling it would read that input forever.
     */
    public synchronized void reset() {
        Arrays.fill(this.downSinceNanos, 0L);
        Arrays.fill(this.lastPressNanos, NEVER_PRESSED);
        Arrays.fill(this.pressGapNanos, InputTrace.NO_EARLIER_PRESS);
        this.heldBits = 0;
        this.hotbarSlot = InputState.UNKNOWN_HOTBAR_SLOT;
        this.sequence = 0L;
        this.published = InputSnapshot.IDLE;
    }

    /** Publishes the held state, dropping any expired momentary bit. Returns what is now published. */
    private synchronized @NotNull InputSnapshot clearPulses(long nowNanos) {
        final InputSnapshot snapshot = this.published;
        if (snapshot.state().actionBits() == 0) {
            // Another thread cleared them between the volatile read and this monitor. Nothing to do,
            // and republishing would advance the sequence number for no observation.
            return snapshot;
        }
        return publish(this.heldBits, this.hotbarSlot, nowNanos);
    }

    private @Nullable InputSnapshot publishIfChanged(int bits, long nowNanos) {
        final InputState state = new InputState(bits, this.hotbarSlot);
        return state.equals(this.published.state()) ? null : publish(bits, this.hotbarSlot, nowNanos);
    }

    /**
     * Stamps the edges and publishes.
     *
     * <p>The previous state is the one that was <em>published</em>, not an internal shadow copy, which
     * is what makes {@link InputTrace#previous()} and the edge predicates built on it describe exactly
     * the step a reader is looking at.
     */
    private @NotNull InputSnapshot publish(int bits, int slot, long nowNanos) {
        return publish(bits, slot, nowNanos, 0);
    }

    /**
     * As {@link #publish(int, int, long)}, but treating {@code pulseMask} as freshly pressed whatever
     * the previous publication said.
     *
     * <p>A momentary input is an <em>event</em>, not a state, so it must not go through the change
     * comparison. A pulse bit lingers into the following publication on purpose — a reader stepping at
     * its own rate has to be able to see a click that happened between two of its steps — but that
     * lingering bit is then sitting in {@code previous} when the <em>next</em> click arrives. Comparing
     * against it would make the second click invisible: {@code justPressed} false, and no press
     * recorded, so {@code lastPressGapNanos} never fills in and a double tap cannot be detected. Which
     * is the one thing a pulse exists to support.
     *
     * @param pulseMask the momentary bits this publication is announcing, or {@code 0} for a plain
     *                  state change
     */
    private @NotNull InputSnapshot publish(int bits, int slot, long nowNanos, int pulseMask) {
        final InputState lastPublished = this.published.state();
        final InputState previous = pulseMask == 0
                ? lastPublished
                : new InputState(lastPublished.bits() & ~pulseMask, lastPublished.hotbarSlot());
        final InputState state = new InputState(bits, slot);

        int pressed = state.bits() & ~previous.bits();
        while (pressed != 0) {
            final int index = Integer.numberOfTrailingZeros(pressed);
            pressed &= pressed - 1;

            this.downSinceNanos[index] = nowNanos;
            final long last = this.lastPressNanos[index];
            this.pressGapNanos[index] =
                    last == NEVER_PRESSED ? InputTrace.NO_EARLIER_PRESS : nowNanos - last;
            this.lastPressNanos[index] = nowNanos;
        }

        this.sequence++;
        final InputSnapshot snapshot = InputSnapshot.of(
                state, previous, nowNanos, this.downSinceNanos, this.pressGapNanos, this.sequence);
        this.published = snapshot;
        return snapshot;
    }
}
