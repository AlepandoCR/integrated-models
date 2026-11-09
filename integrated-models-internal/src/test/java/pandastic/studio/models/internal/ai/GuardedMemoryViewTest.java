package pandastic.studio.models.internal.ai;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.ai.AiMemory;
import pandastic.studio.models.api.ai.AiMemoryView;
import pandastic.studio.models.api.ai.AiPlan;
import pandastic.studio.models.api.ai.AiPoint;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard that turns the server's two silent memory failures into one sentence.
 *
 * <p>Those two failures are the whole subject. A write to a memory the brain was not built with is
 * accepted and discarded, so the mistake presents as a behaviour that never fires; a read of one throws
 * from inside the creature's tick with the server's class names in the message. Both are converted here,
 * and the assertions are on the message because the person reading it has to work out whether they
 * mistyped a key or forgot to declare it.
 */
class GuardedMemoryViewTest {

    private static final AiMemory<Long> COOLDOWN = AiMemory.defined("cooldown", Long.class, 40L);
    private static final AiMemory<AiPoint> WAYPOINT = AiMemory.defined("waypoint", AiPoint.class);
    private static final AiMemory<Long> UNDECLARED = AiMemory.defined("undeclared", Long.class);

    private final FakeAiHost host = new FakeAiHost();
    private final FakeMemoryStore store = new FakeMemoryStore();
    private final AiPlan plan = AiFixtures.minimal("guard")
            .declareMemory(COOLDOWN)
            .declareMemory(WAYPOINT)
            .build();
    private final AiMemoryView view =
            new GuardedMemoryView(this.plan, this.store, this.host, AiFixtures.BOUNDS);

    @Test
    void aDeclaredMemoryRoundTrips() {
        this.view.set(COOLDOWN, 7L);

        assertAll(
                () -> assertEquals(Optional.of(7L), this.view.get(COOLDOWN)),
                () -> assertTrue(this.view.has(COOLDOWN)),
                () -> assertEquals(40L, this.store.expiries.get("minecraft:cooldown"),
                        "a set with no explicit expiry must use the memory's own default")
        );
    }

    @Test
    void anExplicitExpiryOverridesTheMemorysDefault() {
        this.view.set(COOLDOWN, 7L, 5L);

        assertEquals(5L, this.store.expiries.get("minecraft:cooldown"));
    }

    @Test
    void clearingIsIdempotent() {
        this.view.set(WAYPOINT, AiPoint.ORIGIN);
        this.view.clear(WAYPOINT);
        this.view.clear(WAYPOINT);

        assertAll(
                () -> assertFalse(this.view.has(WAYPOINT)),
                () -> assertEquals(Optional.empty(), this.view.get(WAYPOINT)),
                () -> assertEquals(2, this.store.erased.get("minecraft:waypoint"),
                        "a behaviour clearing a cooldown on stop should not have to check first")
        );
    }

    @Test
    void anUndeclaredMemoryIsRefusedOnEveryOperationAndNothingReachesTheStore() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> this.view.get(UNDECLARED)),
                () -> assertThrows(IllegalArgumentException.class, () -> this.view.set(UNDECLARED, 1L)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> this.view.set(UNDECLARED, 1L, 5L)),
                () -> assertThrows(IllegalArgumentException.class, () -> this.view.has(UNDECLARED)),
                () -> assertThrows(IllegalArgumentException.class, () -> this.view.clear(UNDECLARED)),
                // The store is deliberately permissive, so anything in it got past the guard.
                () -> assertTrue(this.store.written.isEmpty()),
                () -> assertTrue(this.store.erased.isEmpty())
        );
    }

    @Test
    void theRefusalNamesThePlanTheKeyAndWhereToDeclareIt() {
        final IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> this.view.get(UNDECLARED));

        assertAll(
                () -> assertTrue(refused.getMessage().contains("guard"), refused::getMessage),
                () -> assertTrue(refused.getMessage().contains("minecraft:undeclared"),
                        refused::getMessage),
                () -> assertTrue(refused.getMessage().contains("usedMemories()"),
                        () -> "the refusal has to say where to declare it: " + refused.getMessage())
        );
    }

    @Test
    void everyOperationIsRefusedFromAThreadThatDoesNotOwnTheCreature() {
        this.host.owned = false;

        assertAll(
                () -> assertThrows(IllegalStateException.class, () -> this.view.get(COOLDOWN)),
                () -> assertThrows(IllegalStateException.class, () -> this.view.set(COOLDOWN, 1L)),
                () -> assertThrows(IllegalStateException.class, () -> this.view.has(COOLDOWN)),
                () -> assertThrows(IllegalStateException.class, () -> this.view.clear(COOLDOWN)),
                () -> assertTrue(this.store.written.isEmpty())
        );
    }

    @Test
    void theOwnershipCheckIsConsultedPerCallBecauseACreatureCanChangeRegion() {
        this.view.set(COOLDOWN, 1L);
        this.host.owned = false;
        assertThrows(IllegalStateException.class, () -> this.view.get(COOLDOWN));
        this.host.owned = true;
        assertEquals(Optional.of(1L), this.view.get(COOLDOWN));
    }

    @Test
    void membershipIsCheckedBeforeOwnershipSoTheMessageNamesTheRealMistake() {
        // Both are wrong here. Reporting the thread would send the reader looking for a concurrency
        // problem that is not there, while the plan is missing the key on every thread.
        this.host.owned = false;

        assertThrows(IllegalArgumentException.class, () -> this.view.get(UNDECLARED));
    }

    @Test
    void anExpiryAboveTheConfiguredCeilingIsRefusedOnTheWriteItself() {
        // The plan-build check only covers a memory's declared default. A body may compute an expiry per
        // write, and a computed one is exactly the kind that runs away.
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> this.view.set(COOLDOWN, 1L, AiFixtures.BOUNDS.maxMemoryExpiryTicks() + 1L));

        assertAll(
                () -> assertTrue(refused.getMessage().contains("maxMemoryExpiryTicks"),
                        refused::getMessage),
                () -> assertTrue(this.store.written.isEmpty())
        );
    }

    @Test
    void aNegativeExpiryIsRefusedWithThePhraseForWhatWasProbablyMeant() {
        final IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> this.view.set(COOLDOWN, 1L, -1L));

        assertTrue(refused.getMessage().contains("NEVER_EXPIRES"), refused::getMessage);
    }

    @Test
    void aValueOfTheWrongTypeInTheStoreFailsAsACastRatherThanSilently() {
        // The store is untyped by necessity, so the only thing standing between a wrong value and a
        // caller's variable is Class::cast. This is what proves it is there.
        this.store.values.put("minecraft:cooldown", "not a long");

        assertThrows(ClassCastException.class, () -> this.view.get(COOLDOWN));
    }

    @Test
    void aNullValueIsRefusedBecauseForgettingHasItsOwnMethod() {
        assertThrows(NullPointerException.class, () -> this.view.set(COOLDOWN, null));
    }

    @Test
    void aVanillaReferenceCanBeTestedForPresenceOnceDeclared() {
        final AiMemory<Void> attackTarget = AiMemory.vanilla("attack_target");
        final AiPlan withVanilla = AiFixtures.minimal("watcher").declareMemory(attackTarget).build();
        final AiMemoryView vanillaView =
                new GuardedMemoryView(withVanilla, this.store, this.host, AiFixtures.BOUNDS);

        assertAll(
                () -> assertFalse(vanillaView.has(attackTarget)),
                // Reading its value is typed as Optional<Void>, so it can only ever be empty. The real
                // value never crosses the seam; see AiMemory.
                () -> assertEquals(Optional.empty(), vanillaView.get(attackTarget))
        );
    }

    @Test
    void aUuidMemoryIsHowABehaviourRemembersSomething() {
        // The documented alternative to holding a platform object across a tick boundary.
        final AiMemory<UUID> target = AiMemory.defined("target", UUID.class);
        final AiPlan withTarget = AiFixtures.minimal("hunter").declareMemory(target).build();
        final AiMemoryView targetView =
                new GuardedMemoryView(withTarget, this.store, this.host, AiFixtures.BOUNDS);
        final UUID identity = UUID.randomUUID();

        targetView.set(target, identity);

        assertEquals(Optional.of(identity), targetView.get(target));
    }
}
