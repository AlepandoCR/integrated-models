package pandastic.studio.models.api.math;

/**
 * The one numerical tolerance {@link TwoBoneIk} needs: how close a target has to be to the limb's root
 * before it counts as coincident with it.
 *
 * <p>Configurable because it is a distance in blocks and the models it is applied to are not all the same
 * size. On a player-proportioned humanoid a tenth of a millimetre is comfortably below anything a foot
 * target can legitimately reach; on a rig authored at a fortieth of that scale the same figure swallows
 * the whole leg, and the limb folds flat whenever it should merely be bent.
 *
 * @param degenerateTargetBlocks distance below which the target is treated as sitting on the root and the
 *                               limb folds completely. Without the guard {@code atan2(0, 0)} decides the
 *                               limb's direction, which is to say nothing does
 */
public record IkTolerance(float degenerateTargetBlocks) {

    public IkTolerance {
        if (!Float.isFinite(degenerateTargetBlocks) || degenerateTargetBlocks <= 0F) {
            // Zero leaves atan2(0, 0) reachable, which is the exact case this value exists to exclude.
            throw new IllegalArgumentException(
                    "degenerateTargetBlocks must be finite and positive but was "
                            + degenerateTargetBlocks);
        }
    }
}
