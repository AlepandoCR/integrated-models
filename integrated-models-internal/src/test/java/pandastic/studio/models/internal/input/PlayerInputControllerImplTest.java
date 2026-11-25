package pandastic.studio.models.internal.input;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.input.InputCapability;
import pandastic.studio.models.api.input.InputCombination;
import pandastic.studio.models.api.input.InputDispatch;
import pandastic.studio.models.api.input.InputFamily;
import pandastic.studio.models.api.input.InputIssue;
import pandastic.studio.models.api.input.InputKey;
import pandastic.studio.models.api.input.InputRegistration;
import pandastic.studio.models.api.input.InputResponse;
import pandastic.studio.models.api.input.InputSnapshot;
import pandastic.studio.models.api.input.InputTuning;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dispatcher, the conflict reporting and the two read-once disciplines.
 *
 * <h2>What is actually at risk here</h2>
 * Not whether a handler runs — that is one line and nothing doubts it. The risks are the things that
 * fail silently: a shadowing pair of bindings that nobody is told about, a suppression that a consumer
 * believes in and does not get, a step that reads its configuration twice and judges one input against
 * two generations of it, and a player who disconnects mid-key and keeps driving. Each of those has a
 * test and each is the reason the corresponding code is shaped the way it is.
 */
@DisplayName("PlayerInputControllerImpl")
final class PlayerInputControllerImplTest {

    private static final long MILLISECOND = 1_000_000L;
    private static final long NOW = 1_000L * MILLISECOND;

    /** What the platform source alone offers: everything but suppressing movement and seeing it early. */
    private static final Set<InputCapability> PLATFORM_ONLY = Set.of(
            InputCapability.OBSERVE_MOVEMENT,
            InputCapability.OBSERVE_ACTIONS,
            InputCapability.SUPPRESS_ACTIONS);

    /** What a server with the packet source has. */
    private static final Set<InputCapability> EVERYTHING =
            EnumSet.allOf(InputCapability.class);

    // ----------------------------------------------------------------- dispatch

    @Test
    @DisplayName("a chord fires once when it starts matching and not again until it stops")
    void dispatchesOnTheEdge() {
        final InputFixtures.CountingSettingsSource settings =
                new InputFixtures.CountingSettingsSource(InputFixtures.defaults());
        final PlayerInputControllerImpl controller = controller(settings, EVERYTHING, null);
        final Player player = InputFixtures.player("rider");
        final List<String> fired = new ArrayList<>();

        controller.bind(InputCombination.chord(InputKey.SPRINT, InputKey.JUMP),
                event -> fired.add(event.combination().describe()));

        // Not matching yet.
        controller.movement(delivery(player, NOW), InputKey.SPRINT.mask());
        assertEquals(List.of(), fired);

        // Matching: one dispatch.
        controller.movement(delivery(player, NOW + MILLISECOND),
                InputKey.SPRINT.mask() | InputKey.JUMP.mask());
        assertEquals(List.of("sprint+jump"), fired);

        // Still matching, inside the repeat interval: no second dispatch. Without this gate a held
        // combination would fire on every client tick.
        controller.movement(delivery(player, NOW + 2L * MILLISECOND),
                InputKey.SPRINT.mask() | InputKey.JUMP.mask() | InputKey.SNEAK.mask());
        assertEquals(1, fired.size());

        // Past the repeat interval: it fires again.
        final long repeat = InputFixtures.defaults().tuning().repeatIntervalNanos();
        controller.movement(delivery(player, NOW + 2L * MILLISECOND + repeat),
                InputKey.SPRINT.mask() | InputKey.JUMP.mask());
        assertEquals(2, fired.size());
    }

    @Test
    @DisplayName("every matching handler runs, in registration order, with no winner picked")
    void dispatchIsMulticast() {
        final PlayerInputControllerImpl controller = controller(EVERYTHING, null);
        final Player player = InputFixtures.player("rider");
        final List<String> fired = new ArrayList<>();

        controller.bind(InputCombination.held(InputKey.SNEAK), event -> fired.add("general"));
        controller.bind(InputCombination.chord(InputKey.SNEAK, InputKey.USE),
                event -> fired.add("specific"));

        controller.movement(delivery(player, NOW), InputKey.SNEAK.mask());
        assertEquals(List.of("general"), fired);

        controller.pulse(delivery(player, NOW + MILLISECOND), InputKey.USE);
        // The general binding is still inside its repeat interval, so only the specific one is new.
        assertEquals(List.of("general", "specific"), fired);
    }

    @Test
    @DisplayName("a player-scoped binding only fires for that player")
    void scoping() {
        final PlayerInputControllerImpl controller = controller(EVERYTHING, null);
        final UUID riderId = UUID.randomUUID();
        final Player rider = InputFixtures.player("rider", riderId);
        final Player other = InputFixtures.player("other");
        final List<String> fired = new ArrayList<>();

        controller.bind(riderId, InputCombination.held(InputKey.FORWARD), InputResponse.OBSERVE,
                event -> fired.add(event.player().getName()));

        controller.movement(delivery(other, NOW), InputKey.FORWARD.mask());
        assertEquals(List.of(), fired);

        controller.movement(delivery(rider, NOW), InputKey.FORWARD.mask());
        assertEquals(List.of("rider"), fired);
    }

    @Test
    @DisplayName("a closed registration stops matching")
    void closing() {
        final PlayerInputControllerImpl controller = controller(EVERYTHING, null);
        final Player player = InputFixtures.player("rider");
        final List<String> fired = new ArrayList<>();

        final InputRegistration registration = controller.bind(
                InputCombination.held(InputKey.FORWARD), event -> fired.add("x"));
        assertTrue(registration.isActive());
        assertEquals(1, controller.bindingCount());

        registration.close();

        assertFalse(registration.isActive());
        assertEquals(0, controller.bindingCount());
        controller.movement(delivery(player, NOW), InputKey.FORWARD.mask());
        assertEquals(List.of(), fired);
    }

    // -------------------------------------------------------------- suppression

    @Test
    @DisplayName("a suppressing handler on a capable source stops the input and says so")
    void suppressionHonoured() {
        final PlayerInputControllerImpl controller = controller(EVERYTHING, null);
        final Player player = InputFixtures.player("rider");

        controller.bind(InputCombination.held(InputKey.FORWARD), InputResponse.SUPPRESS,
                event -> assertTrue(event.suppress(), "a capable source must honour it"));

        // The sink's answer is what the packet source uses to decide whether to drop the packet, so
        // it has to come back true from the same call that dispatched.
        assertTrue(controller.movement(suppressible(player, NOW), InputKey.FORWARD.mask()));
    }

    @Test
    @DisplayName("a suppressing handler on an incapable source is told at registration and at delivery")
    void suppressionRefusedWhenUnavailable() {
        // The degraded shape: no NMS adapter, so movement cannot be suppressed. The point is that the
        // consumer is told twice — once while it is still in its own enable method, and again on the
        // delivery — rather than silently having no effect.
        final PlayerInputControllerImpl controller = controller(PLATFORM_ONLY, "no adapter");
        final Player player = InputFixtures.player("rider");

        assertFalse(controller.canSuppress(InputFamily.MOVEMENT));
        assertTrue(controller.canSuppress(InputFamily.ACTION));

        final InputRegistration registration = controller.bind(
                InputCombination.held(InputKey.FORWARD), InputResponse.SUPPRESS,
                event -> {
                    assertFalse(event.canSuppress());
                    assertFalse(event.suppress());
                });

        assertTrue(registration.isActive(), "losing suppression must not cost the observation");
        assertEquals(List.of(InputIssue.Kind.SUPPRESSION_UNSUPPORTED), kinds(registration));
        assertFalse(controller.movement(delivery(player, NOW), InputKey.FORWARD.mask()));
    }

    @Test
    @DisplayName("an observing binding cannot suppress even on a capable source")
    void observingCannotSuppress() {
        // Otherwise a binding would acquire by accident an effect it never declared, and the
        // registration-time check that is the whole point of declaring it would mean nothing.
        final PlayerInputControllerImpl controller = controller(EVERYTHING, null);
        final Player player = InputFixtures.player("rider");

        controller.bind(InputCombination.held(InputKey.FORWARD), event -> {
            assertFalse(event.canSuppress());
            assertFalse(event.suppress());
        });

        assertFalse(controller.movement(suppressible(player, NOW), InputKey.FORWARD.mask()));
    }

    // ------------------------------------------------------------------- issues

    @Test
    @DisplayName("a weaker binding is reported as shadowing the one already there")
    void shadowingIsReported() {
        final PlayerInputControllerImpl controller = controller(EVERYTHING, null);

        controller.bind(InputCombination.chord(InputKey.SNEAK, InputKey.USE), event -> { });
        final InputRegistration general =
                controller.bind(InputCombination.held(InputKey.SNEAK), event -> { });

        assertEquals(List.of(InputIssue.Kind.SHADOWS), kinds(general));
        assertTrue(general.isActive(), "an overlap is a legitimate design, so it is reported not refused");
        assertTrue(general.issues().getFirst().message().contains("sneak+use"),
                "the report has to name the other binding, or it is unactionable");
    }

    @Test
    @DisplayName("the same combination twice is reported as equivalent")
    void equivalenceIsReported() {
        final PlayerInputControllerImpl controller = controller(EVERYTHING, null);

        controller.bind(InputCombination.held(InputKey.JUMP), event -> { });
        final InputRegistration second =
                controller.bind(InputCombination.held(InputKey.JUMP), event -> { });

        assertEquals(List.of(InputIssue.Kind.EQUIVALENT), kinds(second));
    }

    @Test
    @DisplayName("mutually exclusive bindings are reported as nothing at all")
    void independenceIsSilent() {
        final PlayerInputControllerImpl controller = controller(EVERYTHING, null);

        controller.bind(InputCombination.chord(InputKey.USE, InputKey.SNEAK), event -> { });
        final InputRegistration modifierless = controller.bind(
                InputCombination.held(InputKey.USE).without(InputKey.SNEAK), event -> { });

        assertEquals(List.of(), modifierless.issues(),
                "reporting the normal case would bury the two findings that are not normal");
    }

    @Test
    @DisplayName("two bindings scoped to different players never shadow each other")
    void scopesDoNotCollide() {
        final PlayerInputControllerImpl controller = controller(EVERYTHING, null);

        controller.bind(UUID.randomUUID(), InputCombination.chord(InputKey.SNEAK, InputKey.USE),
                InputResponse.OBSERVE, event -> { });
        final InputRegistration other = controller.bind(UUID.randomUUID(),
                InputCombination.held(InputKey.SNEAK), InputResponse.OBSERVE, event -> { });

        assertEquals(List.of(), other.issues());
    }

    @Test
    @DisplayName("an audit finds a shadow that only exists because of registration order")
    void auditSeesBothDirections() {
        // bind() compares a new binding against the ones that were there. An audit compares the whole
        // set, which is the only way to see the pair a reader would have ordered the other way.
        final PlayerInputControllerImpl controller = controller(EVERYTHING, null);

        controller.bind(InputCombination.held(InputKey.SNEAK), event -> { });
        controller.bind(InputCombination.chord(InputKey.SNEAK, InputKey.USE), event -> { });

        final List<InputIssue.Kind> kinds = controller.audit().stream().map(InputIssue::kind).toList();
        assertEquals(List.of(InputIssue.Kind.SHADOWS), kinds);
    }

    @Test
    @DisplayName("an unsatisfiable combination is refused rather than silently never firing")
    void unsatisfiableIsRefused() {
        final PlayerInputControllerImpl controller = controller(EVERYTHING, null);

        final InputRegistration registration = controller.bind(
                InputCombination.hold(InputKey.ATTACK), event -> { });

        assertFalse(registration.isActive());
        assertEquals(List.of(InputIssue.Kind.UNSATISFIABLE), kinds(registration));
        assertTrue(registration.issues().getFirst().isRefusal());
        assertEquals(0, controller.bindingCount());
    }

    @Test
    @DisplayName("a binding past the configured ceiling is refused")
    void limitIsEnforced() {
        final PlayerInputControllerImpl controller = controller(
                InputSettingsSource.fixed(InputFixtures.withMaxBindings(1)), EVERYTHING, null);

        assertTrue(controller.bind(InputCombination.held(InputKey.FORWARD), event -> { }).isActive());
        final InputRegistration refused =
                controller.bind(InputCombination.held(InputKey.BACKWARD), event -> { });

        assertFalse(refused.isActive());
        assertEquals(List.of(InputIssue.Kind.LIMIT_REACHED), kinds(refused));
        assertEquals(1, controller.bindingCount());
    }

    @Test
    @DisplayName("a combination naming an unobservable family is registered and reported")
    void unobservableFamilyIsReported() {
        // Registered rather than refused, because a server that gains the missing source should find
        // the binding already there rather than need the consumer to re-register.
        final PlayerInputControllerImpl controller = controller(
                Set.of(InputCapability.OBSERVE_MOVEMENT), "actions are off");

        final InputRegistration registration =
                controller.bind(InputCombination.held(InputKey.USE), event -> { });

        assertTrue(registration.isActive());
        assertEquals(List.of(InputIssue.Kind.FAMILY_UNOBSERVABLE), kinds(registration));
        assertFalse(controller.canObserve(InputFamily.ACTION));
    }

    // ------------------------------------------------------------ the read-once disciplines

    @Test
    @DisplayName("one observation reads the settings exactly once")
    void settingsAreReadOncePerObservation() {
        // A dispatcher that read per value would pass every behavioural test here and still be able to
        // judge one input against two generations of configuration — which is the hazard the settings
        // source's contract names, and this count is the only thing that can detect it.
        final InputFixtures.CountingSettingsSource settings =
                new InputFixtures.CountingSettingsSource(InputFixtures.defaults());
        final PlayerInputControllerImpl controller = controller(settings, EVERYTHING, null);
        final Player player = InputFixtures.player("rider");

        controller.bind(InputCombination.held(InputKey.FORWARD), event -> { });
        controller.bind(InputCombination.hold(InputKey.SNEAK), event -> { });
        controller.bind(InputCombination.doubleTap(InputKey.FORWARD), event -> { });
        settings.resetCount();

        controller.movement(delivery(player, NOW), InputKey.FORWARD.mask());

        assertEquals(1L, settings.reads(),
                "three bindings and one observation still has to be one read");
    }

    @Test
    @DisplayName("a reload between two observations is seen by the second and not the first")
    void aReloadLandsBetweenObservations() {
        final InputSettings first = InputFixtures.defaults();
        final InputFixtures.CountingSettingsSource settings =
                new InputFixtures.CountingSettingsSource(first);
        final PlayerInputControllerImpl controller = controller(settings, EVERYTHING, null);

        // Identity, not equality: a source whose value is identity-stable while the configuration is
        // unchanged is a contract this project has broken once, elsewhere, with no test noticing.
        assertSame(first.tuning(), controller.tuning());
        assertSame(first.tuning(), controller.tuning());

        final InputTuning replacement = InputTuning.ofMillis(10L, 20L, 30L, 40L);
        settings.publish(InputFixtures.withTuning(replacement));

        assertSame(replacement, controller.tuning());
    }

    @Test
    @DisplayName("a snapshot older than the staleness window reads as idle")
    void stalenessStopsADroppedConnection() {
        // A client that goes away mid-key never sends the release, so without this a vehicle would be
        // driven by a player who is no longer connected.
        final InputFixtures.ManualClock clock = new InputFixtures.ManualClock();
        final PlayerInputControllerImpl controller = new PlayerInputControllerImpl(
                InputSettingsSource.fixed(InputFixtures.defaults()), clock,
                RegionDispatcher.inline(), InputFaultSink.ignoring(), EVERYTHING, null);
        final Player player = InputFixtures.player("rider");

        clock.set(NOW);
        controller.movement(delivery(player, NOW), InputKey.FORWARD.mask());
        assertTrue(controller.snapshot(player).isDown(InputKey.FORWARD));

        clock.advance(InputFixtures.defaults().tuning().staleAfterNanos() + 1L);

        assertSame(InputSnapshot.IDLE, controller.snapshot(player));
    }

    @Test
    @DisplayName("an unseen player reads as idle rather than as null")
    void unseenPlayerIsIdle() {
        final PlayerInputControllerImpl controller = controller(EVERYTHING, null);

        assertSame(InputSnapshot.IDLE, controller.snapshot(UUID.randomUUID()));
        assertSame(InputSnapshot.IDLE, controller.snapshot(InputFixtures.player("stranger")));
    }

    @Test
    @DisplayName("forgetting a player drops the published state")
    void forgetting() {
        final PlayerInputControllerImpl controller = controller(EVERYTHING, null);
        final Player player = InputFixtures.player("rider");

        controller.movement(delivery(player, NOW), InputKey.FORWARD.mask());
        controller.forget(player.getUniqueId());

        assertSame(InputSnapshot.IDLE, controller.snapshot(player));
    }

    // -------------------------------------------------------------------- faults

    @Test
    @DisplayName("a handler that keeps throwing has its binding closed")
    void faultsCloseABinding() {
        final List<String> reports = new ArrayList<>();
        final InputSettingsSource settings =
                InputSettingsSource.fixed(InputFixtures.withFaultLimit(2));
        final PlayerInputControllerImpl controller = new PlayerInputControllerImpl(
                settings, new InputFixtures.ManualClock(), RegionDispatcher.inline(),
                (binding, failure) -> {
                    reports.add(binding);
                    return reports.size() < 2;
                },
                EVERYTHING, null);
        final Player player = InputFixtures.player("rider");

        final InputRegistration registration = controller.bind(
                InputCombination.pressed(InputKey.JUMP),
                event -> {
                    throw new IllegalStateException("a consumer's bug");
                });

        controller.movement(delivery(player, NOW), InputKey.JUMP.mask());
        assertTrue(registration.isActive());

        controller.movement(delivery(player, NOW + MILLISECOND), 0);
        controller.movement(delivery(player, NOW + 2L * MILLISECOND), InputKey.JUMP.mask());

        assertFalse(registration.isActive(), "a handler that always throws must stop being called");
        assertEquals(2, reports.size());
    }

    @Test
    @DisplayName("a handler that throws does not stop the others")
    void faultsAreIsolated() {
        final PlayerInputControllerImpl controller = controller(EVERYTHING, null);
        final Player player = InputFixtures.player("rider");
        final List<String> fired = new ArrayList<>();

        controller.bind(InputCombination.held(InputKey.FORWARD), event -> {
            throw new IllegalStateException("a consumer's bug");
        });
        controller.bind(InputCombination.held(InputKey.FORWARD), event -> fired.add("second"));

        controller.movement(delivery(player, NOW), InputKey.FORWARD.mask());

        assertEquals(List.of("second"), fired);
    }

    @Test
    @DisplayName("shutdown closes every binding and forgets every player")
    void shutdown() {
        final PlayerInputControllerImpl controller = controller(EVERYTHING, null);
        final Player player = InputFixtures.player("rider");
        final InputRegistration registration =
                controller.bind(InputCombination.held(InputKey.FORWARD), event -> { });
        controller.movement(delivery(player, NOW), InputKey.FORWARD.mask());

        assertEquals(1, controller.shutdown());

        assertFalse(registration.isActive());
        assertEquals(0, controller.bindingCount());
        assertSame(InputSnapshot.IDLE, controller.snapshot(player));
    }

    @Test
    @DisplayName("the reason for a reduced capability set is carried, not inferred")
    void unavailableReason() {
        assertEquals("no adapter",
                controller(PLATFORM_ONLY, "no adapter").unavailableReason().orElseThrow());
        assertTrue(controller(EVERYTHING, null).unavailableReason().isEmpty());
    }

    // ----------------------------------------------------------------- fixtures

    private static PlayerInputControllerImpl controller(
            Set<InputCapability> capabilities,
            String unavailableReason
    ) {
        return controller(
                InputSettingsSource.fixed(InputFixtures.defaults()), capabilities, unavailableReason);
    }

    private static PlayerInputControllerImpl controller(
            InputSettingsSource settings,
            Set<InputCapability> capabilities,
            String unavailableReason
    ) {
        return new PlayerInputControllerImpl(
                settings,
                new InputFixtures.ManualClock(),
                RegionDispatcher.inline(),
                InputFaultSink.ignoring(),
                capabilities,
                unavailableReason);
    }

    /** An observation that cannot be suppressed, which is what the platform source makes for movement. */
    private static InputDelivery delivery(Player player, long nowNanos) {
        return InputDelivery.observing(player, nowNanos, InputDispatch.PLAYER_REGION);
    }

    /** An observation that can be suppressed, which is what the packet source makes. */
    private static InputDelivery suppressible(Player player, long nowNanos) {
        return new InputDelivery(player, nowNanos, InputDispatch.NETWORK, () -> true);
    }

    private static List<InputIssue.Kind> kinds(InputRegistration registration) {
        return registration.issues().stream().map(InputIssue::kind).toList();
    }
}
