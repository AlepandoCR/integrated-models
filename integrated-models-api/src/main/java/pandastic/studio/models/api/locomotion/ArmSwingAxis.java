package pandastic.studio.models.api.locomotion;

/**
 * The local rotation axis on which a rig's arms swing.
 *
 * <p>This is an authored-model property: the same walk cycle can require a different local axis when
 * a modeller rotates the arm bones in the asset.
 */
public enum ArmSwingAxis {
    PITCH,
    YAW,
    ROLL;

    /**
     * Parses the spelling used by rig files.
     *
     * @throws IllegalArgumentException if the value is not an axis name
     */
    public static ArmSwingAxis parse(String value) {
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "pitch" -> PITCH;
            case "yaw" -> YAW;
            case "roll" -> ROLL;
            default -> throw new IllegalArgumentException(
                    "Unknown arm swing axis '" + value + "'; expected pitch, yaw or roll");
        };
    }
}
