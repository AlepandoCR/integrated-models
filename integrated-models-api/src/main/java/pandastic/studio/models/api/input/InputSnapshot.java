package pandastic.studio.models.api.input;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Objects;

/**
 * One player's input, published as a value a consumer may hold for a whole step.
 *
 * <h2>The hand-over this type exists to make safe</h2>
 * An input arrives on a thread that may not touch a vehicle or a model: the platform's change
 * notification fires on the player's own region thread, and a packet arrives on a network thread.
 * Neither is where a vehicle step runs. So the input has to be <em>published as an immutable value</em>
 * and read by whatever is being driven on its own schedule — exactly the arrangement the pose
 * pipeline's driver sample already uses, and for exactly the same reason.
 *
 * <p>The publication is a single reference store of a fully-built object, which is what carries the
 * happens-before edge: a reader that sees this snapshot sees every field of it, and sees them all from
 * the same input. A mutable holder written field by field would publish a sprint flag from this tick
 * against a hold duration from the last, which reads as a control scheme that occasionally disagrees
 * with itself and reproduces for nobody.
 *
 * <h2>Read it once per step</h2>
 * Not once per question. Two reads inside one step can straddle a publication and give one decision
 * two different inputs — the same hazard as reading the configuration snapshot twice. Hold the
 * snapshot, ask it everything, finish the step.
 *
 * <h2>Why a class rather than a record</h2>
 * Two of its six components are {@code long[]} of length {@link InputKey#COUNT}, and a record would
 * publish them through accessors that hand out the arrays themselves. Arrays are copied in and never
 * handed out, which is what makes "immutable" true rather than intended. The arrays are there because
 * the alternative is twelve objects per publication: a snapshot is built on every input change for
 * every player, so one object and two small arrays is the shape that can afford to be allocated there.
 */
public final class InputSnapshot implements InputTrace {

    /**
     * What a controller answers for a player it has never observed.
     *
     * <p>A real value rather than {@code null} or an empty {@code Optional}, so the first step of a
     * control loop that starts before the first packet reads "nothing held" instead of needing a null
     * check on its hot path. {@link #nowNanos()} is zero, which is harmless: every time on this
     * interface is only meaningful as a difference, and an idle snapshot reports no key down, so no
     * duration is read from it.
     */
    public static final @NotNull InputSnapshot IDLE = new InputSnapshot(
            InputState.NONE, InputState.NONE, 0L, emptyHolds(), emptyGaps(), 0L);

    private final @NotNull InputState state;
    private final @NotNull InputState previous;
    private final long nowNanos;

    /** Press time per key, valid only where {@link #state} has the key down. Never handed out. */
    private final long @NotNull [] downSinceNanos;

    /** Gap between the last two presses per key, or {@link InputTrace#NO_EARLIER_PRESS}. */
    private final long @NotNull [] pressGapNanos;

    private final long sequence;

    private InputSnapshot(
            @NotNull InputState state,
            @NotNull InputState previous,
            long nowNanos,
            long @NotNull [] downSinceNanos,
            long @NotNull [] pressGapNanos,
            long sequence
    ) {
        this.state = state;
        this.previous = previous;
        this.nowNanos = nowNanos;
        this.downSinceNanos = downSinceNanos;
        this.pressGapNanos = pressGapNanos;
        this.sequence = sequence;
    }

    /**
     * Builds a snapshot, copying the per-key timing arrays.
     *
     * <p>For the capture layer, which owns mutable working arrays and publishes a copy of them. A
     * consumer never calls this; it is public because the capture layer is in another module and
     * because a test wanting a trace with a specific hold duration has no other way to make one.
     *
     * @param state          the input as of now
     * @param previous       the input as of the publication before
     * @param nowNanos       the monotonic time of this publication
     * @param downSinceNanos press time per key, indexed by {@link InputKey#bit()}
     * @param pressGapNanos  gap between the last two presses per key, indexed the same way
     * @param sequence       publication counter for this player, strictly increasing
     * @return the snapshot
     * @throws IllegalArgumentException if either array is not {@link InputKey#COUNT} long
     */
    @Contract(value = "_, _, _, _, _, _ -> new", pure = true)
    public static @NotNull InputSnapshot of(
            @NotNull InputState state,
            @NotNull InputState previous,
            long nowNanos,
            long @NotNull [] downSinceNanos,
            long @NotNull [] pressGapNanos,
            long sequence
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(previous, "previous");
        requireLength(downSinceNanos, "downSinceNanos");
        requireLength(pressGapNanos, "pressGapNanos");
        return new InputSnapshot(state, previous, nowNanos,
                downSinceNanos.clone(), pressGapNanos.clone(), sequence);
    }

    /**
     * A snapshot with no timing history, for a caller that only has flags.
     *
     * <p>Honest about what it cannot answer: {@link #heldNanos(InputKey)} is zero for every key and
     * {@link #lastPressGapNanos(InputKey)} is {@link InputTrace#NO_EARLIER_PRESS}, so a
     * {@link InputCombination#hold} or {@link InputCombination#doubleTap} evaluated against it does not
     * match. That is the right answer rather than a limitation — a caller with no history has not
     * observed a hold.
     *
     * @param state    the input as of now
     * @param previous the input as of before
     * @param nowNanos the monotonic time
     * @return the snapshot
     */
    @Contract(value = "_, _, _ -> new", pure = true)
    public static @NotNull InputSnapshot ofFlags(
            @NotNull InputState state,
            @NotNull InputState previous,
            long nowNanos
    ) {
        // Every press time is now, not zero. A zero would be read as a press at the clock's origin,
        // so every key that happens to be down would report a hold of however long the server has
        // been up — and a hold combination evaluated against such a snapshot would match on the first
        // observation. "No history" has to mean "pressed this instant", which is the only reading
        // that makes a caller with no history unable to claim a duration it never saw.
        final long[] downSince = new long[InputKey.COUNT];
        Arrays.fill(downSince, nowNanos);
        return new InputSnapshot(
                Objects.requireNonNull(state, "state"),
                Objects.requireNonNull(previous, "previous"),
                nowNanos, downSince, emptyGaps(), 0L);
    }

    private static void requireLength(long @NotNull [] array, @NotNull String name) {
        if (Objects.requireNonNull(array, name).length != InputKey.COUNT) {
            throw new IllegalArgumentException(name + " must have one entry per key ("
                    + InputKey.COUNT + ") but had " + array.length);
        }
    }

    private static long @NotNull [] emptyHolds() {
        return new long[InputKey.COUNT];
    }

    private static long @NotNull [] emptyGaps() {
        final long[] gaps = new long[InputKey.COUNT];
        Arrays.fill(gaps, NO_EARLIER_PRESS);
        return gaps;
    }

    @Override
    public @NotNull InputState state() {
        return this.state;
    }

    @Override
    public @NotNull InputState previous() {
        return this.previous;
    }

    @Override
    public long nowNanos() {
        return this.nowNanos;
    }

    @Override
    public long heldNanos(@NotNull InputKey key) {
        if (!this.state.isDown(Objects.requireNonNull(key, "key"))) {
            return 0L;
        }
        final long held = this.nowNanos - this.downSinceNanos[key.bit()];
        return Math.max(0L, held);
    }

    @Override
    public long lastPressGapNanos(@NotNull InputKey key) {
        return this.pressGapNanos[Objects.requireNonNull(key, "key").bit()];
    }

    /**
     * How many times this player's input has been published, counting from the first observation.
     *
     * <p>Not for a control decision — it is for the discipline the class note states. A step that wants
     * to act only on fresh input compares this against the value it saw last step, and a test asserting
     * that a step read the snapshot once can assert that it saw one sequence number rather than two.
     *
     * @return the publication counter, {@code 0} for {@link #IDLE}
     */
    public long sequence() {
        return this.sequence;
    }

    /**
     * Whether this snapshot is older than {@code maxAgeNanos} at {@code nowNanos}.
     *
     * <p>A disconnecting client stops sending input without releasing anything, so the last state a
     * server saw can have forward held forever. Whatever reads this decides what to do about that; the
     * controller's own answer is to report {@link #IDLE} past the configured staleness window.
     *
     * @param atNanos      the monotonic time to compare against
     * @param maxAgeNanos  how old is too old
     * @return whether this snapshot should no longer be trusted
     */
    @Contract(pure = true)
    public boolean isStale(long atNanos, long maxAgeNanos) {
        return atNanos - this.nowNanos > maxAgeNanos;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof InputSnapshot that)) {
            return false;
        }
        return this.nowNanos == that.nowNanos
                && this.sequence == that.sequence
                && this.state.equals(that.state)
                && this.previous.equals(that.previous)
                && Arrays.equals(this.downSinceNanos, that.downSinceNanos)
                && Arrays.equals(this.pressGapNanos, that.pressGapNanos);
    }

    @Override
    public int hashCode() {
        int hash = Objects.hash(this.state, this.previous, this.nowNanos, this.sequence);
        hash = 31 * hash + Arrays.hashCode(this.downSinceNanos);
        hash = 31 * hash + Arrays.hashCode(this.pressGapNanos);
        return hash;
    }

    @Override
    public @NotNull String toString() {
        return "InputSnapshot[" + this.state.describe() + " was=" + this.previous.describe()
                + " slot=" + this.state.hotbarSlot() + " seq=" + this.sequence + ']';
    }
}
