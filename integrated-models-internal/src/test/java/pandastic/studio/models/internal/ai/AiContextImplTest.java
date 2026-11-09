package pandastic.studio.models.internal.ai;

import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.ai.AiBounds;
import pandastic.studio.models.api.ai.AiContext;
import pandastic.studio.models.api.ai.AiIntent;
import pandastic.studio.models.api.ai.AiPlan;
import pandastic.studio.models.api.ai.AiPoint;
import pandastic.studio.models.api.ai.AiScan;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scanning, which is where the region asymmetry is dealt with, and the one piece of this layer where
 * getting it wrong produces a creature that is wrong only near a boundary.
 *
 * <h2>Why "truncated" and "empty" are tested as different answers</h2>
 * Because a behaviour that treats them as the same decides the creature is alone at exactly the moment
 * it is standing on a region boundary — which is to say at the moment most likely to matter and least
 * likely to be reproducible. The platform genuinely cannot answer across a boundary, so the only honest
 * thing the layer can do is narrow the question and say that it did.
 */
class AiContextImplTest {

    private final FakeAiHost host = new FakeAiHost();
    private final IntentHolder intents = new IntentHolder();
    private final AiPlan plan = AiFixtures.minimal("scout").build();

    private AiContext context() {
        return new AiContextImpl(this.host,
                new GuardedMemoryView(this.plan, new FakeMemoryStore(), this.host, AiFixtures.BOUNDS),
                this.intents, AiFixtures.BOUNDS);
    }

    @Test
    void aScanWithinBothLimitsIsComplete() {
        final AiScan<Entity> scan = context().scanEntities(5.0D);

        assertAll(
                () -> assertTrue(scan.complete()),
                () -> assertEquals(5.0D, scan.requestedRadius()),
                () -> assertEquals(5.0D, scan.scannedRadius()),
                () -> assertEquals(5.0D, this.host.lastScanRadius,
                        "the host must be asked for the radius the caller may have, not the raw one")
        );
    }

    @Test
    void theOperatorsCeilingNarrowsTheScanAndSaysSo() {
        // maxScanRadiusBlocks is 11 in the fixture. A plan asking for 200 does not get to walk the
        // world once per creature per period just because it asked.
        final AiScan<Entity> scan = context().scanEntities(200.0D);

        assertAll(
                () -> assertFalse(scan.complete()),
                () -> assertEquals(200.0D, scan.requestedRadius(),
                        "the requested radius must be the caller's figure, so provesEmptyWithin answers"
                                + " the question the caller actually asked"),
                () -> assertEquals(11.0D, scan.scannedRadius()),
                () -> assertEquals(11.0D, this.host.lastScanRadius)
        );
    }

    @Test
    void theRegionNarrowsTheScanAndSaysSo() {
        this.host.ownedRadiusBlocks = 4.0D;

        final AiScan<Entity> scan = context().scanEntities(10.0D);

        assertAll(
                () -> assertFalse(scan.complete()),
                () -> assertEquals(10.0D, scan.requestedRadius()),
                () -> assertEquals(4.0D, scan.scannedRadius()),
                () -> assertEquals(4.0D, this.host.lastScanRadius,
                        "the host must never be asked for a radius it said was not owned")
        );
    }

    @Test
    void aThreadThatOwnsNothingGetsAnUnavailableScanRatherThanAnEmptyOne() {
        this.host.ownedRadiusBlocks = AiHost.NOT_OWNED;

        final AiScan<Entity> scan = context().scanEntities(10.0D);

        assertAll(
                () -> assertFalse(scan.complete()),
                () -> assertEquals(0.0D, scan.scannedRadius()),
                () -> assertTrue(scan.isEmpty()),
                () -> assertFalse(scan.provesEmptyWithin(1.0D),
                        "an unavailable scan proves nothing at any radius"),
                () -> assertTrue(Double.isNaN(this.host.lastScanRadius),
                        "the query must not have been attempted at all")
        );
    }

    @Test
    void anEmptyCompleteScanProvesEmptinessAndATruncatedOneDoesNot() {
        // The distinction a behaviour deciding "nobody is watching, go back to idle" has to make.
        final AiScan<Entity> complete = context().scanEntities(8.0D);
        this.host.ownedRadiusBlocks = 2.0D;
        final AiScan<Entity> truncated = context().scanEntities(8.0D);

        assertAll(
                () -> assertTrue(complete.provesEmptyWithin(8.0D)),
                () -> assertFalse(truncated.provesEmptyWithin(8.0D)),
                () -> assertTrue(truncated.provesEmptyWithin(2.0D),
                        "a truncated scan still proves emptiness out to the radius it reached")
        );
    }

    @Test
    void playerScansObeyTheSameTwoLimits() {
        this.host.ownedRadiusBlocks = 3.0D;

        final AiScan<?> scan = context().scanPlayers(200.0D);

        assertAll(
                () -> assertEquals(3.0D, scan.scannedRadius()),
                () -> assertFalse(scan.complete())
        );
    }

    @Test
    void aNonsensicalRadiusIsRefusedRatherThanClamped() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> context().scanEntities(-1.0D)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> context().scanPlayers(Double.NaN))
        );
    }

    @Test
    void publishingAndReadingAnIntentGoThroughTheSameHolder() {
        final AiContext context = context();
        final AiIntent decision = AiIntent.moveTo(new AiPoint(1.0D, 2.0D, 3.0D), 1.0D);

        assertAll(
                () -> assertEquals(AiIntent.NONE, context.intent()),
                () -> {
                    context.publish(decision);
                    assertEquals(decision, context.intent());
                    assertEquals(decision, this.intents.current(),
                            "the context and whatever else reads the intent must be the same holder");
                }
        );
    }

    @Test
    void theContextReportsTheCreaturesOwnPositionAndClock() {
        this.host.position = new AiPoint(4.0D, 5.0D, 6.0D);
        this.host.gameTime = 1234L;

        final AiContext context = context();

        assertAll(
                () -> assertEquals(new AiPoint(4.0D, 5.0D, 6.0D), context.position()),
                () -> assertEquals(1234L, context.gameTime())
        );
    }

    @Test
    void theContextsBoundsAreFixedForTheInstallationsLife() {
        // Fixed rather than read live, so a body comparing two scans in one tick has them taken under
        // one ceiling. The fixture's ceiling is 11; a context built with a different one must keep it.
        final AiContext narrow = new AiContextImpl(this.host,
                new GuardedMemoryView(this.plan, new FakeMemoryStore(), this.host, AiFixtures.BOUNDS),
                this.intents,
                new AiBounds(3, 2, 7, 2, 2L, 40L, 2.0D, 500L, 8));

        assertEquals(2.0D, narrow.scanEntities(100.0D).scannedRadius());
    }
}
