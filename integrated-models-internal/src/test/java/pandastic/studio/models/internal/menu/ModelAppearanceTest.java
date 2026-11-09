package pandastic.studio.models.internal.menu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What an appearance refuses, and what it does with numbers that came off the wire.
 *
 * <p>The split is the whole subject. The constructor refuses an illegal colour because an appearance the
 * plugin assembles is a value it controls; {@link ModelAppearance#clamped} clamps one because a
 * submitted appearance is a packet and a client is free to say a channel is four thousand. A test that
 * only exercised one of the two would leave the other as the hole.
 */
class ModelAppearanceTest {

    @TempDir
    Path directory;

    private MenuSettings.Appearance bounds() {
        return MenuFixtures.defaults().appearance();
    }

    // ------------------------------------------------------------------ what it refuses

    @Test
    void aChannelOutsideTheByteIsRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ModelAppearance(false, 256, 0, 0, 1F, true, false)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ModelAppearance(false, 0, -1, 0, 1F, true, false)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ModelAppearance(false, 0, 0, 999, 1F, true, false)));
    }

    @Test
    void aZeroScaleIsRefusedBecauseItIsInvisibleRatherThanSmall() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModelAppearance(false, 1, 2, 3, 0F, true, false));
    }

    @Test
    void aNonFiniteScaleIsRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ModelAppearance(false, 1, 2, 3, Float.NaN, true, false)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ModelAppearance(false, 1, 2, 3,
                                Float.POSITIVE_INFINITY, true, false)));
    }

    // ------------------------------------------------------------------ the two derived forms

    @Test
    void theHexIsLowerCaseAndAlwaysSevenCharacters() {
        assertAll(
                () -> assertEquals("#000000",
                        new ModelAppearance(false, 0, 0, 0, 1F, true, false).hex()),
                () -> assertEquals("#ffffff",
                        new ModelAppearance(false, 255, 255, 255, 1F, true, false).hex()),
                () -> assertEquals("#0a14ff",
                        new ModelAppearance(false, 10, 20, 255, 1F, true, false).hex()));
    }

    @Test
    void thePackedColourPutsRedHighest() {
        assertEquals(0x0A14FF, new ModelAppearance(false, 10, 20, 255, 1F, true, false).packedRgb());
    }

    // ------------------------------------------------------------------ what arrives off the wire

    @Test
    void aSubmissionInsideTheRangeIsKept() {
        final ModelAppearance clamped =
                ModelAppearance.clamped(bounds(), true, 10F, 20F, 30F, 2F, false, true);
        assertAll(
                () -> assertEquals(10, clamped.red()),
                () -> assertEquals(20, clamped.green()),
                () -> assertEquals(30, clamped.blue()),
                () -> assertEquals(2F, clamped.scale()),
                () -> assertEquals(true, clamped.glow()),
                () -> assertEquals(false, clamped.physics()),
                () -> assertEquals(true, clamped.firstPersonArms()));
    }

    @Test
    void aChannelAClientInventedIsClampedRatherThanRefused() {
        // The slider bounds the client was handed are a hint to its interface, not a constraint on the
        // packet. Refusing this would turn a modified client into an exception in a click handler.
        final ModelAppearance clamped =
                ModelAppearance.clamped(bounds(), false, 4_000F, -4_000F, 128F, 1F, true, false);
        assertAll(
                () -> assertEquals(255, clamped.red()),
                () -> assertEquals(0, clamped.green()),
                () -> assertEquals(128, clamped.blue()));
    }

    @Test
    void aScaleAClientInventedIsClampedToTheConfiguredRange() {
        final MenuSettings.Appearance bounds = bounds();
        assertAll(
                () -> assertEquals(bounds.maxScale(),
                        ModelAppearance.clamped(bounds, false, 1F, 1F, 1F, 1_000F, true, false).scale()),
                () -> assertEquals(bounds.minScale(),
                        ModelAppearance.clamped(bounds, false, 1F, 1F, 1F, 0.001F, true, false).scale()));
    }

    @Test
    void aNonFiniteScaleFromAClientBecomesTheDefaultRatherThanAnException() {
        // Neither end of the range is nearer to NaN than the other, and every comparison against it is
        // false — so clamping cannot answer and the default is the only defensible reply.
        final MenuSettings.Appearance bounds = bounds();
        assertEquals(bounds.defaults().scale(),
                ModelAppearance.clamped(bounds, false, 1F, 1F, 1F, Float.NaN, true, false).scale());
    }

    @Test
    void aFractionalChannelIsRoundedRatherThanTruncated() {
        final ModelAppearance clamped =
                ModelAppearance.clamped(bounds(), false, 127.6F, 127.4F, 0.5F, 1F, true, false);
        assertAll(
                () -> assertEquals(128, clamped.red()),
                () -> assertEquals(127, clamped.green()),
                () -> assertEquals(1, clamped.blue()));
    }

    @Test
    void aNarrowedChannelRangeIsWhatASubmissionIsHeldTo() {
        // An operator forbidding near-black tints: the floor is theirs to raise, and a client sending
        // zero gets the floor rather than zero.
        final MenuSettings.Appearance bounds = MenuFixtures.fromFile(this.directory,
                "appearance.minChannel: 64",
                "appearance.maxChannel: 192",
                "appearance.defaultRed: 100",
                "appearance.defaultGreen: 100",
                "appearance.defaultBlue: 100").appearance();

        final ModelAppearance clamped =
                ModelAppearance.clamped(bounds, false, 0F, 255F, 128F, 1F, true, false);
        assertAll(
                () -> assertEquals(64, clamped.red()),
                () -> assertEquals(192, clamped.green()),
                () -> assertEquals(128, clamped.blue()));
    }
}
