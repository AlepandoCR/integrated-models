package pandastic.studio.models.internal.menu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What the assembled menu settings refuse.
 *
 * <p>The record throws rather than clamping, which is the same split the rest of the project makes: a
 * rig file is written by a human and is clamped with a warning, while these values arrive from a
 * validator that has already refused the whole reload. Anything reaching these constructors out of range
 * is a defect in the mapper rather than operator input, and swallowing it would hide which of the two is
 * wrong.
 *
 * <p>The complaints an <em>operator</em> sees are asserted separately, in the configuration layer's own
 * tests, because those name a file and a key and these cannot.
 */
class MenuSettingsTest {

    /** The nineteen labels an appearance section needs, so a test can vary only the numbers. */
    private static MenuSettings.Appearance appearance(
            int minChannel,
            int maxChannel,
            float channelStep,
            float minScale,
            float maxScale,
            float scaleStep,
            ModelAppearance defaults
    ) {
        return new MenuSettings.Appearance(
                "title", "", "glow", "red", "green", "blue", "options.generic_value",
                "scale", "options.generic_value", "physics", "arms", "apply", "reset",
                "#", "<swatch>", "", "applied", "reset", "none",
                minChannel, maxChannel, channelStep, minScale, maxScale, scaleStep, defaults);
    }

    private static ModelAppearance white() {
        return new ModelAppearance(false, 255, 255, 255, 1F, true, false);
    }

    // ------------------------------------------------------------------ the session

    @Test
    void aTimeoutOfZeroIsRefusedBecauseNothingElseEndsASession() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new MenuSettings.Session(0F, 10)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new MenuSettings.Session(-1F, 10)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new MenuSettings.Session(Float.NaN, 10)));
    }

    @Test
    void aCeilingBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new MenuSettings.Session(60F, 0));
    }

    @Test
    void theTimeoutIsConvertedToTheUnitAMonotonicClockComparesIn() {
        assertEquals(1_500_000_000L, new MenuSettings.Session(1.5F, 10).timeoutNanos());
    }

    // ------------------------------------------------------------------ the layout

    @Test
    void aWidthOutsideWhatTheClientDrawsIsRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new MenuSettings.Layout(
                        0, 200, 150, "p", "n", "v", "b", "c")),
                () -> assertThrows(IllegalArgumentException.class, () -> new MenuSettings.Layout(
                        MenuSettings.MAX_ELEMENT_WIDTH + 1, 200, 150, "p", "n", "v", "b", "c")),
                () -> assertThrows(IllegalArgumentException.class, () -> new MenuSettings.Layout(
                        310, 0, 150, "p", "n", "v", "b", "c")),
                () -> assertThrows(IllegalArgumentException.class, () -> new MenuSettings.Layout(
                        310, 200, MenuSettings.MAX_ELEMENT_WIDTH + 1, "p", "n", "v", "b", "c")));
    }

    @Test
    void aBlankChromeLabelIsRefusedBecauseItWouldBeAnInvisibleButton() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new MenuSettings.Layout(
                        310, 200, 150, "p", "", "v", "b", "c")),
                () -> assertThrows(IllegalArgumentException.class, () -> new MenuSettings.Layout(
                        310, 200, 150, "p", "n", "v", "b", "   ")));
    }

    @Test
    void aNullLabelIsRefusedRatherThanThrowingALaterNullPointer() {
        // A generated configuration reads a text key through getString, which answers null for a key
        // present with no value. "closeLabel:" on its own line is an edit an operator can make.
        assertThrows(IllegalArgumentException.class,
                () -> new MenuSettings.Layout(310, 200, 150, "p", "n", "v", "b", null));
    }

    // ------------------------------------------------------------------ the sliders

    @Test
    void aChannelRangeWiderThanTheByteIsRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> appearance(-1, 255, 1F, 0.5F, 4F, 0.1F, white())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> appearance(0, 256, 1F, 0.5F, 4F, 0.1F, white())));
    }

    @Test
    void anEmptyOrInvertedChannelRangeIsRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> appearance(128, 128, 1F, 0.5F, 4F, 0.1F, white())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> appearance(200, 100, 1F, 0.5F, 4F, 0.1F, white())));
    }

    @Test
    void aStepLargerThanItsRangeIsRefusedBecauseTheHandleWouldHaveOnePosition() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> appearance(0, 10, 11F, 0.5F, 4F, 0.1F, white())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> appearance(0, 255, 1F, 1F, 2F, 1.5F, white())));
    }

    @Test
    void aZeroStepIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> appearance(0, 255, 0F, 0.5F, 4F, 0.1F, white()));
    }

    @Test
    void aScaleFloorOfZeroIsRefusedBecauseItIsInvisibleRatherThanSmall() {
        assertThrows(IllegalArgumentException.class,
                () -> appearance(0, 255, 1F, 0F, 4F, 0.1F, white()));
    }

    @Test
    void anInvertedScaleRangeIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> appearance(0, 255, 1F, 4F, 0.5F, 0.1F, white()));
    }

    @Test
    void aDefaultOutsideTheSlidersIsRefusedBecauseResetCouldNotBeUndone() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> appearance(
                        64, 192, 1F, 0.5F, 4F, 0.1F,
                        new ModelAppearance(false, 255, 100, 100, 1F, true, false))),
                () -> assertThrows(IllegalArgumentException.class, () -> appearance(
                        0, 255, 1F, 0.5F, 4F, 0.1F,
                        new ModelAppearance(false, 255, 255, 255, 8F, true, false))));
    }

    @Test
    void aValidAppearanceClampsBothWays() {
        final MenuSettings.Appearance appearance =
                appearance(64, 192, 1F, 0.5F, 4F, 0.1F, white2());
        assertAll(
                () -> assertEquals(64, appearance.clampChannel(0)),
                () -> assertEquals(192, appearance.clampChannel(255)),
                () -> assertEquals(100, appearance.clampChannel(100)),
                () -> assertEquals(0.5F, appearance.clampScale(0.1F)),
                () -> assertEquals(4F, appearance.clampScale(99F)),
                () -> assertEquals(2F, appearance.clampScale(2F)));
    }

    private static ModelAppearance white2() {
        return new ModelAppearance(false, 100, 100, 100, 1F, true, false);
    }

    // ------------------------------------------------------------------ the page sizes

    @Test
    void aPageSizeBelowOneIsRefusedOnBothPagedScreens() {
        final MenuSettings defaults = MenuFixtures.defaults();
        final MenuSettings.Picker picker = defaults.picker();
        final MenuSettings.Animation animation = defaults.animation();
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new MenuSettings.Picker(
                        picker.title(), picker.heading(), 0, picker.chooserLabel(),
                        picker.emptyMessage(), picker.wearLabel(), picker.wearTooltip(),
                        picker.removeLabel(), picker.removeTooltip(), picker.refreshLabel(),
                        picker.refreshTooltip(), picker.reloadLabel(), picker.reloadTooltip(),
                        picker.appearanceLabel(), picker.animationsLabel(), picker.wornLine(),
                        picker.nothingWornLine(), picker.unknownModelMessage(), picker.wornMessage(),
                        picker.removedMessage(), picker.reloadedMessage(), picker.thumbnailMaterial(),
                        picker.thumbnailItemModel(), picker.thumbnailWidth(), picker.thumbnailHeight(),
                        picker.thumbnailDescription())),
                () -> assertThrows(IllegalArgumentException.class, () -> new MenuSettings.Animation(
                        animation.title(), animation.heading(), 0, animation.emptyMessage(),
                        animation.entryLabel(), animation.entryTooltip(), animation.stopLabel(),
                        animation.stopTooltip(), animation.playedMessage(), animation.stoppedMessage(),
                        animation.unknownAnimationMessage(), animation.noModelMessage())));
    }

    @Test
    void anEmptyItemModelMeansNoPackSuppliedPicture() {
        // Blank is how an operator says "I have no resource pack", and the picker then shows the plain
        // material rather than asking for a model key nothing maps.
        assertAll(
                () -> assertEquals(true, MenuFixtures.defaults().picker().hasItemModel()),
                () -> assertEquals(false, blankItemModel().hasItemModel()));
    }

    private static MenuSettings.Picker blankItemModel() {
        final MenuSettings.Picker picker = MenuFixtures.defaults().picker();
        return new MenuSettings.Picker(
                picker.title(), picker.heading(), picker.pageSize(), picker.chooserLabel(),
                picker.emptyMessage(), picker.wearLabel(), picker.wearTooltip(),
                picker.removeLabel(), picker.removeTooltip(), picker.refreshLabel(),
                picker.refreshTooltip(), picker.reloadLabel(), picker.reloadTooltip(),
                picker.appearanceLabel(), picker.animationsLabel(), picker.wornLine(),
                picker.nothingWornLine(), picker.unknownModelMessage(), picker.wornMessage(),
                picker.removedMessage(), picker.reloadedMessage(), picker.thumbnailMaterial(),
                "", picker.thumbnailWidth(), picker.thumbnailHeight(), picker.thumbnailDescription());
    }
}
