package pandastic.studio.models.nms;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.jetbrains.annotations.NotNull;

/**
 * A compile-time canary: it exists so that a broken NMS toolchain is a compile error in a file whose
 * name says what went wrong, rather than a mystery in the first class that happens to need NMS.
 *
 * <h2>Why class references alone would prove nothing</h2>
 * Spigot mappings also give classes readable names — {@code net.minecraft.world.entity.Mob} resolves
 * under both mapping sets. What Spigot mappings do <em>not</em> give is readable <em>member</em>
 * names. So the only reference that distinguishes a Mojang-mapped classpath from a Spigot-mapped one
 * is a method call, which is why this class calls {@link Mob#getGoalSelector()} and
 * {@link net.minecraft.world.entity.LivingEntity#getBrain()} rather than merely naming their types.
 * If paperweight ever hands this module a Spigot-mapped server, those two lines stop compiling and
 * nothing else has to.
 *
 * <h2>Why it covers both the goal selector and the brain</h2>
 * They are the two mutually exclusive ways a mob can be driven, and the AI layer needs both: the
 * brain to express activities, sensors and memories, and the goal selector because a mob that still
 * has vanilla goals will fight whatever the brain decides. Probing one would leave the other to
 * discover its own breakage later.
 *
 * <h2>It is not expected to run</h2>
 * {@link #describe(Mob)} is reachable and correct, but nothing calls it. The value is in the
 * compilation, and a method is the cheapest way to force real member resolution — a field
 * declaration would be elided by nothing, but a call site is unambiguous about what it needs.
 */
public final class MojangMappingProbe {

    private MojangMappingProbe() {
        throw new AssertionError("No instances.");
    }

    /**
     * Names the two driver types the given mob actually has.
     *
     * <p>Diagnostic only, and safe to call from a command or a debug path: it reads two fields and
     * allocates a string. Like everything below the NMS seam it must be called from the thread that
     * owns the mob — on Folia, the owning region's — because Folia has no strict thread-check mode
     * and a foreign-region read of an entity fails silently rather than throwing.
     *
     * @param mob the mob to inspect
     * @return the mapped names of its goal selector and brain implementations
     */
    @NotNull
    public static String describe(@NotNull Mob mob) {
        final GoalSelector goals = mob.getGoalSelector();
        final Brain<?> brain = mob.getBrain();
        return "goalSelector=" + goals.getClass().getName() + " brain=" + brain.getClass().getName();
    }
}
