package pandastic.studio.models.internal.nms;

import org.bukkit.entity.Mob;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Installing a plan's behaviour on a live creature by rebuilding its brain.
 *
 * <h2>Rebuild, because the server offers nothing else</h2>
 * A brain can have behaviour added to it and can have <em>all</em> of its behaviour cleared. There is no
 * third option: the list a single entry would have to be removed from is private and the only removal
 * the class exposes empties it. So "change what this creature does" has exactly one sanctioned
 * implementation, and it is the one the server itself uses when a villager changes profession — stop
 * everything the brain is running, read its state back out, build a new brain around that state with
 * the new behaviour in it, and put the new one in place of the old.
 *
 * <p>That is why this seam takes a whole {@link BrainInstallRequest} and returns a
 * {@link BrainInstallation}, rather than offering {@code addBehaviour} and {@code removeBehaviour} calls
 * the underlying type cannot honour. An api shaped around operations the server does not have would read
 * as the more flexible one and would be a lie.
 *
 * <h2>Three facts that shape every implementation</h2>
 * <ul>
 *   <li><strong>A brain only ticks for the species whose tick ticks it.</strong> About twenty of the
 *       server's creatures are brain-driven; the rest are driven by goals and never ask their brain
 *       anything. Installing a plan on one of those succeeds, is correct, and has no effect at all, with
 *       nothing logged — the worst failure available here. {@link #supports(Mob)} is therefore not a
 *       courtesy and an implementation re-checks it inside {@link #install} rather than trusting the
 *       caller to have asked.</li>
 *   <li><strong>A memory the brain was not built with is silently useless.</strong> Writing one is
 *       accepted and discarded; reading one throws. So the brain has to be built with exactly the set
 *       the plan declares, which is the reason {@link pandastic.studio.models.api.ai.AiPlan} derives
 *       that set instead of letting a caller list it.</li>
 *   <li><strong>Goals and brains compete.</strong> A creature that still has its goals will fight
 *       whatever the plan decides, every tick, with no diagnostic. Clearing them is part of installation
 *       rather than a separate call a caller can forget — and it is irreversible, which is why the plan
 *       gets to say whether to do it.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * {@link #install} touches a live creature, so it is owning-thread only. The seam does not schedule for
 * its caller — {@link pandastic.studio.models.internal.ai.CreatureAiImpl} already owns that decision and
 * forces it — but an implementation asserts ownership rather than trusting it, because registering is
 * the one operation in this area with no thread checking anywhere: the goal selector is a plain set and
 * the brain's maps are plain maps, so a registration from the wrong thread interleaves with the
 * creature's tick and corrupts whichever structure it landed in, silently.
 *
 * <h2>Nothing here reads configuration</h2>
 * Every bound, period and threshold arrives already applied, inside the plan or behind
 * {@link AiFaultSink}. An implementation of this interface that read a configuration file would have the
 * dependency backwards: it lives in a module that deliberately knows nothing but the server.
 */
public interface BrainBridge {

    /**
     * Whether {@code mob} is a species whose own tick ticks its brain.
     *
     * <p>Safe from any thread: it is a test of the creature's type, not of its state.
     *
     * @param mob the creature to test
     * @return whether a plan installed on it would ever be asked anything
     */
    boolean supports(@NotNull Mob mob);

    /**
     * The species {@link #supports(Mob)} accepts, named as the server names them.
     *
     * <p>For the message a refusal shows. Derived from the same list {@link #supports(Mob)} consults, so
     * the two cannot disagree — which matters because a list maintained separately would go stale
     * exactly when the server added a species and the refusal became wrong.
     *
     * @return the species names, immutable
     */
    @NotNull List<String> supportedSpecies();

    /**
     * Rebuilds {@code request}'s creature's brain around its plan and installs it.
     *
     * <p>Replaces whatever brain the creature has, including one this seam installed earlier: the caller
     * is expected to have uninstalled the previous installation first, and an implementation that finds
     * one simply replaces it rather than refusing, because the alternative is a creature that cannot be
     * reprogrammed without being killed.
     *
     * @param request the creature, the plan, and the two callbacks the adapter needs
     * @return the live installation
     * @throws IllegalStateException if called from a thread that does not own the creature, if the
     *                               creature's species is not brain-driven, if the creature is no longer
     *                               valid, or if the server's internals could not be reached — which on
     *                               this seam means the running server is not the one this adapter was
     *                               compiled against
     */
    @NotNull BrainInstallation install(@NotNull BrainInstallRequest request);
}
