package pandastic.studio.models.internal.menu;

import io.papermc.paper.registry.data.dialog.ActionButton;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model picker: what it offers, and what it does with the id a click brings back.
 *
 * <p>Two groups of assertions. The first is about construction — which entries, which thumbnail, which
 * buttons exist on a page — and is where the configuration is checked against deliberately odd values.
 * The second is about the handlers, and the one that matters most is that a submitted model id is
 * <strong>re-checked against the catalogue</strong>: it arrives in a packet the client composed, and
 * nothing else makes it a model.
 */
class ModelPickerMenuTest {

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
    }

    private MenuService service(FakeModelCatalogue catalogue, String... lines) {
        final MenuSettings settings = MenuFixtures.fromFile(this.directory, lines);
        final MenuSettingsSource source = MenuSettingsSource.fixed(settings);
        return new MenuService(source, this.surface, new MenuSessions(source, this.clock),
                catalogue, this.wardrobe, this.presenter);
    }

    private ModelPickerMenu open(FakeModelCatalogue catalogue, String... lines) {
        final MenuService service = service(catalogue, lines);
        service.openPicker(this.viewer);
        return this.presenter.last(ModelPickerMenu.class);
    }

    /** Builds every provider on the screen, as the dialog factory would. */
    private static void build(ModelPickerMenu screen) {
        screen.heading();
        screen.noticeLine();
        screen.wornLine();
        screen.preview();
        screen.emptyLine();
        screen.pageLine();
        screen.chooser();
    }

    /** The fake behind an unfinished button, which is what a test reads a label off. */
    private static FakeMenuSurface.Builder builder(ActionButton.Builder unfinished) {
        return (FakeMenuSurface.Builder) unfinished;
    }

    // ------------------------------------------------------------------ what a page offers

    @Test
    void aPageOffersExactlyTheConfiguredNumberOfModels() {
        final ModelPickerMenu screen = open(FakeModelCatalogue.sized(10), "picker.pageSize: 3");
        screen.chooser();

        assertEquals(List.of("model1", "model2", "model3"), this.surface.chooser("model").ids());
    }

    @Test
    void theSecondPageOffersTheNextModels() {
        final MenuService service = service(FakeModelCatalogue.sized(10), "picker.pageSize: 3");
        service.openPicker(this.viewer);
        final ModelPickerMenu first = this.presenter.last(ModelPickerMenu.class);
        this.surface.reset();

        first.onNextPage(FakeClick.by(this.viewer));
        this.presenter.last(ModelPickerMenu.class).chooser();

        assertEquals(List.of("model4", "model5", "model6"), this.surface.chooser("model").ids());
    }

    @Test
    void theChooserOpensOnTheModelTheThumbnailShows() {
        this.wardrobe.dress(this.viewer, "model5");
        final ModelPickerMenu screen = open(FakeModelCatalogue.sized(10), "picker.pageSize: 10");
        screen.chooser();
        screen.preview();

        assertAll(
                () -> assertEquals("model5", this.surface.chooser("model").initialId()),
                () -> assertEquals("integrated_models:thumbnail/model5",
                        this.surface.thumbnails().getFirst().itemModelKey()));
    }

    @Test
    void theThumbnailFallsBackToTheFirstEntryWhenNothingIsWornOrSelected() {
        final ModelPickerMenu screen = open(FakeModelCatalogue.sized(4), "picker.pageSize: 2");
        screen.preview();

        assertEquals("integrated_models:thumbnail/model1",
                this.surface.thumbnails().getFirst().itemModelKey());
    }

    @Test
    void aConfiguredThumbnailIsHonouredDownToItsMaterialAndSize() {
        final ModelPickerMenu screen = open(FakeModelCatalogue.of("dragon"),
                "picker.thumbnailMaterial: PAINTING",
                "picker.thumbnailItemModel: 'packs:preview/<model>'",
                "picker.thumbnailWidth: 48",
                "picker.thumbnailHeight: 72",
                "picker.thumbnailDescription: 'Model <model>'");
        screen.preview();

        final FakeMenuSurface.Item thumbnail = this.surface.thumbnails().getFirst();
        assertAll(
                () -> assertEquals("PAINTING", thumbnail.materialName()),
                () -> assertEquals("packs:preview/dragon", thumbnail.itemModelKey()),
                () -> assertEquals(48, thumbnail.width()),
                () -> assertEquals(72, thumbnail.height()),
                () -> assertEquals("Model dragon", FakeMenuSurface.plain(thumbnail.caption())));
    }

    @Test
    void anEmptyItemModelLeavesThePlainItem() {
        final ModelPickerMenu screen =
                open(FakeModelCatalogue.of("dragon"), "picker.thumbnailItemModel: ''");
        screen.preview();

        assertNull(this.surface.thumbnails().getFirst().itemModelKey());
    }

    @Test
    void aModelIdIsSubstitutedLiterallyRatherThanAsMarkup() {
        // A model id is a file name and a file can be called anything. Substituting it as MiniMessage
        // would let a file name decide what the rest of the line looks like.
        final ModelPickerMenu screen = open(FakeModelCatalogue.of("<red>oops"),
                "picker.thumbnailDescription: '<gray>Model <model>'");
        screen.preview();

        assertEquals("Model <red>oops",
                FakeMenuSurface.plain(this.surface.thumbnails().getFirst().caption()));
    }

    @Test
    void theWidthsEveryElementUsesComeFromTheConfiguration() {
        final ModelPickerMenu screen = open(FakeModelCatalogue.of("dragon"),
                "layout.bodyWidth: 123",
                "layout.inputWidth: 77",
                "layout.buttonWidth: 42");
        build(screen);

        assertAll(
                () -> assertEquals(123, this.surface.messages().getFirst().width()),
                () -> assertEquals(77, this.surface.chooser("model").width()),
                () -> assertEquals(42, builder(screen.wearButton()).width()));
    }

    // ------------------------------------------------------------------ conditional rendering

    @Test
    void anEmptyCatalogueOffersNoChooserAndNoWearButton() {
        final ModelPickerMenu screen = open(FakeModelCatalogue.sized(0));

        assertAll(
                () -> assertNull(screen.chooser()),
                () -> assertNull(screen.wearButton()),
                () -> assertNull(screen.refreshButton()),
                () -> assertNotNull(screen.emptyLine()),
                () -> assertNotNull(screen.reloadButton()));
    }

    @Test
    void theButtonsThatNeedAWornModelAreAbsentWithoutOne() {
        final ModelPickerMenu screen = open(FakeModelCatalogue.of("dragon"));

        assertAll(
                () -> assertNull(screen.removeButton()),
                () -> assertNull(screen.appearanceButton()),
                () -> assertNull(screen.animationsButton()));
    }

    @Test
    void theButtonsThatNeedAWornModelAppearWithOne() {
        this.wardrobe.dress(this.viewer, "dragon");
        final ModelPickerMenu screen = open(FakeModelCatalogue.of("dragon"));

        assertAll(
                () -> assertNotNull(screen.removeButton()),
                () -> assertNotNull(screen.appearanceButton()),
                () -> assertNotNull(screen.animationsButton()));
    }

    @Test
    void thePageIndicatorAndNavigationOnlyExistWhenThereIsMoreThanOnePage() {
        final ModelPickerMenu single = open(FakeModelCatalogue.sized(2), "picker.pageSize: 8");
        assertAll(
                () -> assertNull(single.pageLine()),
                () -> assertNull(single.nextPageButton()),
                () -> assertNull(single.previousPageButton()));

        final ModelPickerMenu paged = open(FakeModelCatalogue.sized(20), "picker.pageSize: 8");
        assertAll(
                () -> assertNotNull(paged.pageLine()),
                () -> assertNotNull(paged.nextPageButton()),
                () -> assertNull(paged.previousPageButton()));
    }

    @Test
    void thePageIndicatorCountsFromOne() {
        final ModelPickerMenu screen = open(FakeModelCatalogue.sized(20), "picker.pageSize: 8");
        screen.pageLine();

        assertTrue(this.surface.lines().contains("Page 1 of 3"), this.surface.lines().toString());
    }

    @Test
    void aMissingMaterialLosesThePictureAndNotTheScreen() {
        this.surface.breakMaterial();
        final ModelPickerMenu screen = open(FakeModelCatalogue.of("dragon"));

        assertAll(
                () -> assertNull(screen.preview()),
                () -> assertNotNull(screen.chooser()),
                () -> assertNotNull(screen.wearButton()));
    }

    // ------------------------------------------------------------------ the body lines

    @Test
    void theWornLineNamesTheModelAndIsReplacedWhenThereIsNone() {
        final ModelPickerMenu bare = open(FakeModelCatalogue.of("dragon"));
        bare.wornLine();
        assertEquals(List.of("Wearing nothing."), this.surface.lines());

        this.surface.reset();
        this.wardrobe.dress(this.viewer, "dragon");
        final ModelPickerMenu dressed = open(FakeModelCatalogue.of("dragon"));
        dressed.wornLine();
        assertEquals(List.of("Wearing: dragon"), this.surface.lines());
    }

    @Test
    void aConfiguredLabelIsWhatTheScreenCarries() {
        final ModelPickerMenu screen = open(FakeModelCatalogue.of("dragon"),
                "picker.wearLabel: 'Put it on'",
                "picker.wearTooltip: ''",
                "picker.heading: ''");

        assertAll(
                () -> assertEquals("Put it on", builder(screen.wearButton()).text()),
                () -> assertNull(builder(screen.wearButton()).tooltip()),
                () -> assertNull(screen.heading()));
    }

    // ------------------------------------------------------------------ the handlers

    @Test
    void wearingAKnownModelAppliesItAndReopensThePicker() {
        final ModelPickerMenu screen = open(FakeModelCatalogue.of("dragon", "wyvern"));
        this.presenter.reset();

        screen.onWear(FakeClick.by(this.viewer).with("model", "wyvern"));

        assertAll(
                () -> assertEquals("wyvern", this.wardrobe.wornBy(this.viewer)),
                () -> assertEquals(1, this.presenter.count()),
                () -> assertNotNull(this.presenter.last(ModelPickerMenu.class)));
    }

    @Test
    void wearingAnIdTheCatalogueDoesNotOfferChangesNothing() {
        // The id came off the wire. A modified client can submit anything; this check is the only thing
        // standing between it and a model operation.
        final ModelPickerMenu screen = open(FakeModelCatalogue.of("dragon"));
        this.presenter.reset();

        screen.onWear(FakeClick.by(this.viewer).with("model", "../../etc/passwd"));

        assertAll(
                () -> assertNull(this.wardrobe.wornBy(this.viewer)),
                () -> assertEquals(1, this.presenter.count()));
    }

    @Test
    void theRefusalIsTheNextScreensNoticeLine() {
        final ModelPickerMenu screen = open(FakeModelCatalogue.of("dragon"));
        this.presenter.reset();
        this.surface.reset();

        screen.onWear(FakeClick.by(this.viewer).with("model", "ghost"));
        this.presenter.last(ModelPickerMenu.class).noticeLine();

        assertEquals(List.of("That model is not available: ghost"), this.surface.lines());
    }

    @Test
    void aModelRemovedBetweenTheBuildAndTheClickIsRefusedRatherThanWorn() {
        final FakeModelCatalogue catalogue = FakeModelCatalogue.of("dragon", "wyvern");
        final ModelPickerMenu screen = open(catalogue);
        catalogue.replaceModels("dragon");
        this.presenter.reset();

        screen.onWear(FakeClick.by(this.viewer).with("model", "wyvern"));

        assertNull(this.wardrobe.wornBy(this.viewer));
    }

    @Test
    void aClickWithNoSelectionJustReopensThePicker() {
        final ModelPickerMenu screen = open(FakeModelCatalogue.of("dragon"));
        this.presenter.reset();

        screen.onWear(FakeClick.by(this.viewer));

        assertAll(
                () -> assertNull(this.wardrobe.wornBy(this.viewer)),
                () -> assertEquals(1, this.presenter.count()));
    }

    @Test
    void thePreviewButtonIsHowTheThumbnailFollowsTheChooser() {
        // The server is never told that an input changed, so a selection only reaches the thumbnail by
        // way of a click. This is that click.
        final ModelPickerMenu screen = open(FakeModelCatalogue.sized(4), "picker.pageSize: 4");
        this.presenter.reset();
        this.surface.reset();

        screen.onRefresh(FakeClick.by(this.viewer).with("model", "model3"));
        this.presenter.last(ModelPickerMenu.class).preview();

        assertEquals("integrated_models:thumbnail/model3",
                this.surface.thumbnails().getFirst().itemModelKey());
    }

    @Test
    void removingTakesTheModelOffAndSaysSo() {
        this.wardrobe.dress(this.viewer, "dragon");
        final ModelPickerMenu screen = open(FakeModelCatalogue.of("dragon"));
        this.presenter.reset();
        this.surface.reset();

        screen.onRemove(FakeClick.by(this.viewer));
        this.presenter.last(ModelPickerMenu.class).noticeLine();

        assertAll(
                () -> assertNull(this.wardrobe.wornBy(this.viewer)),
                () -> assertEquals(1, this.wardrobe.removals()),
                () -> assertEquals(List.of("Model removed."), this.surface.lines()));
    }

    @Test
    void reloadingReReadsTheCatalogueAndReportsWhatIsAvailable() {
        final FakeModelCatalogue catalogue = FakeModelCatalogue.sized(3);
        final ModelPickerMenu screen = open(catalogue);
        this.presenter.reset();
        this.surface.reset();

        screen.onReload(FakeClick.by(this.viewer));
        this.presenter.last(ModelPickerMenu.class).noticeLine();

        assertAll(
                () -> assertEquals(1, catalogue.reloads()),
                () -> assertEquals(List.of("Reloaded: 3 model(s) available."), this.surface.lines()));
    }

    @Test
    void aPageNumberLeftOverFromALargerCatalogueIsClampedRatherThanFailing() {
        final FakeModelCatalogue catalogue = FakeModelCatalogue.sized(20);
        final MenuService service = service(catalogue, "picker.pageSize: 4");
        service.openPicker(this.viewer);

        this.presenter.last(ModelPickerMenu.class).onNextPage(FakeClick.by(this.viewer));
        this.presenter.last(ModelPickerMenu.class).onNextPage(FakeClick.by(this.viewer));
        this.presenter.last(ModelPickerMenu.class).onNextPage(FakeClick.by(this.viewer));

        catalogue.replaceModels("only");
        this.surface.reset();
        service.openPicker(this.viewer);
        this.presenter.last(ModelPickerMenu.class).chooser();

        assertEquals(List.of("only"), this.surface.chooser("model").ids());
    }

    @Test
    void steppingIntoAnotherScreenKeepsTheSelection() {
        this.wardrobe.dress(this.viewer, "model1");
        final MenuService service = service(FakeModelCatalogue.sized(4), "picker.pageSize: 4");
        service.openPicker(this.viewer);
        final ModelPickerMenu screen = this.presenter.last(ModelPickerMenu.class);

        screen.onAnimations(FakeClick.by(this.viewer).with("model", "model3"));
        this.surface.reset();
        service.openPicker(this.viewer);
        this.presenter.last(ModelPickerMenu.class).chooser();

        assertEquals("model3", this.surface.chooser("model").initialId());
    }

    @Test
    void closingDropsTheSessionSoTheNextOpenStartsOnPageOne() {
        final MenuSettings settings = MenuFixtures.fromFile(this.directory, "picker.pageSize: 4");
        final MenuSettingsSource source = MenuSettingsSource.fixed(settings);
        final MenuSessions sessions = new MenuSessions(source, this.clock);
        final MenuService service = new MenuService(source, this.surface, sessions,
                FakeModelCatalogue.sized(20), this.wardrobe, this.presenter);

        service.openPicker(this.viewer);
        this.presenter.last(ModelPickerMenu.class).onNextPage(FakeClick.by(this.viewer));
        assertEquals(1, sessions.open(this.viewer.getUniqueId()).pickerPage());

        this.presenter.last(ModelPickerMenu.class).onClose(FakeClick.by(this.viewer));
        assertAll(
                () -> assertTrue(sessions.find(this.viewer.getUniqueId()).isEmpty()),
                () -> assertEquals(0, sessions.open(this.viewer.getUniqueId()).pickerPage()));
    }

    @Test
    void aClickFromSomebodyElseIsIgnored() {
        // Defence in depth: a callback is registered per build and only reachable by its recipient, so
        // this should be unreachable. It costs one reference comparison to keep it that way.
        this.wardrobe.dress(this.viewer, "dragon");
        final ModelPickerMenu screen = open(FakeModelCatalogue.of("dragon", "wyvern"));
        this.presenter.reset();

        screen.onWear(FakeClick.byStranger().with("model", "wyvern"));
        screen.onRemove(FakeClick.byStranger());

        assertAll(
                () -> assertEquals("dragon", this.wardrobe.wornBy(this.viewer)),
                () -> assertEquals(0, this.wardrobe.removals()),
                () -> assertEquals(0, this.presenter.count()));
    }

    @Test
    void theScreenIsBuiltForTheViewerItWasOpenedFor() {
        final MenuService service = service(FakeModelCatalogue.of("dragon"));
        service.openPicker(this.viewer);

        assertSame(this.viewer, this.presenter.shown().getFirst().viewer());
    }

    @Test
    void aNoticeIsShownOnceAndNotOnTheScreenAfterThat() {
        final ModelPickerMenu screen = open(FakeModelCatalogue.of("dragon"));
        this.presenter.reset();

        screen.onRemove(FakeClick.by(this.viewer));
        final ModelPickerMenu afterRemove = this.presenter.last(ModelPickerMenu.class);
        assertNotNull(afterRemove.noticeLine());

        this.presenter.reset();
        afterRemove.onNextPage(FakeClick.by(this.viewer));
        assertFalse(this.presenter.shown().isEmpty());
        assertNull(this.presenter.last(ModelPickerMenu.class).noticeLine());
    }
}
