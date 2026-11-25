package pandastic.studio.models.internal.vehicle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the seating layer's settings refuse, and why each refusal is not a tidiness check.
 *
 * <p>A file is validated by {@code ConfigMapper}, which clamps nothing and reports everything; this
 * record is the second line and exists for the programmatic caller. The two relationships it enforces
 * are the ones that would otherwise produce a seat that looks configured and never moves.
 */
class SeatSettingsTest {

    private static SeatSettings with(float maxCarrierStep, float deadband) {
        return new SeatSettings("ARMOR_STAND", 0F, maxCarrierStep, deadband, true,
                VehicleFixtures.chartLabels());
    }

    @Test
    void aBlankCarrierTypeIsRefusedBecauseItWouldMakeEverySeatUnoccupiable() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new SeatSettings("  ", 0F, 4F, 0.002F, true, VehicleFixtures.chartLabels()))
                .getMessage().contains("carrierEntityType"));
    }

    @Test
    void aCarrierTypeIsTrimmedSoAnOperatorsStraySpaceStillResolves() {
        assertEquals("ARMOR_STAND",
                new SeatSettings(" ARMOR_STAND ", 0F, 4F, 0.002F, true,
                        VehicleFixtures.chartLabels()).carrierEntityType());
    }

    @Test
    void aDeadbandAtOrAboveTheCarrierCeilingIsRefused() {
        // Not tidiness: above it every displacement is either ignored or teleported, so a carrier is
        // never moved in place at all — seats that stutter and a scheduled teleport per rider per tick.
        assertAll(
                () -> assertTrue(assertThrows(IllegalArgumentException.class, () -> with(1F, 1F))
                        .getMessage().contains("maxCarrierStepBlocks")),
                () -> assertThrows(IllegalArgumentException.class, () -> with(1F, 2F))
        );
    }

    @Test
    void aZeroDeadbandIsLegalAndMeansFollowEverything() {
        assertEquals(0F, with(1F, 0F).followDeadbandBlocks());
    }

    @Test
    void everyNonFiniteOrNonPositiveFigureIsRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> with(0F, 0F)),
                () -> assertThrows(IllegalArgumentException.class, () -> with(Float.NaN, 0F)),
                () -> assertThrows(IllegalArgumentException.class, () -> with(4F, -0.1F)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new SeatSettings("ARMOR_STAND", Float.POSITIVE_INFINITY, 4F, 0F, true,
                                VehicleFixtures.chartLabels()))
        );
    }

    @Test
    void aNegativeColumnGapIsRefused() {
        final SeatSettings.Chart labels = VehicleFixtures.chartLabels();
        assertThrows(IllegalArgumentException.class, () -> new SeatSettings.Chart(
                labels.top(), labels.title(), labels.rowPrefix(), labels.seatCell(),
                labels.locatorLabel(), labels.boneLabel(), labels.unresolvedLabel(),
                labels.driverLabel(), labels.passengerLabel(), labels.offsetCell(),
                labels.onLocatorLabel(), labels.summary(), labels.unusableNote(), labels.bottom(),
                -1));
    }
}
