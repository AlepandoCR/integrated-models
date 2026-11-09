package pandastic.studio.models.internal.ai;

import pandastic.studio.models.api.ai.AiActivity;
import pandastic.studio.models.api.ai.AiActivityPlan;
import pandastic.studio.models.api.ai.AiBehaviour;
import pandastic.studio.models.api.ai.AiBounds;
import pandastic.studio.models.api.ai.AiMemory;
import pandastic.studio.models.api.ai.AiMemoryStatus;
import pandastic.studio.models.api.ai.AiPlan;
import pandastic.studio.models.api.ai.AiPoint;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plan validator.
 *
 * <h2>Every case here is a failure that is silent at runtime</h2>
 * That is the selection criterion, not coverage. A plan that exceeds a bound would merely make the
 * server slower; a plan with a default activity it never filled would produce a creature that stands
 * still and looks exactly like a creature whose conditions are never met. The second kind is what the
 * validator exists for, and the assertions are on the message as much as on the refusal, because the
 * person reading it is going to go and change something.
 */
class AiPlanTest {

    private static final AiMemory<Long> COOLDOWN = AiMemory.defined("cooldown", Long.class);
    private static final AiMemory<Boolean> ALERTED = AiMemory.defined("alerted", Boolean.class);
    private static final AiMemory<Void> ATTACK_TARGET = AiMemory.vanilla("attack_target");

    @Test
    void theSmallestUsefulPlanBuilds() {
        final AiPlan plan = AiFixtures.minimal("guard").build();

        assertAll(
                () -> assertEquals("guard", plan.getName()),
                () -> assertEquals(1, plan.getActivities().size()),
                () -> assertEquals(AiActivity.IDLE, plan.getDefaultActivity()),
                () -> assertTrue(plan.getCoreActivities().isEmpty()),
                () -> assertTrue(plan.getSensors().isEmpty()),
                () -> assertTrue(plan.getMemories().isEmpty()),
                // Default on, because a creature fighting its own goals is the harder failure to spot.
                () -> assertTrue(plan.clearsVanillaGoals())
        );
    }

    @Test
    void aPlanWithNoActivitiesIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> AiPlan.builder("empty", AiFixtures.BOUNDS).build());
    }

    @Test
    void aPlanWithNoDefaultActivityIsRefused() {
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> AiPlan.builder("no-fallback", AiFixtures.BOUNDS)
                        .activity(AiFixtures.activity(AiActivity.IDLE))
                        .build());

        assertTrue(refused.getMessage().contains("default activity"),
                () -> "the refusal must name what is missing: " + refused.getMessage());
    }

    @Test
    void aDefaultActivityWithNoFilledSlotIsRefused() {
        // The silent failure this catches: the brain is pointed at an activity with no behaviours, so
        // the creature stands still and nothing says why.
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> AiPlan.builder("dangling", AiFixtures.BOUNDS)
                        .activity(AiFixtures.activity(AiActivity.IDLE))
                        .defaultActivity(AiActivity.FIGHT)
                        .build());

        assertTrue(refused.getMessage().contains("never fills"),
                () -> refused.getMessage());
    }

    @Test
    void anActivityThatIsBothCoreAndDefaultIsRefused() {
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> AiPlan.builder("contradiction", AiFixtures.BOUNDS)
                        .activity(AiFixtures.activity(AiActivity.IDLE))
                        .coreActivity(AiActivity.IDLE)
                        .defaultActivity(AiActivity.IDLE)
                        .build());

        assertTrue(refused.getMessage().contains("its own fallback"), () -> refused.getMessage());
    }

    @Test
    void aCoreActivityWithNoFilledSlotIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> AiFixtures.minimal("dangling-core")
                        .coreActivity(AiActivity.CORE)
                        .build());
    }

    @Test
    void fillingOneSlotTwiceIsRefusedRatherThanReplacing() {
        assertThrows(IllegalArgumentException.class,
                () -> AiFixtures.minimal("twice")
                        .activity(AiFixtures.activity(AiActivity.IDLE))
                        .build());
    }

    @Test
    void anActivityWithNoBehavioursIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> AiActivityPlan.builder(AiActivity.IDLE).build());
    }

    @Test
    void theSameBehaviourInstanceTwiceInOneSlotIsRefused() {
        final AiBehaviour shared = AiFixtures.behaviour("shared");

        assertThrows(IllegalArgumentException.class, () -> AiActivityPlan.builder(AiActivity.IDLE)
                .behaviour(0, shared)
                .behaviour(1, shared)
                .build());
    }

    @Test
    void twoDifferentBehavioursMaySharePriorityAndKeepDeclarationOrder() {
        final AiActivityPlan activity = AiActivityPlan.builder(AiActivity.IDLE)
                .behaviour(1, AiFixtures.behaviour("second"))
                .behaviour(0, AiFixtures.behaviour("first"))
                .behaviour(1, AiFixtures.behaviour("third"))
                .build();

        assertEquals(List.of("first", "second", "third"),
                activity.getBehaviours().stream().map(entry -> entry.behaviour().name()).toList(),
                "the sort must be stable, or a deliberate pair at one priority changes order between"
                        + " builds");
    }

    @Test
    void aNegativePriorityIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> AiActivityPlan.builder(AiActivity.IDLE).behaviour(-1, AiFixtures.behaviour("x")));
    }

    @Test
    void twoContradictoryConditionsOnOneMemoryAreRefused() {
        final AiActivityPlan.Builder builder = AiActivityPlan.builder(AiActivity.FIGHT)
                .behaviour(0, AiFixtures.behaviour("strike"))
                .requires(ATTACK_TARGET, AiMemoryStatus.VALUE_PRESENT);

        assertThrows(IllegalArgumentException.class,
                () -> builder.requires(ATTACK_TARGET, AiMemoryStatus.VALUE_ABSENT));
    }

    @Test
    void theSameConditionTwiceIsHarmless() {
        assertDoesNotThrow(() -> AiActivityPlan.builder(AiActivity.FIGHT)
                .behaviour(0, AiFixtures.behaviour("strike"))
                .requires(ATTACK_TARGET, AiMemoryStatus.VALUE_PRESENT)
                .requires(ATTACK_TARGET, AiMemoryStatus.VALUE_PRESENT)
                .build());
    }

    @Test
    void everyPlaceAMemoryCanAppearPutsItInTheBrainsMemorySet() {
        // The reason this matters: the brain is built with exactly this set, and a write to anything
        // outside it is accepted and discarded.
        final AiMemory<AiPoint> erased = AiMemory.defined("last_seen", AiPoint.class);
        final AiMemory<Boolean> sensed = AiMemory.defined("sensed", Boolean.class);
        final AiMemory<Long> declaredOnly = AiMemory.defined("declared", Long.class);

        final AiPlan plan = AiPlan.builder("thorough", AiFixtures.BOUNDS)
                .activity(AiActivityPlan.builder(AiActivity.IDLE)
                        .behaviour(0, AiFixtures.behaviour("wander", Set.of(COOLDOWN), Set.of(ALERTED)))
                        .requires(ATTACK_TARGET, AiMemoryStatus.VALUE_ABSENT)
                        .erasesOnStop(erased)
                        .build())
                .defaultActivity(AiActivity.IDLE)
                .sensor(AiFixtures.sensor("look", Set.of(sensed)), 2L)
                .declareMemory(declaredOnly)
                .build();

        assertAll(
                () -> assertEquals(Set.of(COOLDOWN, ALERTED, ATTACK_TARGET, erased, sensed,
                        declaredOnly), plan.getMemories()),
                () -> assertTrue(plan.declares(COOLDOWN)),
                () -> assertFalse(plan.declares(AiMemory.defined("never_mentioned", Long.class)))
        );
    }

    @Test
    void onePlanKeyWithTwoValueTypesIsRefused() {
        // The failure it prevents: the server keys one slot per identifier, so both would share it and
        // whichever read second would get a ClassCastException from the other's value.
        final AiMemory<Integer> sameKeyOtherType = AiMemory.defined("cooldown", Integer.class);

        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> AiFixtures.minimal("clash")
                        .declareMemory(COOLDOWN)
                        .declareMemory(sameKeyOtherType)
                        .build());

        assertTrue(refused.getMessage().contains("two different memories"), () -> refused.getMessage());
    }

    @Test
    void aVanillaAndADefinedMemorySharingAKeyAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> AiFixtures.minimal("origin-clash")
                .declareMemory(AiMemory.vanilla("alerted"))
                .declareMemory(ALERTED)
                .build());
    }

    @Test
    void tooManyActivitiesIsRefusedNamingTheKey() {
        final AiPlan.Builder builder = AiPlan.builder("sprawling", AiFixtures.BOUNDS)
                .defaultActivity(AiActivity.IDLE);
        for (final AiActivity activity : List.of(AiActivity.IDLE, AiActivity.FIGHT, AiActivity.AVOID,
                AiActivity.PANIC)) {
            builder.activity(AiFixtures.activity(activity));
        }

        final IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, builder::build);
        assertAll(
                () -> assertTrue(refused.getMessage().contains("maxActivities"), refused::getMessage),
                () -> assertTrue(refused.getMessage().contains("ai.yml"),
                        () -> "a bound refusal must name the file to edit: " + refused.getMessage())
        );
    }

    @Test
    void tooManyBehavioursInOneActivityIsRefusedWhenTheActivityIsAdded() {
        // Checked on add rather than on build, so the message can name the slot while the caller is
        // still looking at the code that filled it.
        final AiActivityPlan overfull = AiActivityPlan.builder(AiActivity.IDLE)
                .behaviour(0, AiFixtures.behaviour("a"))
                .behaviour(1, AiFixtures.behaviour("b"))
                .behaviour(2, AiFixtures.behaviour("c"))
                .build();

        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> AiPlan.builder("overfull", AiFixtures.BOUNDS).activity(overfull));
        assertTrue(refused.getMessage().contains("maxBehavioursPerActivity"), refused::getMessage);
    }

    @Test
    void aPriorityAboveTheConfiguredCeilingIsRefused() {
        final AiActivityPlan tooDeep = AiActivityPlan.builder(AiActivity.IDLE)
                .behaviour(AiFixtures.BOUNDS.maxPriority() + 1, AiFixtures.behaviour("deep"))
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> AiPlan.builder("deep", AiFixtures.BOUNDS).activity(tooDeep));
    }

    @Test
    void aSensorPeriodOutsideTheConfiguredRangeIsRefusedAtBothEnds() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> AiFixtures.minimal("fast")
                        .sensor(AiFixtures.sensor("fast", Set.of()),
                                AiFixtures.BOUNDS.minSensorPeriodTicks() - 1L)),
                () -> assertThrows(IllegalArgumentException.class, () -> AiFixtures.minimal("slow")
                        .sensor(AiFixtures.sensor("slow", Set.of()),
                                AiFixtures.BOUNDS.maxSensorPeriodTicks() + 1L))
        );
    }

    @Test
    void oneSensorTooManyIsRefused() {
        final AiPlan.Builder builder = AiFixtures.minimal("watchful")
                .sensor(AiFixtures.sensor("one", Set.of()), 2L)
                .sensor(AiFixtures.sensor("two", Set.of()), 2L);

        assertThrows(IllegalArgumentException.class,
                () -> builder.sensor(AiFixtures.sensor("three", Set.of()), 2L));
    }

    @Test
    void tooManyMemoriesIsRefused() {
        final AiPlan.Builder builder = AiFixtures.minimal("forgetful");
        for (int index = 0; index <= AiFixtures.BOUNDS.maxMemoriesPerPlan(); index++) {
            builder.declareMemory(AiMemory.defined("slot_" + index, Long.class));
        }

        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void anExpiryAboveTheConfiguredCeilingIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> AiFixtures.minimal("hoarder")
                .declareMemory(AiMemory.defined("ancient", Long.class,
                        AiFixtures.BOUNDS.maxMemoryExpiryTicks() + 1L))
                .build());
    }

    @Test
    void aBuiltPlanIsRecheckedAgainstTighterBoundsAtInstallTime() {
        // A plan is built once and may be installed much later, after a reload tightened something.
        // Without this, yesterday's plan keeps installing against limits the operator removed.
        final AiPlan plan = AiFixtures.minimal("yesterday")
                .sensor(AiFixtures.sensor("look", Set.of()), 20L)
                .build();

        final AiBounds tightened = new AiBounds(3, 2, 7, 2, 2L, 10L, 11.0D, 500L, 8);

        assertAll(
                () -> assertDoesNotThrow(() -> plan.requireWithin(AiFixtures.BOUNDS)),
                () -> assertTrue(assertThrows(IllegalArgumentException.class,
                        () -> plan.requireWithin(tightened)).getMessage().contains("ai.yml"))
        );
    }

    @Test
    void aBlankPlanNameIsRefusedBecauseEveryMessageNeedsIt() {
        assertThrows(IllegalArgumentException.class, () -> AiPlan.builder("  ", AiFixtures.BOUNDS));
    }

    @Test
    void aBlankBehaviourOrSensorNameIsRefused() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AiActivityPlan.builder(AiActivity.IDLE)
                                .behaviour(0, AiFixtures.behaviour(" "))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AiFixtures.minimal("anonymous")
                                .sensor(AiFixtures.sensor("", Set.of()), 2L))
        );
    }

    @Test
    void activityLooksUpAFilledSlotAndAnswersNullForAnEmptyOne() {
        final AiPlan plan = AiFixtures.minimal("guard").build();

        assertAll(
                () -> assertEquals(AiActivity.IDLE, plan.activity(AiActivity.IDLE).getActivity()),
                () -> assertEquals(null, plan.activity(AiActivity.FIGHT))
        );
    }

    @Test
    void clearingVanillaGoalsCanBeTurnedOff() {
        assertFalse(AiFixtures.minimal("coexist").clearVanillaGoals(false).build()
                .clearsVanillaGoals());
    }
}
