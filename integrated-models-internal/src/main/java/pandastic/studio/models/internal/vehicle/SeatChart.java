package pandastic.studio.models.internal.vehicle;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.vehicle.SeatPlan;
import pandastic.studio.models.api.vehicle.VehicleSeat;

import java.util.List;
import java.util.Objects;

/**
 * Every seat a rig declared, and what each one turned out to be against the live model.
 *
 * <h2>Why the unusable seats are kept</h2>
 * Because they are the finding. A seat that resolved to a bone is a rigger pointing at a bone, and a seat
 * that resolved to nothing is a locator that was renamed, mistyped or never added — and a chart that
 * listed only the seats that worked would answer "how many seats has this vehicle" while silently
 * dropping the question the operator actually has. So the chart is the whole declaration, annotated, and
 * {@link #usableCount()} counts the subset riders are offered.
 *
 * <p><strong>Index order is load-bearing</strong>, and it is {@link SeatPlan}'s order: the driver's seat
 * first, then the rest in the order the rig file wrote them. That is the order riders are offered the
 * seats, so a chart sorted by anything else would stop describing the thing it reports on — and reading
 * the chart top to bottom is how an operator sees which seat the first player to board will get.
 *
 * <p>An unusable seat keeps its index rather than being skipped, which is why the numbers in a chart can
 * have a red line between them. Renumbering around a failed seat would move every seat behind it the
 * moment a rigger renamed a locator.
 *
 * @param entries one per declared seat, in index order
 */
public record SeatChart(@NotNull List<Entry> entries) {

    /** The chart of a rig that declares no seats, which is every rig that is not a vehicle. */
    public static final @NotNull SeatChart EMPTY = new SeatChart(List.of());

    public SeatChart {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    /** How many seats the rig declared, usable or not. */
    public int declared() {
        return this.entries.size();
    }

    /** How many seats resolved to a locator. */
    public int usableCount() {
        int count = 0;
        for (final Entry entry : this.entries) {
            if (entry.isUsable()) {
                count++;
            }
        }
        return count;
    }

    /**
     * How many declared seats nobody can sit on.
     *
     * <p>The figure the chart's severity turns on: above zero, the whole chart is reported as a warning
     * rather than as information, because a seat the rigger believes exists and nobody can use is a
     * finding and not a statistic.
     */
    public int unusableCount() {
        return declared() - usableCount();
    }

    /** Whether the rig declared no seats at all, in which case there is nothing to report. */
    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

    /**
     * One declared seat and its fate.
     *
     * @param seat the seat as the rig declared it — offsets and driver flag included — carrying the
     *             index boarding addresses it by
     * @param kind what its locator name turned out to name on the live model
     */
    public record Entry(@NotNull VehicleSeat seat, @NotNull SeatLocators.Kind kind) {

        public Entry {
            Objects.requireNonNull(seat, "seat");
            Objects.requireNonNull(kind, "kind");
        }

        /** Whether a rider may sit here. */
        public boolean isUsable() {
            return this.kind.isUsable();
        }
    }
}
