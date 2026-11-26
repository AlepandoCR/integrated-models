package pandastic.studio.models.internal.view;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The publication half of the viewer sampler: everything about getting a viewer's position from the
 * thread that may read it to the thread that decides from it, with no server involved.
 */
class ViewerIndexTest {

    private static final UUID WORLD = UUID.nameUUIDFromBytes("world".getBytes());

    private static ViewerSnapshot at(UUID player, double x, double z, long nanos) {
        // Through the factory, so the conversion the sampler performs is the one under test rather than
        // a hand-written unit vector that could never have come out of a yaw and a pitch.
        return ViewerSnapshot.of(player, WORLD, x, 64D, z, 0F, 0F, 32, nanos);
    }

    // =============================================================================================
    // Publication
    // =============================================================================================

    @Test
    void aPublishedSnapshotIsWhatAPassReads() {
        final ViewerIndex index = new ViewerIndex();
        final UUID player = UUID.randomUUID();
        final ViewerSnapshot published = at(player, 10D, 20D, 1L);

        assertNull(index.publish(published), "the first publication replaces nothing");

        assertAll(
                () -> assertSame(published, index.viewer(player),
                        "a reader must get the very object the sampler published, not a copy"),
                () -> assertEquals(1, index.size()),
                () -> assertSame(published, index.viewers().get(player)));
    }

    @Test
    void publishingAgainReplacesRatherThanAccumulates() {
        final ViewerIndex index = new ViewerIndex();
        final UUID player = UUID.randomUUID();
        final ViewerSnapshot first = at(player, 0D, 0D, 1L);
        final ViewerSnapshot second = at(player, 5D, 0D, 2L);

        index.publish(first);
        final ViewerSnapshot replaced = index.publish(second);

        assertAll(
                () -> assertSame(first, replaced, "the previous sample is handed back"),
                () -> assertSame(second, index.viewer(player)),
                // The property the whole design rests on: one entry per player, however many samples
                // that player's own region thread has taken.
                () -> assertEquals(1, index.size()));
    }

    @Test
    void severalViewersPublishIndependently() {
        final ViewerIndex index = new ViewerIndex();
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();

        index.publish(at(first, 0D, 0D, 1L));
        index.publish(at(second, 100D, 0D, 1L));

        assertAll(
                () -> assertEquals(2, index.size()),
                () -> assertEquals(0D, index.viewer(first).x()),
                () -> assertEquals(100D, index.viewer(second).x()));
    }

    @Test
    void anUnknownViewerIsAbsentRatherThanAnError() {
        final ViewerIndex index = new ViewerIndex();
        assertNull(index.viewer(UUID.randomUUID()));
    }

    // =============================================================================================
    // Forgetting, which is the half that never repairs itself if it is missed
    // =============================================================================================

    @Test
    void forgettingRemovesTheViewer() {
        final ViewerIndex index = new ViewerIndex();
        final UUID player = UUID.randomUUID();
        index.publish(at(player, 0D, 0D, 1L));

        assertAll(
                () -> assertTrue(index.forget(player)),
                () -> assertNull(index.viewer(player)),
                () -> assertEquals(0, index.size()));
    }

    @Test
    void forgettingSomebodyWhoWasNeverThereIsAnswerable() {
        // A quit for a player who never got a sample in — they joined and left inside one interval.
        assertFalse(new ViewerIndex().forget(UUID.randomUUID()));
    }

    @Test
    void clearingEmptiesEverything() {
        final ViewerIndex index = new ViewerIndex();
        index.publish(at(UUID.randomUUID(), 0D, 0D, 1L));
        index.publish(at(UUID.randomUUID(), 0D, 0D, 1L));

        index.clear();

        assertEquals(0, index.size());
    }

    // =============================================================================================
    // What a reader may and may not do
    // =============================================================================================

    @Test
    void aPassCannotPublishThroughTheViewItReads() {
        // The culler is handed this map. It has no business writing to it, and the type says so rather
        // than the documentation saying so.
        final ViewerIndex index = new ViewerIndex();
        final Map<UUID, ViewerSnapshot> viewing = index.viewers();
        final ViewerSnapshot snapshot = at(UUID.randomUUID(), 0D, 0D, 1L);

        assertThrows(UnsupportedOperationException.class,
                () -> viewing.put(snapshot.playerId(), snapshot));
    }

    @Test
    void theViewFollowsLaterPublicationsRatherThanBeingASnapshot() {
        // Deliberate, and the reason the accessor is not a copy: a pass reads it once per pass over
        // every player online, and copying it would put an allocation proportional to the player count
        // on the path whose entire purpose is to save work.
        final ViewerIndex index = new ViewerIndex();
        final Map<UUID, ViewerSnapshot> viewing = index.viewers();
        final UUID player = UUID.randomUUID();

        index.publish(at(player, 7D, 0D, 1L));

        assertEquals(7D, viewing.get(player).x());
    }

    // =============================================================================================
    // The conversion the sampler performs on the region thread
    // =============================================================================================

    @Test
    void theLookVectorIsBuiltFromTheYawTheSamplerRead() {
        // Yaw 0 faces +Z in Minecraft's convention, which is what the culler's bowtie is measured
        // against. Getting this wrong would cull exactly what a player is looking at, and it is checked
        // here rather than only in ViewerSnapshot's own suite because this is the path a sample takes.
        final ViewerIndex index = new ViewerIndex();
        final UUID player = UUID.randomUUID();
        index.publish(ViewerSnapshot.of(player, WORLD, 0D, 64D, 0D, 0F, 0F, 32, 1L));

        final ViewerSnapshot published = index.viewer(player);
        assertAll(
                () -> assertEquals(1D, published.lookZ(), 1.0E-9D),
                () -> assertEquals(0D, published.lookX(), 1.0E-9D),
                () -> assertEquals(0D, published.lookY(), 1.0E-9D));
    }
}
