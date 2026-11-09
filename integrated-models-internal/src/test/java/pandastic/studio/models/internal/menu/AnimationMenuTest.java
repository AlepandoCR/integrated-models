package pandastic.studio.models.internal.menu;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The animation chooser: a button per animation, and what happens when one is pressed.
 *
 * <p>This is the screen whose buttons cannot be annotated — there is one per animation read off disk, at
 * a count that is a configured page size, and {@code @DialogButtonHandler} pairs a handler with an id
 * written in source. So the list buttons carry their own action, and these tests invoke that action
 * directly, which is the same thing a click does.
 */
class AnimationMenuTest {

    @TempDir
    Path directory;

    private final FakeMenuSurface surface = new FakeMenuSurface();
    private final FakeMenuPresenter presenter = new FakeMenuPresenter();
    private final FakeModelWardrobe wardrobe = new FakeModelWardrobe();
    private final SteppedClock clock = new SteppedClock();

    private Player viewer;
    private FakeModelCatalogue catalogue;

    @BeforeEach
    void setUp() {
        this.viewer = MenuFixtures.player("tester");
        this.catalogue = FakeModelCatalogue.of("dragon")
                .withAnimations("dragon", "idle", "walk", "roar", "fly", "land");
        this.wardrobe.dress(this.viewer, "dragon");
    }

    private MenuService service(String... lines) {
        final MenuSettings settings = MenuFixtures.fromFile(this.directory, lines);
        final MenuSettingsSource source = MenuSettingsSource.fixed(settings);
        return new MenuService(source, this.surface, new MenuSessions(source, this.clock),
                this.catalogue, this.wardrobe, this.presenter);
    }

    private AnimationMenu open(String... lines) {
        final MenuService service = service(lines);
        service.openAnimations(this.viewer);
        return this.presenter.last(AnimationMenu.class);
    }

    /** The labels of the list buttons, which is what a pagination assertion reads. */
    private List<String> choiceLabels() {
        final List<String> labels = new ArrayList<>();
        for (final FakeMenuSurface.Button button : this.surface.actionButtons()) {
            labels.add(button.text());
        }
        return labels;
    }

    // ------------------------------------------------------------------ what a page offers

    @Test
    void aPageOffersOneButtonPerAnimation() {
        final AnimationMenu screen = open("animation.pageSize: 3");
        screen.choiceButtons();

        assertEquals(List.of("idle", "walk", "roar"), choiceLabels());
    }

    @Test
    void theSecondPageOffersTheRest() {
        final MenuService service = service("animation.pageSize: 3");
        service.openAnimations(this.viewer);
        final AnimationMenu first = this.presenter.last(AnimationMenu.class);
        this.surface.reset();

        first.onNextPage(FakeClick.by(this.viewer));
        this.presenter.last(AnimationMenu.class).choiceButtons();

        assertEquals(List.of("fly", "land"), choiceLabels());
    }

    @Test
    void theLabelAndTheTooltipAreConfiguredTemplates() {
        final AnimationMenu screen = open(
                "animation.pageSize: 1",
                "animation.entryLabel: '▶ <animation>'",
                "animation.entryTooltip: 'Start <animation> now'",
                "layout.buttonWidth: 64");
        screen.choiceButtons();

        final FakeMenuSurface.Button button = this.surface.actionButtons().getFirst();
        assertAll(
                () -> assertEquals("▶ idle", button.text()),
                () -> assertEquals("Start idle now", FakeMenuSurface.plain(button.tooltip())),
                () -> assertEquals(64, button.width()));
    }

    @Test
    void anEmptyTooltipMeansNoTooltip() {
        final AnimationMenu screen = open("animation.pageSize: 1", "animation.entryTooltip: ''");
        screen.choiceButtons();

        assertNull(this.surface.actionButtons().getFirst().tooltip());
    }

    @Test
    void anAnimationNameIsSubstitutedLiterallyRatherThanAsMarkup() {
        this.catalogue = FakeModelCatalogue.of("dragon").withAnimations("dragon", "<bold>odd");
        final AnimationMenu screen = open("animation.entryLabel: '<white><animation>'");
        screen.choiceButtons();

        assertEquals("<bold>odd", this.surface.actionButtons().getFirst().text());
    }

    @Test
    void aModelWithNoAnimationsOffersNoButtonsAndSaysSo() {
        this.catalogue = FakeModelCatalogue.of("dragon");
        final AnimationMenu screen = open();

        assertAll(
                () -> assertNull(screen.choiceButtons()),
                () -> assertNotNull(screen.emptyLine()),
                () -> assertNotNull(screen.stopButton()));
    }

    @Test
    void thePageIndicatorAndNavigationOnlyExistWhenThereIsMoreThanOnePage() {
        final AnimationMenu single = open("animation.pageSize: 8");
        assertAll(
                () -> assertNull(single.pageLine()),
                () -> assertNull(single.nextPageButton()),
                () -> assertNull(single.previousPageButton()));

        final AnimationMenu paged = open("animation.pageSize: 2");
        assertAll(
                () -> assertNotNull(paged.pageLine()),
                () -> assertNotNull(paged.nextPageButton()),
                () -> assertNull(paged.previousPageButton()));
    }

    // ------------------------------------------------------------------ the handlers

    @Test
    void pressingAButtonPlaysTheAnimationItNames() {
        final AnimationMenu screen = open("animation.pageSize: 5");
        screen.choiceButtons();
        this.presenter.reset();

        click("roar");

        assertAll(
                () -> assertEquals("roar", this.wardrobe.animationOf(this.viewer)),
                () -> assertNotNull(this.presenter.last(AnimationMenu.class)));
    }

    @Test
    void pressingAButtonSaysWhatItStarted() {
        final AnimationMenu screen = open(
                "animation.pageSize: 5", "animation.playedMessage: 'Now: <animation>'");
        screen.choiceButtons();
        this.presenter.reset();
        final List<FakeMenuSurface.Button> buttons = this.surface.actionButtons();
        this.surface.reset();

        buttons.get(1).onClick().onClick(FakeClick.by(this.viewer));
        this.presenter.last(AnimationMenu.class).noticeLine();

        assertEquals(List.of("Now: walk"), this.surface.lines());
    }

    @Test
    void anAnimationTheModelNoLongerDeclaresIsRefusedRatherThanPlayed() {
        // The list this screen was built from is not the list that matters at click time: a reload, or a
        // model swapped in another window of the same session, can take the animation away.
        final AnimationMenu screen = open("animation.pageSize: 5");
        screen.choiceButtons();
        final List<FakeMenuSurface.Button> buttons = this.surface.actionButtons();
        this.catalogue.withAnimations("dragon", "idle");
        this.presenter.reset();
        this.surface.reset();

        buttons.get(2).onClick().onClick(FakeClick.by(this.viewer));
        this.presenter.last(AnimationMenu.class).noticeLine();

        assertAll(
                () -> assertNull(this.wardrobe.animationOf(this.viewer)),
                () -> assertEquals(List.of("That animation is not available: roar"),
                        this.surface.lines()));
    }

    @Test
    void pressingAButtonAfterTheModelWasRemovedLandsOnThePicker() {
        final AnimationMenu screen = open("animation.pageSize: 5");
        screen.choiceButtons();
        final List<FakeMenuSurface.Button> buttons = this.surface.actionButtons();
        this.wardrobe.remove(this.viewer);
        this.presenter.reset();

        buttons.getFirst().onClick().onClick(FakeClick.by(this.viewer));

        assertAll(
                () -> assertNull(this.wardrobe.animationOf(this.viewer)),
                () -> assertTrue(this.presenter.last() instanceof ModelPickerMenu));
    }

    @Test
    void stoppingStopsAndSaysSo() {
        final AnimationMenu screen = open("animation.stoppedMessage: 'Stopped.'");
        this.wardrobe.playAnimation(this.viewer, "roar");
        this.presenter.reset();
        this.surface.reset();

        screen.onStop(FakeClick.by(this.viewer));
        this.presenter.last(AnimationMenu.class).noticeLine();

        assertAll(
                () -> assertNull(this.wardrobe.animationOf(this.viewer)),
                () -> assertEquals(1, this.wardrobe.stops()),
                () -> assertEquals(List.of("Stopped."), this.surface.lines()));
    }

    @Test
    void goingBackLandsOnThePicker() {
        final AnimationMenu screen = open();
        this.presenter.reset();

        screen.onBack(FakeClick.by(this.viewer));

        assertNotNull(this.presenter.last(ModelPickerMenu.class));
    }

    @Test
    void theAnimationPageIsRememberedAcrossARebuild() {
        final MenuService service = service("animation.pageSize: 2");
        service.openAnimations(this.viewer);
        this.presenter.last(AnimationMenu.class).onNextPage(FakeClick.by(this.viewer));

        this.surface.reset();
        service.openAnimations(this.viewer);
        this.presenter.last(AnimationMenu.class).choiceButtons();

        assertEquals(List.of("roar", "fly"), choiceLabels());
    }

    @Test
    void aPageNumberLeftOverFromALongerListIsClamped() {
        final MenuService service = service("animation.pageSize: 2");
        service.openAnimations(this.viewer);
        this.presenter.last(AnimationMenu.class).onNextPage(FakeClick.by(this.viewer));
        this.presenter.last(AnimationMenu.class).onNextPage(FakeClick.by(this.viewer));

        this.catalogue.withAnimations("dragon", "idle");
        this.surface.reset();
        service.openAnimations(this.viewer);
        this.presenter.last(AnimationMenu.class).choiceButtons();

        assertEquals(List.of("idle"), choiceLabels());
    }

    @Test
    void aClickFromSomebodyElseChangesNothing() {
        final AnimationMenu screen = open("animation.pageSize: 5");
        screen.choiceButtons();
        final List<FakeMenuSurface.Button> buttons = this.surface.actionButtons();
        this.presenter.reset();

        buttons.getFirst().onClick().onClick(FakeClick.byStranger());
        screen.onStop(FakeClick.byStranger());

        assertAll(
                () -> assertNull(this.wardrobe.animationOf(this.viewer)),
                () -> assertEquals(0, this.wardrobe.stops()),
                () -> assertEquals(0, this.presenter.count()));
    }

    @Test
    void openingItWithNothingWornLandsOnThePickerInstead() {
        final Player bare = MenuFixtures.player("bare");
        final MenuService service = service("animation.noModelMessage: 'Wear something.'");
        service.openAnimations(bare);
        this.surface.reset();
        this.presenter.last(ModelPickerMenu.class).noticeLine();

        assertAll(
                () -> assertTrue(this.presenter.last() instanceof ModelPickerMenu),
                () -> assertEquals(List.of("Wear something."), this.surface.lines()));
    }

    /** Presses the list button whose label is {@code animationId}. */
    private void click(String animationId) {
        for (final FakeMenuSurface.Button button : this.surface.actionButtons()) {
            if (button.text().equals(animationId)) {
                assertNotNull(button.onClick());
                button.onClick().onClick(FakeClick.by(this.viewer));
                return;
            }
        }
        throw new AssertionError("No list button is labelled '" + animationId + "'");
    }
}
