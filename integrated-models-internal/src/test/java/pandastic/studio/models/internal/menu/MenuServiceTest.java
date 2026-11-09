package pandastic.studio.models.internal.menu;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three ways in and the one way out.
 *
 * <p>What is worth asserting here is the shape of the navigation rather than any screen's contents: the
 * picker has no precondition and is therefore where every refusal lands, the other two have one and
 * cannot report it any way but by replacing themselves, and shutdown has to leave nothing behind
 * because a plugin's classloader closes before the platform tidies up after it.
 */
class MenuServiceTest {

    @TempDir
    Path directory;

    private final FakeMenuSurface surface = new FakeMenuSurface();
    private final FakeMenuPresenter presenter = new FakeMenuPresenter();
    private final FakeModelWardrobe wardrobe = new FakeModelWardrobe();
    private final SteppedClock clock = new SteppedClock();

    private MenuSessions sessions;
    private MenuService service;
    private Player viewer;

    @BeforeEach
    void setUp() {
        final MenuSettingsSource source =
                MenuSettingsSource.fixed(MenuFixtures.fromFile(this.directory));
        this.sessions = new MenuSessions(source, this.clock);
        this.service = new MenuService(source, this.surface, this.sessions,
                FakeModelCatalogue.of("dragon"), this.wardrobe, this.presenter);
        this.viewer = MenuFixtures.player("tester");
    }

    @Test
    void thePickerOpensWithNoPreconditionAtAll() {
        this.service.openPicker(this.viewer);

        assertAll(
                () -> assertEquals(1, this.presenter.count()),
                () -> assertTrue(this.presenter.last() instanceof ModelPickerMenu));
    }

    @Test
    void thePickerOpensEvenWithAnEmptyCatalogue() {
        final MenuSettingsSource source =
                MenuSettingsSource.fixed(MenuFixtures.fromFile(this.directory));
        final MenuService empty = new MenuService(source, this.surface,
                new MenuSessions(source, this.clock), FakeModelCatalogue.sized(0),
                this.wardrobe, this.presenter);

        empty.openPicker(this.viewer);

        assertTrue(this.presenter.last() instanceof ModelPickerMenu);
    }

    @Test
    void theTwoScreensThatNeedAModelRefuseByReplacingThemselves() {
        this.service.openAppearance(this.viewer);
        assertTrue(this.presenter.last() instanceof ModelPickerMenu);

        this.presenter.reset();
        this.service.openAnimations(this.viewer);
        assertTrue(this.presenter.last() instanceof ModelPickerMenu);
    }

    @Test
    void theyOpenOnceThereIsAModel() {
        this.wardrobe.dress(this.viewer, "dragon");

        this.service.openAppearance(this.viewer);
        assertTrue(this.presenter.last() instanceof AppearanceMenu);

        this.service.openAnimations(this.viewer);
        assertTrue(this.presenter.last() instanceof AnimationMenu);
    }

    @Test
    void closingDropsOnlyThatPlayersSession() {
        final Player other = MenuFixtures.player("other");
        this.service.openPicker(this.viewer);
        this.service.openPicker(other);

        this.service.close(this.viewer);

        assertAll(
                () -> assertTrue(this.sessions.find(this.viewer.getUniqueId()).isEmpty()),
                () -> assertTrue(this.sessions.find(other.getUniqueId()).isPresent()));
    }

    @Test
    void shutdownLeavesNothingBehind() {
        this.service.openPicker(this.viewer);
        this.service.openPicker(MenuFixtures.player("other"));

        this.service.shutdown();

        assertEquals(0, this.sessions.size());
    }

    @Test
    void openingAScreenIsWhatPrunesTheStore() {
        // There is no scheduled sweep: a region or entity task is never cancelled for us on disable, so
        // the housekeeping hangs off the one moment this layer is certainly running.
        this.service.openPicker(MenuFixtures.player("transient"));
        assertEquals(1, this.sessions.size());

        this.clock.advanceSeconds(10_000);
        this.service.openPicker(this.viewer);

        assertEquals(1, this.sessions.size());
    }
}
