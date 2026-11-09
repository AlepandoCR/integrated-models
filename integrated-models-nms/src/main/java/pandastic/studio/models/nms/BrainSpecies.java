package pandastic.studio.models.nms;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.animal.frog.Tadpole;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.animal.nautilus.Nautilus;
import net.minecraft.world.entity.animal.nautilus.ZombieNautilus;
import net.minecraft.world.entity.animal.sniffer.Sniffer;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.npc.villager.Villager;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The species whose own tick ticks their brain.
 *
 * <h2>Why this list has to exist, and why it is the most important check in the adapter</h2>
 * Every living entity on the server <em>has</em> a brain field. Only about twenty of them ever ask it
 * anything: a brain is ticked from the species' own AI step, and a species that was written around goals
 * simply never calls it. So installing a plan on a zombie succeeds, produces a correct brain, replaces
 * the field, and has no effect whatsoever — the creature carries on being driven by its goals, nothing
 * is logged, and the only symptom is a plan that appears not to work.
 *
 * <p>That is the worst failure available in this area, which is why the check is a hard refusal rather
 * than a warning, and why the list is twenty {@code Class} literals rather than something cleverer.
 * There is no reflective way to ask "does this species tick its brain" — the call is inside an override
 * of an AI step — so the only honest implementation is to name them, and naming them in Java means the
 * day a version removes one of these classes this module stops compiling. That is the right place to
 * find out.
 *
 * <h2>Why the concrete classes and not their abstract supertypes</h2>
 * {@code AbstractPiglin} and {@code AbstractNautilus} would each collapse two entries, and
 * {@code AbstractVillager} looks like it would collapse a third. It would not: a wandering trader is an
 * {@code AbstractVillager} and is goal-driven, so the shortcut would quietly readmit exactly the case
 * this class exists to refuse. Listing the concrete species keeps the list equal to the verified fact
 * rather than to a hierarchy that happens to overlap it.
 *
 * <h2>Nothing here is tunable</h2>
 * This is a property of the server, not a preference. An operator who could add a species to it would be
 * configuring a silent no-op.
 */
final class BrainSpecies {

    /**
     * The brain-driven species, in no particular order.
     *
     * <p>Held as classes rather than tested with a chain of {@code instanceof} so that
     * {@link #names()} can be derived from the same list the check consults — a separately maintained
     * list of names would go stale exactly when a species was added and the refusal message became
     * wrong.
     */
    private static final @NotNull List<Class<? extends LivingEntity>> BRAIN_DRIVEN = List.of(
            Villager.class,
            Piglin.class,
            PiglinBrute.class,
            Warden.class,
            Allay.class,
            Axolotl.class,
            Frog.class,
            Tadpole.class,
            Goat.class,
            Camel.class,
            Breeze.class,
            Creaking.class,
            Hoglin.class,
            Zoglin.class,
            Sniffer.class,
            Armadillo.class,
            HappyGhast.class,
            CopperGolem.class,
            Nautilus.class,
            ZombieNautilus.class);

    /** Sorted once, because the refusal message reads better alphabetically and never changes. */
    private static final @NotNull List<String> NAMES = BRAIN_DRIVEN.stream()
            .map(Class::getSimpleName)
            .sorted()
            .toList();

    private BrainSpecies() {
        throw new AssertionError("No instances.");
    }

    /**
     * Whether {@code entity} is one of them.
     *
     * <p>A linear scan over twenty {@code Class#isInstance} calls, which is cheaper than it looks and is
     * done once per install rather than per tick.
     *
     * @param entity the server-side entity to test
     * @return whether its own tick ticks its brain
     */
    static boolean isBrainDriven(@NotNull LivingEntity entity) {
        for (final Class<? extends LivingEntity> species : BRAIN_DRIVEN) {
            if (species.isInstance(entity)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Their names, for the message a refusal shows.
     *
     * @return the simple class names, sorted, immutable
     */
    static @NotNull List<String> names() {
        return NAMES;
    }
}
