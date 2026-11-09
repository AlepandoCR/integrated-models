package pandastic.studio.models.internal.nms;

import org.bukkit.entity.Mob;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.ai.AiPlan;

import java.util.Objects;

/**
 * Everything the adapter needs to rebuild one creature's brain, as one value.
 *
 * <h2>Why a request object rather than four parameters</h2>
 * Because the adapter is in a different module, compiled against a different server jar, and a change
 * to what an install needs would otherwise be a signature change the two modules have to agree about in
 * lockstep. A record adds a component and both sides keep compiling — the adapter simply does not read
 * it yet. Given that the whole point of the NMS seam is to keep an alpha-channel server jar out of the
 * rest of the build, the cheapest possible coupling across it is worth a small indirection.
 *
 * <p>It also makes the install one call. The brain can only be replaced wholesale, so an installer that
 * accepted the plan, then the context, then the faults would have to decide when the caller had
 * finished — and the answer would be a fifth method called {@code commit}.
 *
 * @param mob      the creature. Must be a species {@link BrainBridge#supports(Mob)} accepts, and the
 *                 adapter re-checks rather than trusting it: the check is one {@code instanceof} and the
 *                 failure it prevents is a plan that installs cleanly and is then never ticked
 * @param plan     what the creature should do. Already validated against the live bounds by the caller,
 *                 so the adapter treats it as correct and does no bounds work of its own
 * @param contexts how to build the object handed to behaviour bodies; see {@link AiContextFactory}
 * @param faults   where a body's exception goes, and who decides whether to keep running it
 */
public record BrainInstallRequest(
        @NotNull Mob mob,
        @NotNull AiPlan plan,
        @NotNull AiContextFactory contexts,
        @NotNull AiFaultSink faults
) {

    public BrainInstallRequest {
        Objects.requireNonNull(mob, "mob");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(contexts, "contexts");
        Objects.requireNonNull(faults, "faults");
    }

    @Override
    public @NotNull String toString() {
        return "BrainInstallRequest[" + this.plan.getName() + " on " + this.mob.getType() + "]";
    }
}
