package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * What each seat's inputs do, for one vehicle.
 *
 * <h2>An immutable map from seat to listener, decided before the vehicle exists</h2>
 * Fixed for the vehicle's life, like its {@link VehicleProfile}: a vehicle whose controls could be
 * rewired while somebody was driving it would change what a held key means mid-hold, and the rider has
 * no way to be told. A consumer that wants two behaviours ships two {@link VehicleControls} and creates
 * the vehicle it wants.
 *
 * <h2>Three ways to address a seat, and what happens when they disagree</h2>
 * By <strong>index</strong>, by <strong>name</strong>, or by {@link #onEverySeat}. All three are
 * resolved against the vehicle's {@link SeatPlan} when the vehicle starts, and a seat matched by more
 * than one gets them composed in that order: its index listener, then its name listener, then the
 * catch-all. Composition rather than precedence, because "the driver also gets the horn" is the normal
 * case and a precedence rule would silently drop one of the two.
 *
 * <p>A listener bound to an index or a name <strong>no seat has</strong> is kept rather than refused:
 * a consumer wiring four seats against a rig a rigger is still editing should not have its vehicle fail
 * to create because seat three is not in the file yet. {@link #unmatched(SeatPlan)} is how it finds out,
 * and it is worth calling once at start-up.
 *
 * <h2>When to reach for {@link VehicleController} instead, or as well</h2>
 * This type is right when the behaviour is a handful of independent reactions — a horn, a hatch, a
 * passenger waving — because those compose and none of them owns anything. It is the wrong shape for
 * behaviour with state: a turret angle or a submarine's trim captured in several lambdas that have to
 * agree is a set of invariants with no owner. That is what a controller is for, and a vehicle may have
 * both. Per seat, this seat's composed listener runs first and the controller second.
 */
public final class VehicleControls {

    /** No seat does anything beyond the steering the rig declares. */
    public static final @NotNull VehicleControls NONE = new VehicleControls(
            Map.of(), Map.of(), null);

    private final @NotNull Map<Integer, SeatInputListener> byIndex;
    private final @NotNull Map<String, SeatInputListener> byName;
    private final @Nullable SeatInputListener everySeat;

    private VehicleControls(
            @NotNull Map<Integer, SeatInputListener> byIndex,
            @NotNull Map<String, SeatInputListener> byName,
            @Nullable SeatInputListener everySeat
    ) {
        this.byIndex = Map.copyOf(byIndex);
        this.byName = Map.copyOf(byName);
        this.everySeat = everySeat;
    }

    /** A builder. */
    @Contract(value = "-> new", pure = true)
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * Controls where one seat does something and nothing else does.
     *
     * @param seatIndex the seat to wire
     * @param listener  what its rider's inputs do
     * @return the controls
     */
    @Contract(value = "_, _ -> new", pure = true)
    public static @NotNull VehicleControls forSeat(int seatIndex, @NotNull SeatInputListener listener) {
        return builder().onSeat(seatIndex, listener).build();
    }

    /**
     * Controls where every seat's rider runs the same listener.
     *
     * <p>For a consumer that wants to see all the input and sort it out itself, from
     * {@link SeatInput#seatIndex()} and {@link SeatInput#steering()}.
     *
     * @param listener what every rider's inputs do
     * @return the controls
     */
    @Contract(value = "_ -> new", pure = true)
    public static @NotNull VehicleControls everySeat(@NotNull SeatInputListener listener) {
        return builder().onEverySeat(listener).build();
    }

    /** Whether anything at all is wired, which is what lets a vehicle skip the dispatch entirely. */
    @Contract(pure = true)
    public boolean isEmpty() {
        return this.byIndex.isEmpty() && this.byName.isEmpty() && this.everySeat == null;
    }

    /**
     * The listener for {@code seat}, with every matching binding composed.
     *
     * <p>Resolved once per seat when a vehicle starts rather than per step: the composition allocates,
     * and a control step must not.
     *
     * @param seat the seat to resolve for
     * @return the listener, or {@code null} when nothing is wired for that seat
     */
    @Contract(pure = true)
    public @Nullable SeatInputListener listenerFor(@NotNull VehicleSeat seat) {
        Objects.requireNonNull(seat, "seat");
        SeatInputListener composed = this.byIndex.get(seat.index());
        final SeatInputListener named = this.byName.get(seat.name());
        if (named != null) {
            composed = composed == null ? named : composed.andThen(named);
        }
        final SeatInputListener all = this.everySeat;
        if (all != null) {
            composed = composed == null ? all : composed.andThen(all);
        }
        return composed;
    }

    /**
     * Every index and name that was wired and that {@code plan} has no seat for.
     *
     * <p>A diagnostic rather than a refusal; see the class note. Worth logging once at start-up, because
     * a listener bound to a seat that does not exist is silently dead otherwise.
     *
     * @param plan the seats the vehicle actually has
     * @return the unmatched addresses, as text, in a stable order. Empty when everything matched
     */
    @Contract(pure = true)
    public @NotNull List<String> unmatched(@NotNull SeatPlan plan) {
        Objects.requireNonNull(plan, "plan");
        final List<String> missing = new ArrayList<>();
        this.byIndex.keySet().stream().sorted().forEach(index -> {
            if (!plan.hasIndex(index)) {
                missing.add("#" + index);
            }
        });
        this.byName.keySet().stream().sorted().forEach(name -> {
            if (plan.findSeat(name).isEmpty()) {
                missing.add("'" + name + "'");
            }
        });
        return List.copyOf(missing);
    }

    @Override
    public @NotNull String toString() {
        return "VehicleControls[" + this.byIndex.size() + " by index, " + this.byName.size()
                + " by name" + (this.everySeat == null ? "" : ", every seat") + "]";
    }

    /** Collects per-seat listeners. Not thread-safe; build one and hand it over. */
    public static final class Builder {

        private final @NotNull Map<Integer, SeatInputListener> byIndex = new HashMap<>();
        private final @NotNull Map<String, SeatInputListener> byName = new HashMap<>();
        private @Nullable SeatInputListener everySeat;

        private Builder() {
        }

        /**
         * Wires the seat at {@code seatIndex}.
         *
         * <p>Calling it twice for one index composes the two, for the reason the class note gives.
         *
         * @param seatIndex the seat's address. {@link SeatPlan#DRIVER_INDEX} is the driver's, whose
         *                  listener runs in addition to the steering rather than instead of it
         * @param listener  what its rider's inputs do
         * @return this builder
         */
        @Contract("_, _ -> this")
        public @NotNull Builder onSeat(int seatIndex, @NotNull SeatInputListener listener) {
            Objects.requireNonNull(listener, "listener");
            if (seatIndex < 0) {
                throw new IllegalArgumentException("A seat index addresses a position in the seat plan"
                        + " and must not be negative, but was " + seatIndex);
            }
            this.byIndex.merge(seatIndex, listener, SeatInputListener::andThen);
            return this;
        }

        /**
         * Wires the seat called {@code seatName}, matched case-insensitively.
         *
         * <p>The form to prefer when the rig is still being edited: a name survives the rigger
         * reordering the file, an index does not.
         *
         * @param seatName the seat's own id
         * @param listener what its rider's inputs do
         * @return this builder
         */
        @Contract("_, _ -> this")
        public @NotNull Builder onSeat(@NotNull String seatName, @NotNull SeatInputListener listener) {
            Objects.requireNonNull(listener, "listener");
            final String key = Objects.requireNonNull(seatName, "seatName").trim()
                    .toLowerCase(Locale.ROOT);
            if (key.isEmpty()) {
                throw new IllegalArgumentException("A seat name cannot be blank");
            }
            this.byName.merge(key, listener, SeatInputListener::andThen);
            return this;
        }

        /**
         * Wires the driver's seat, whichever index the rig put it at.
         *
         * <p>Which is always {@link SeatPlan#DRIVER_INDEX} today; the method exists so that a consumer
         * saying "the driver" does not have to know that.
         *
         * @param listener what the driver's inputs do, beyond steering
         * @return this builder
         */
        @Contract("_ -> this")
        public @NotNull Builder onDriverSeat(@NotNull SeatInputListener listener) {
            return onSeat(SeatPlan.DRIVER_INDEX, listener);
        }

        /**
         * Wires every seat, composed after any index or name binding.
         *
         * @param listener what every rider's inputs do
         * @return this builder
         */
        @Contract("_ -> this")
        public @NotNull Builder onEverySeat(@NotNull SeatInputListener listener) {
            Objects.requireNonNull(listener, "listener");
            this.everySeat = this.everySeat == null ? listener : this.everySeat.andThen(listener);
            return this;
        }

        /** The controls. */
        @Contract(value = "-> new", pure = true)
        public @NotNull VehicleControls build() {
            return new VehicleControls(this.byIndex, this.byName, this.everySeat);
        }
    }
}
