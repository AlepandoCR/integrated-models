package pandastic.studio.models.internal.menu;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings screen: what the controls offer, and what Apply does with the answer.
 *
 * <p>The two assertions worth reading are about the halves of the original that did not survive. The
 * swatch shows the <em>applied</em> colour rather than the slider positions, because nothing tells the
 * server that a slider moved — so the test is that Apply moves it and a build does not. And a submitted
 * value is clamped rather than trusted, because the slider bounds the client was handed are a hint to
 * its interface and not a constraint on the packet.
 */
class AppearanceMenuTest {

    @TempDir
    Path directory;

    private final FakeMenuSurface surface = new FakeMenuSurface();
    private final FakeMenuPresenter presenter = new FakeMenuPresenter();
    private final FakeModelWardrobe wardrobe = new FakeModelWardrobe();
    private final SteppedClock clock = new SteppedClock();

    private Player viewer;

    @BeforeEach
    void setUp() {
        this.viewer = MenuFixtures.player("tester");
        this.wardrobe.dress(this.viewer, "dragon");
    }

    private MenuService service(String... lines) {
        final MenuSettings settings = MenuFixtures.fromFile(this.directory, lines);
        final MenuSettingsSource source = MenuSettingsSource.fixed(settings);
        return new MenuService(source, this.surface, new MenuSessions(source, this.clock),
                FakeModelCatalogue.of("dragon"), this.wardrobe, this.presenter);
    }

    private AppearanceMenu open(String... lines) {
        final MenuService service = service(lines);
        service.openAppearance(this.viewer);
        return this.presenter.last(AppearanceMenu.class);
    }

    /** Builds every control on the screen, as the dialog factory would. */
    private static void build(AppearanceMenu screen) {
        screen.heading();
        screen.noticeLine();
        screen.swatchLine();
        screen.swatchNote();
        screen.glowToggle();
        screen.redSlider();
        screen.greenSlider();
        screen.blueSlider();
        screen.scaleSlider();
        screen.physicsToggle();
        screen.armsToggle();
    }

    // ------------------------------------------------------------------ what the controls offer

    @Test
    void theColourSlidersOfferTheConfiguredRangeAndGranularity() {
        final AppearanceMenu screen = open(
                "appearance.minChannel: 16",
                "appearance.maxChannel: 240",
                "appearance.channelStep: 8",
                "appearance.defaultRed: 100",
                "appearance.defaultGreen: 100",
                "appearance.defaultBlue: 100");
        build(screen);

        final FakeMenuSurface.Slider red = this.surface.slider("red");
        assertAll(
                () -> assertEquals(16F, red.start()),
                () -> assertEquals(240F, red.end()),
                () -> assertEquals(8F, red.step()),
                () -> assertEquals(100F, red.initial()),
                () -> assertEquals("options.generic_value", red.labelFormat()));
    }

    @Test
    void theScaleSliderOffersTheConfiguredRange() {
        final AppearanceMenu screen = open(
                "appearance.minScale: 0.25",
                "appearance.maxScale: 8",
                "appearance.scaleStep: 0.25");
        build(screen);

        final FakeMenuSurface.Slider scale = this.surface.slider("scale");
        assertAll(
                () -> assertEquals(0.25F, scale.start()),
                () -> assertEquals(8F, scale.end()),
                () -> assertEquals(0.25F, scale.step()),
                () -> assertEquals(1F, scale.initial()));
    }

    @Test
    void aLabelFormatIsCarriedThroughVerbatimBecauseTheClientResolvesIt() {
        // It is a translation key, not a printf pattern. Rewriting it here would send the client a
        // missing key, which is displayed verbatim and looks like a plugin bug.
        final AppearanceMenu screen = open("appearance.channelLabelFormat: 'my.pack.channel'");
        build(screen);

        assertEquals("my.pack.channel", this.surface.slider("green").labelFormat());
    }

    @Test
    void everyControlExistsAndCarriesItsConfiguredLabel() {
        final AppearanceMenu screen = open(
                "appearance.glowLabel: 'Shine'",
                "appearance.physicsLabel: 'Wobble'",
                "appearance.firstPersonArmsLabel: 'Arms'");
        build(screen);

        assertAll(
                () -> assertEquals("Shine", FakeMenuSurface.plain(this.surface.toggle("glow").label())),
                () -> assertEquals("Wobble",
                        FakeMenuSurface.plain(this.surface.toggle("physics").label())),
                () -> assertEquals("Arms", FakeMenuSurface.plain(this.surface.toggle("arms").label())),
                () -> assertNotNull(this.surface.slider("red")),
                () -> assertNotNull(this.surface.slider("green")),
                () -> assertNotNull(this.surface.slider("blue")),
                () -> assertNotNull(this.surface.slider("scale")));
    }

    @Test
    void theControlsOpenAtWhatTheModelAlreadyLooksLike() {
        this.wardrobe.store(this.viewer, new ModelAppearance(true, 10, 20, 30, 2.5F, false, true));
        final AppearanceMenu screen = open();
        build(screen);

        assertAll(
                () -> assertEquals(true, this.surface.toggle("glow").initial()),
                () -> assertEquals(false, this.surface.toggle("physics").initial()),
                () -> assertEquals(true, this.surface.toggle("arms").initial()),
                () -> assertEquals(10F, this.surface.slider("red").initial()),
                () -> assertEquals(20F, this.surface.slider("green").initial()),
                () -> assertEquals(30F, this.surface.slider("blue").initial()),
                () -> assertEquals(2.5F, this.surface.slider("scale").initial()));
    }

    @Test
    void aStoredValueTheConfigurationNoLongerOffersIsClampedBeforeItReachesTheSlider() {
        // An operator who narrowed the range after a player had already picked a colour. An initial
        // value outside the range is refused by the client rather than displayed.
        this.wardrobe.store(this.viewer, new ModelAppearance(false, 255, 0, 128, 1F, true, false));
        final AppearanceMenu screen = open(
                "appearance.minChannel: 64",
                "appearance.maxChannel: 192",
                "appearance.defaultRed: 100",
                "appearance.defaultGreen: 100",
                "appearance.defaultBlue: 100");
        build(screen);

        assertAll(
                () -> assertEquals(192F, this.surface.slider("red").initial()),
                () -> assertEquals(64F, this.surface.slider("green").initial()),
                () -> assertEquals(128F, this.surface.slider("blue").initial()));
    }

    // ------------------------------------------------------------------ the swatch

    @Test
    void theSwatchAndTheHexDescribeTheSameColour() {
        this.wardrobe.store(this.viewer, new ModelAppearance(false, 10, 20, 255, 1F, true, false));
        final AppearanceMenu screen = open("appearance.swatchLine: '<swatch> <hex> <red>,<green>,<blue>'");
        screen.swatchLine();

        assertEquals(List.of("████ #0a14ff 10,20,255"), this.surface.lines());
    }

    @Test
    void theNoteAboutTheSwatchCanBeTurnedOffButIsThereByDefault() {
        assertAll(
                () -> assertNotNull(open().swatchNote()),
                () -> assertNull(open("appearance.swatchNote: ''").swatchNote()));
    }

    @Test
    void theSwatchOnlyMovesWhenApplyIsPressed() {
        // The platform gives no input-change notification, so this is the whole of what the screen can
        // promise: the colour on screen is the colour that is applied.
        final AppearanceMenu screen = open("appearance.swatchLine: '<hex>'");
        screen.swatchLine();
        assertEquals(List.of("#ffffff"), this.surface.lines());

        this.presenter.reset();
        this.surface.reset();
        screen.onApply(FakeClick.by(this.viewer)
                .with("red", 0F).with("green", 0F).with("blue", 0F));
        this.presenter.last(AppearanceMenu.class).swatchLine();

        assertEquals(List.of("#000000"), this.surface.lines());
    }

    // ------------------------------------------------------------------ the handlers

    @Test
    void applyingCommitsEveryControl() {
        final AppearanceMenu screen = open();
        this.presenter.reset();

        screen.onApply(FakeClick.by(this.viewer)
                .with("glow", true)
                .with("red", 1F)
                .with("green", 2F)
                .with("blue", 3F)
                .with("scale", 2F)
                .with("physics", false)
                .with("arms", true));

        final ModelAppearance applied = this.wardrobe.appearanceOf(this.viewer);
        assertAll(
                () -> assertNotNull(applied),
                () -> assertEquals(true, applied.glow()),
                () -> assertEquals(1, applied.red()),
                () -> assertEquals(2, applied.green()),
                () -> assertEquals(3, applied.blue()),
                () -> assertEquals(2F, applied.scale()),
                () -> assertEquals(false, applied.physics()),
                () -> assertEquals(true, applied.firstPersonArms()),
                () -> assertNotNull(this.presenter.last(AppearanceMenu.class)));
    }

    @Test
    void aSubmissionOutsideTheSlidersIsClampedRatherThanRefused() {
        final AppearanceMenu screen = open();
        this.presenter.reset();

        screen.onApply(FakeClick.by(this.viewer)
                .with("red", 9_000F).with("green", -9_000F).with("scale", 500F));

        final ModelAppearance applied = this.wardrobe.appearanceOf(this.viewer);
        assertAll(
                () -> assertNotNull(applied),
                () -> assertEquals(255, applied.red()),
                () -> assertEquals(0, applied.green()),
                () -> assertEquals(MenuFixtures.defaults().appearance().maxScale(), applied.scale()));
    }

    @Test
    void anInputTheClientDidNotSendKeepsWhatTheScreenWasShowing() {
        // An absent key means the client did not send that control, which is not the same as the player
        // asking for its default — substituting a default would quietly reset a channel.
        this.wardrobe.store(this.viewer, new ModelAppearance(true, 10, 20, 30, 2F, false, true));
        final AppearanceMenu screen = open();
        this.presenter.reset();

        screen.onApply(FakeClick.by(this.viewer).with("red", 99F));

        final ModelAppearance applied = this.wardrobe.appearanceOf(this.viewer);
        assertAll(
                () -> assertNotNull(applied),
                () -> assertEquals(99, applied.red()),
                () -> assertEquals(20, applied.green()),
                () -> assertEquals(30, applied.blue()),
                () -> assertEquals(2F, applied.scale()),
                () -> assertEquals(true, applied.glow()),
                () -> assertEquals(false, applied.physics()),
                () -> assertEquals(true, applied.firstPersonArms()));
    }

    @Test
    void resettingRestoresTheConfiguredDefaults() {
        this.wardrobe.store(this.viewer, new ModelAppearance(true, 1, 2, 3, 3F, false, true));
        final AppearanceMenu screen = open(
                "appearance.defaultRed: 11",
                "appearance.defaultGreen: 22",
                "appearance.defaultBlue: 33",
                "appearance.defaultScale: 1.5",
                "appearance.defaultGlow: true",
                "appearance.defaultPhysics: false",
                "appearance.defaultFirstPersonArms: true");
        this.presenter.reset();

        screen.onReset(FakeClick.by(this.viewer));

        final ModelAppearance applied = this.wardrobe.appearanceOf(this.viewer);
        assertAll(
                () -> assertNotNull(applied),
                () -> assertEquals(11, applied.red()),
                () -> assertEquals(22, applied.green()),
                () -> assertEquals(33, applied.blue()),
                () -> assertEquals(1.5F, applied.scale()),
                () -> assertEquals(true, applied.glow()),
                () -> assertEquals(false, applied.physics()),
                () -> assertEquals(true, applied.firstPersonArms()));
    }

    @Test
    void goingBackLandsOnThePicker() {
        final AppearanceMenu screen = open();
        this.presenter.reset();

        screen.onBack(FakeClick.by(this.viewer));

        assertNotNull(this.presenter.last(ModelPickerMenu.class));
    }

    @Test
    void applyingSaysSoOnTheNextScreen() {
        final AppearanceMenu screen = open("appearance.appliedMessage: 'Saved.'");
        this.presenter.reset();
        this.surface.reset();

        screen.onApply(FakeClick.by(this.viewer));
        this.presenter.last(AppearanceMenu.class).noticeLine();

        assertEquals(List.of("Saved."), this.surface.lines());
    }

    @Test
    void aClickFromSomebodyElseChangesNothing() {
        final AppearanceMenu screen = open();
        this.presenter.reset();

        screen.onApply(FakeClick.byStranger().with("red", 7F));
        screen.onReset(FakeClick.byStranger());

        assertAll(
                () -> assertNull(this.wardrobe.appearanceOf(this.viewer)),
                () -> assertEquals(0, this.presenter.count()));
    }

    @Test
    void openingItWithNothingWornLandsOnThePickerInstead() {
        // A form cannot be opened and then told it was the wrong one, so the refusal has to be the
        // screen that replaces it.
        final Player bare = MenuFixtures.player("bare");
        final MenuService service = service("appearance.noModelMessage: 'Wear something.'");
        service.openAppearance(bare);
        this.surface.reset();
        this.presenter.last(ModelPickerMenu.class).noticeLine();

        assertAll(
                () -> assertTrue(this.presenter.last() instanceof ModelPickerMenu),
                () -> assertEquals(List.of("Wear something."), this.surface.lines()));
    }
}
