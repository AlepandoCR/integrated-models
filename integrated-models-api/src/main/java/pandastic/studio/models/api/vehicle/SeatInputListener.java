package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.NotNull;

/**
 * What a rider's inputs do, decided by the consumer, per seat.
 *
 * <h2>Why per seat and not per vehicle</h2>
 * Because the seats of one vehicle are not interchangeable. A gunner aims, a driver steers, a passenger
 * on the back waves. The owner's requirement is exactly this: "decidir cómo se mueve cada uno con todos
 * los inputs mapeados" — every seat, every one of the twelve mapped inputs, and the decision belongs to
 * the plugin that built the vehicle rather than to this one.
 *
 * <p>What a consumer cannot do here is move the <em>steering</em>. Which seat drives is the rig's
 * statement — {@link VehicleSeat#isDriver()} — and a listener on the driver's seat runs <em>in addition
 * to</em> the steering rather than instead of it. That asymmetry is deliberate: the steering is what the
 * server is authoritative about, and a vehicle whose controlling seat could be reassigned at runtime
 * would have a different answer to "who is the authority" from one step to the next.
 *
 * <h2>Called once per control step, per occupied seat, on the vehicle's own thread</h2>
 * Not on an input event. A listener fires every step while somebody is in the seat, whether or not
 * anything changed, which is what a held key needs; {@link SeatInput#justPressed} is what a moment
 * needs. See {@link SeatInput} for the threading contract and for why the snapshot is handed over rather
 * than fetched.
 *
 * <p>Anything thrown is caught and reported by the vehicle layer, and the step carries on. A listener
 * that throws every step is a log line per step, which is loud on purpose — the alternative is a
 * listener nobody notices has stopped working.
 *
 * <h2>How this differs from {@link VehicleController}, and how they run together</h2>
 * This is the <strong>composing</strong> shape: a map from seat to function, where two listeners on one
 * seat both run and neither owns anything. {@link VehicleController} is the <strong>single-owner</strong>
 * shape, one object per kind of vehicle holding that kind's state in fields. A consumer may supply both,
 * and then for each seat this listener runs <em>first</em> and the controller second, with the same
 * {@link SeatInput} instance — specific before general, which is the order {@link VehicleControls}
 * already resolves a seat's own bindings in.
 *
 * <p>Their failure handling differs for the same reason their shapes do, and deliberately. A listener
 * holds no state, so one that throws costs that seat's reaction and nothing else and is reported every
 * time, forever. A controller holds invariants, so one that keeps throwing is throttled and eventually
 * detached from that vehicle: there is nothing useful left to call. Neither containment is the other's,
 * so a broken listener never costs the controller its turn.
 */
@FunctionalInterface
public interface SeatInputListener {

    /** A listener that does nothing, for a seat a consumer wants to name but not act on. */
    @NotNull SeatInputListener NONE = input -> {
    };

    /**
     * Reacts to what this seat's rider is asking for.
     *
     * @param input the rider, the seat and the input, all from this step
     */
    void onInput(@NotNull SeatInput input);

    /**
     * This listener, then {@code next}.
     *
     * <p>So that two consumers' concerns about one seat compose instead of one replacing the other.
     *
     * @param next what to run afterwards
     * @return the combined listener
     */
    default @NotNull SeatInputListener andThen(@NotNull SeatInputListener next) {
        return input -> {
            onInput(input);
            next.onInput(input);
        };
    }
}
