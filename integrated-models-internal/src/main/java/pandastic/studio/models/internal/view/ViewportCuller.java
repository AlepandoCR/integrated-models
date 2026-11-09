package pandastic.studio.models.internal.view;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One culling pass: decides, for every (model, viewer) pair, whether the viewer should have the
 * model's bones, and applies the differences.
 *
 * <h2>Entirely off the tick, and nothing is handed back to it</h2>
 * A pass reads only immutable {@link ViewerSnapshot}s and the positions models published for
 * themselves, and writes only through {@link ModelViewportSink}, whose work is packets. Packets are
 * region-independent on a regionised server, so no part of this needs the main thread or a scheduling
 * hop. The single piece of region-thread work in the whole layer is a player reading their own
 * position, which is the one thing that cannot legally happen anywhere else.
 *
 * <h2>Hides are free, shows are not</h2>
 * Taking a model away sends one despawn and then saves every subsequent update, so hides are applied
 * unconditionally. Showing one re-sends every bone at full state — see {@link ModelViewportSink} for
 * why it cannot be a resume — so shows are metered by
 * {@link ViewSettings#spawnBudgetPerViewer()} and the <strong>nearest are served first</strong>.
 *
 * <p>That ordering is the point of the budget rather than a detail of it. A player teleporting into a
 * crowded area wants the model they are about to walk into, not the one at the edge of the range; and
 * without a budget they would get every model at once, which for a sixty-bone rig is several kilobytes
 * each in a single burst. Deferred shows are picked up by the next pass, so the effect is a drip rather
 * than a loss.
 *
 * <h2>Stateless</h2>
 * All the memory lives in each model's {@link ModelViewport}, so one culler instance serves the whole
 * server and a pass can run from whichever thread the simulation loop happens to be on.
 */
public final class ViewportCuller {

    /**
     * Runs a pass.
     *
     * @param models   every model eligible for culling
     * @param viewers  the current snapshot of who is where, keyed by player id
     * @param settings the live configuration, read once by the caller for the whole pass
     * @return what the pass did, for diagnostics
     */
    public @NotNull Result pass(
            @NotNull Collection<? extends CullableModel> models,
            @NotNull Map<UUID, ViewerSnapshot> viewers,
            @NotNull ViewSettings settings
    ) {
        int shown = 0;
        int hidden = 0;
        int deferred = 0;
        int forgotten = 0;

        // A viewer who left is dropped without a packet: their client is gone, so despawning for them
        // would be a send to nobody — but leaving them recorded would both leak the id and, if they
        // reconnect, have the model believe they already hold it. That is the one state that never
        // repairs itself, because a model only ever sends deltas to a viewer it thinks is current.
        for (final CullableModel model : models) {
            for (final UUID viewer : List.copyOf(model.viewport().viewers())) {
                if (!viewers.containsKey(viewer)) {
                    model.viewport().forget(viewer);
                    forgotten++;
                }
            }
        }

        for (final ViewerSnapshot viewer : viewers.values()) {
            final List<Candidate> candidates = new ArrayList<>();

            for (final CullableModel model : models) {
                final boolean alreadyShown = model.viewport().isShownTo(viewer.playerId());
                final double distanceSquared = viewer.distanceSquaredTo(
                        model.worldId(), model.cullX(), model.cullY(), model.cullZ());
                final boolean wanted = shouldBeShown(model, viewer, settings, alreadyShown);

                if (wanted == alreadyShown) {
                    continue;
                }
                if (wanted) {
                    candidates.add(new Candidate(model, distanceSquared));
                } else if (model.viewport().markHidden(viewer.playerId())) {
                    model.viewportSink().hide(viewer.playerId());
                    hidden++;
                }
            }

            if (candidates.isEmpty()) {
                continue;
            }

            // Nearest first, then by model id so a pass over the same state does the same thing twice —
            // a budgeted pass that picked an arbitrary subset would make a bug here unreproducible.
            candidates.sort(Comparator
                    .comparingDouble(Candidate::distanceSquared)
                    .thenComparing(candidate -> candidate.model().modelId()));

            int budget = settings.spawnBudgetPerViewer();
            for (final Candidate candidate : candidates) {
                if (budget == 0) {
                    deferred++;
                    continue;
                }
                if (candidate.model().viewport().markShown(viewer.playerId())) {
                    candidate.model().viewportSink().show(viewer.playerId());
                    shown++;
                    budget--;
                }
            }
        }

        return new Result(shown, hidden, deferred, forgotten);
    }

    /**
     * The decision for one pair, and the only place the two zones meet.
     *
     * <p>Pure, and exposed so it can be tested directly: every interesting case here is a boundary, and
     * boundaries are much easier to pin without a pass around them.
     *
     * @param alreadyShown selects the hysteresis side — a shown model is kept until it is a clear
     *                     margin outside both the range and the angle it was admitted at
     */
    public static boolean shouldBeShown(
            @NotNull CullableModel model,
            @NotNull ViewerSnapshot viewer,
            @NotNull ViewSettings settings,
            boolean alreadyShown
    ) {
        if (!model.isCullable()) {
            return false;
        }

        final double distanceSquared = viewer.distanceSquaredTo(
                model.worldId(), model.cullX(), model.cullY(), model.cullZ());

        // Another world, or beyond the range. Infinity from a world mismatch falls out here.
        final double range = alreadyShown
                ? settings.exitRangeSquared(viewer)
                : settings.enterRangeSquared(viewer);
        if (distanceSquared > range) {
            return false;
        }

        // The near zone: close enough that direction is not considered at all. See ViewSettings for
        // why this is what makes the angular test safe rather than merely cheaper.
        if (distanceSquared <= settings.nearRadiusSquared()) {
            return true;
        }

        final double radius = model.cullRadius() > 0D
                ? model.cullRadius()
                : settings.defaultModelRadiusBlocks();
        return settings.isWithinViewCone(
                viewer.axisProjectionTo(model.cullX(), model.cullY(), model.cullZ()),
                Math.sqrt(distanceSquared),
                radius,
                alreadyShown);
    }

    /**
     * What a pass did.
     *
     * @param shown     models handed to a viewer, each a full-state send
     * @param hidden    models taken away
     * @param deferred  shows the budget postponed to the next pass
     * @param forgotten viewer entries dropped because the player is gone
     */
    public record Result(int shown, int hidden, int deferred, int forgotten) {

        /** Whether this pass changed anything, which is the common case to skip logging. */
        public boolean isIdle() {
            return this.shown == 0 && this.hidden == 0 && this.deferred == 0 && this.forgotten == 0;
        }
    }

    /** A model wanting to be shown to one viewer, with the distance that orders it. */
    private record Candidate(@NotNull CullableModel model, double distanceSquared) {
    }
}
