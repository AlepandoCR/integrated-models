package pandastic.studio.models.internal.vehicle;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Everything the seating layer reads out of configuration, as one immutable value.
 *
 * <p>Carried inside {@link VehicleSettings} rather than fetched separately, so that one snapshot read per
 * control step covers the control loop and the seats alike. A step that read them from two sources could
 * straddle a reload and follow a locator with one generation's carrier type and another's step ceiling.
 *
 * <p>No component has a default here, for the reason the rest of this project's settings records give: a
 * default in a value type is the hardcoded tunable the type exists to remove. Every figure below arrives
 * from {@code vehicle.yml}.
 *
 * @param carrierEntityType     name of the entity type spawned as a rider's carrier, as the platform
 *                              spells it. A name rather than a resolved type because the mapper that
 *                              validates the file is a pure function and may not name a platform class;
 *                              the host resolves it when it spawns one, and an unknown name leaves the
 *                              seat unoccupied and reported rather than throwing inside a control step
 * @param carrierOffsetYBlocks  vertical trim applied to every carrier, in blocks. Each entity type sits
 *                              its passenger at its own height, so this is the one figure that has to
 *                              change when {@link #carrierEntityType()} does — it is not a seat offset,
 *                              which is the rigger's and lives in the rig file
 * @param maxCarrierStepBlocks  furthest a carrier may be moved in place in one step. Beyond it the
 *                              carrier is relocated through the platform's asynchronous teleport, which
 *                              is what a vehicle that just crossed a region boundary needs and what an
 *                              ordinary step must never pay for
 * @param followDeadbandBlocks  displacement below which a carrier is left alone, in blocks. A parked
 *                              vehicle whose idle clip breathes still moves its locators by a fraction of
 *                              a block, and writing that costs a position packet per rider per tick for
 *                              something nobody can see
 * @param logSeatChart          whether a vehicle logs its seat chart when it binds. The whole verbosity
 *                              switch: a server that does not want the chart turns this off and gets no
 *                              seat lines at all, including the ones about seats that failed to resolve
 * @param chart                 what every line of the chart says
 */
public record SeatSettings(
        @NotNull String carrierEntityType,
        float carrierOffsetYBlocks,
        float maxCarrierStepBlocks,
        float followDeadbandBlocks,
        boolean logSeatChart,
        @NotNull Chart chart
) {

    public SeatSettings {
        Objects.requireNonNull(chart, "chart");
        if (carrierEntityType.isBlank()) {
            throw new IllegalArgumentException("carrierEntityType must name an entity type; a blank"
                    + " name would make every seat on the server unoccupiable, which is a state no file"
                    + " can describe on purpose");
        }
        if (!Float.isFinite(carrierOffsetYBlocks)) {
            throw new IllegalArgumentException(
                    "carrierOffsetYBlocks must be finite but was " + carrierOffsetYBlocks);
        }
        if (!Float.isFinite(maxCarrierStepBlocks) || maxCarrierStepBlocks <= 0F) {
            throw new IllegalArgumentException("maxCarrierStepBlocks must be finite and positive but was "
                    + maxCarrierStepBlocks + "; at zero every single step would pay a teleport");
        }
        if (!Float.isFinite(followDeadbandBlocks) || followDeadbandBlocks < 0F) {
            // Zero is legal and means "follow every movement, however small"; negative names no distance.
            throw new IllegalArgumentException("followDeadbandBlocks must be finite and not negative but"
                    + " was " + followDeadbandBlocks);
        }
        if (followDeadbandBlocks >= maxCarrierStepBlocks) {
            // Not a tidiness check: with the deadband at or above the teleport threshold, every
            // displacement is either ignored or teleported and a carrier is never moved in place at all.
            throw new IllegalArgumentException("followDeadbandBlocks (" + followDeadbandBlocks
                    + ") must be below maxCarrierStepBlocks (" + maxCarrierStepBlocks + "), or a carrier"
                    + " is either left alone or teleported and never moved in place");
        }
        carrierEntityType = carrierEntityType.trim();
    }

    /**
     * Every label and glyph the seat chart is drawn from, as MiniMessage source.
     *
     * <p>Source rather than rendered components, for the reason
     * {@link pandastic.studio.models.internal.menu.MenuSettings} gives: most of these carry a placeholder
     * whose value only exists when a chart is built, so a component parsed at reload time would have to
     * be re-parsed anyway.
     *
     * <p>The column templates are deliberately <em>cells</em> rather than one row template. Alignment is
     * computed from the rendered plain width of each cell, which is the only way a column stays straight
     * when one operator writes {@code LOCATOR} and another writes {@code <green><bold>locator ok}; a
     * single row template would make the padding the operator's problem and it would be wrong on the
     * first colour tag.
     *
     * @param top           the line opening the box. Placeholder: {@code title}
     * @param title         what the box is about. Placeholders: {@code rig}, {@code model},
     *                      {@code declared}
     * @param rowPrefix     drawn at the start of every seat line, which is what makes the box a box
     * @param seatCell      the seat's own name. Placeholder: {@code seat}
     * @param locatorLabel  the state cell for a seat that resolved to a locator
     * @param boneLabel     the state cell for a seat that resolved to a bone — the rigger's mistake, and
     *                      the one label that has to be impossible to miss
     * @param unresolvedLabel the state cell for a name the model does not carry at all
     * @param driverLabel   the role cell for the seat the vehicle is steered from
     * @param passengerLabel the role cell for every other seat
     * @param offsetCell    the offset cell. Placeholders: {@code x}, {@code y}, {@code z}
     * @param onLocatorLabel the offset cell for a seat with no offset at all, which is the common case
     *                      and reads better as a word than as three zeroes
     * @param summary       the line closing the box. Placeholders: {@code usable}, {@code unusable},
     *                      {@code declared}
     * @param unusableNote  extra line emitted only when at least one seat is unusable, which is where the
     *                      operator is told what to do about it. Blank omits it
     * @param bottom        the line closing the box, after the summary. Blank omits it
     * @param columnGap     spaces between two columns, on top of the widest cell in the left one
     */
    public record Chart(
            @NotNull String top,
            @NotNull String title,
            @NotNull String rowPrefix,
            @NotNull String seatCell,
            @NotNull String locatorLabel,
            @NotNull String boneLabel,
            @NotNull String unresolvedLabel,
            @NotNull String driverLabel,
            @NotNull String passengerLabel,
            @NotNull String offsetCell,
            @NotNull String onLocatorLabel,
            @NotNull String summary,
            @NotNull String unusableNote,
            @NotNull String bottom,
            int columnGap
    ) {

        public Chart {
            Objects.requireNonNull(top, "top");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(rowPrefix, "rowPrefix");
            Objects.requireNonNull(seatCell, "seatCell");
            Objects.requireNonNull(locatorLabel, "locatorLabel");
            Objects.requireNonNull(boneLabel, "boneLabel");
            Objects.requireNonNull(unresolvedLabel, "unresolvedLabel");
            Objects.requireNonNull(driverLabel, "driverLabel");
            Objects.requireNonNull(passengerLabel, "passengerLabel");
            Objects.requireNonNull(offsetCell, "offsetCell");
            Objects.requireNonNull(onLocatorLabel, "onLocatorLabel");
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(unusableNote, "unusableNote");
            Objects.requireNonNull(bottom, "bottom");
            if (columnGap < 0) {
                throw new IllegalArgumentException(
                        "columnGap must not be negative but was " + columnGap);
            }
        }
    }
}
