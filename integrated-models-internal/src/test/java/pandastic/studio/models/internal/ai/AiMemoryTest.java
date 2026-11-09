package pandastic.studio.models.internal.ai;

import pandastic.studio.models.api.ai.AiMemory;
import pandastic.studio.models.api.ai.AiPoint;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The memory value type, whose whole job is to make two silent server failures unrepresentable.
 *
 * <p>Each case here corresponds to one of them. A key the server cannot turn into an identifier would be
 * accepted by this layer and refused below the NMS seam; a value type that is not safe to hold across a
 * tick boundary would work until the creature changed region; and two memories sharing a key would share
 * one slot on the server, which is what makes the equality contract load-bearing rather than decorative.
 */
class AiMemoryTest {

    @Test
    void aBareKeyIsGivenTheServersOwnNamespace() {
        assertEquals("minecraft:alerted", AiMemory.defined("alerted", Boolean.class).getKey());
    }

    @Test
    void aKeyIsLowerCasedRatherThanRefused() {
        // The server's identifiers are lower case, and an operator or author writing Alerted meant
        // alerted. Normalising is kinder than refusing and cannot be ambiguous.
        assertEquals("pandastic:alerted",
                AiMemory.defined("Pandastic:Alerted", Boolean.class).getKey());
    }

    @Test
    void aKeyWithACharacterTheServerCannotEncodeIsRefused() {
        final List<String> rejected = List.of("", ":", "no spaces", "pandastic:", ":alerted",
                "pandastic:alert!", "pand astic:alerted", "pandastic:a/b:c");

        assertAll(rejected.stream().map(key -> () -> assertThrows(IllegalArgumentException.class,
                () -> AiMemory.defined(key, Boolean.class),
                () -> "'" + key + "' is not a valid identifier and must be refused here, because it"
                        + " would be refused below the NMS seam instead")));
    }

    @Test
    void aPathMayContainASlashAndANamespaceMayNot() {
        assertAll(
                () -> assertEquals("minecraft:patrol/waypoint",
                        AiMemory.defined("patrol/waypoint", AiPoint.class).getKey()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AiMemory.defined("pand/astic:waypoint", AiPoint.class))
        );
    }

    @Test
    void everyPermittedValueTypeIsAccepted() {
        assertAll(AiMemory.VALUE_TYPES.stream().map(type -> () ->
                assertEquals("minecraft:slot", AiMemory.defined("slot", type).getKey(),
                        () -> type.getSimpleName() + " is in VALUE_TYPES and must be accepted")));
    }

    @Test
    void aPlatformObjectIsRefusedAsAValueTypeWithTheReasonInTheMessage() {
        // Object stands in for anything mutable or world-bound. The point of the case is the message:
        // a caller who tried to remember an entity needs to be told what to remember instead.
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> AiMemory.defined("target", Object.class));

        assertAll(
                () -> assertTrue(refused.getMessage().contains("outlives the tick"),
                        () -> "the message must say why, not just that: " + refused.getMessage()),
                () -> assertTrue(refused.getMessage().contains("UUID"),
                        () -> "the message must list what is accepted: " + refused.getMessage())
        );
    }

    @Test
    void aVanillaReferenceIsPresenceOnlyAndHoldsVoid() {
        final AiMemory<Void> attackTarget = AiMemory.vanilla("attack_target");

        assertAll(
                () -> assertTrue(attackTarget.isVanilla()),
                () -> assertEquals(AiMemory.Origin.VANILLA, attackTarget.getOrigin()),
                () -> assertEquals(Void.class, attackTarget.getValueType()),
                // The point of Void: there is no value a caller could pass to set(), so the restriction
                // costs a compile error rather than a runtime one. Nothing to assert beyond the type.
                () -> assertEquals("minecraft:attack_target", attackTarget.getKey())
        );
    }

    @Test
    void aNegativeExpiryIsRefusedBecauseThereIsAWordForNoExpiry() {
        assertThrows(IllegalArgumentException.class,
                () -> AiMemory.defined("cooldown", Long.class, -1L));
    }

    @Test
    void twoMemoriesWithOneKeyAreEqualBecauseTheServerGivesThemOneSlot() {
        // Load-bearing: AiPlan relies on this to notice that a plan spelled one key two ways, which is
        // the only remaining way a typed read can come back wrong.
        assertAll(
                () -> assertEquals(AiMemory.defined("cooldown", Long.class),
                        AiMemory.defined("cooldown", Integer.class)),
                () -> assertEquals(AiMemory.defined("cooldown", Long.class).hashCode(),
                        AiMemory.defined("cooldown", Integer.class).hashCode()),
                () -> assertNotEquals(AiMemory.defined("cooldown", Long.class),
                        AiMemory.defined("other", Long.class))
        );
    }

    @Test
    void castNarrowsAndRefusesTheWrongType() {
        final AiMemory<UUID> target = AiMemory.defined("target", UUID.class);
        final UUID value = UUID.randomUUID();

        assertAll(
                () -> assertEquals(value, target.cast(value)),
                () -> assertEquals(null, target.cast(null)),
                () -> assertThrows(ClassCastException.class, () -> target.cast("not a uuid"))
        );
    }

    @Test
    void aDefinedMemoryIsNotVanillaAndDefaultsToNoExpiry() {
        final AiMemory<Boolean> alerted = AiMemory.defined("alerted", Boolean.class);

        assertAll(
                () -> assertFalse(alerted.isVanilla()),
                () -> assertEquals(AiMemory.NEVER_EXPIRES, alerted.getDefaultExpiryTicks())
        );
    }
}
