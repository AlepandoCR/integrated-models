package pandastic.studio.models.api.vehicle;

/**
 * What the rider's left and right keys do.
 *
 * <p>The only genuinely ambiguous half of a control scheme, and the one that cannot be inferred from a
 * vehicle's numbers. A horse, a cart and a mech all accelerate and brake the same way; what tells them
 * apart is whether holding left turns the whole vehicle or slides it sideways. Both are correct for some
 * vehicle, neither can be derived from the other, and a vehicle that guesses wrong is a vehicle the
 * rider fights.
 *
 * <p>Part of a {@link VehicleProfile} rather than of the operator's configuration, because it is a
 * statement about <em>this</em> vehicle rather than about the server. A plugin shipping a hovercraft and
 * a horse needs both at once.
 */
public enum ControlScheme {

    /**
     * Left and right turn the vehicle; it only ever moves along its own facing.
     *
     * <p>What a wheeled or legged vehicle does, and what a rider expects by default because it is what
     * every vanilla mount does.
     */
    TURNING,

    /**
     * Left and right move the vehicle sideways; its facing is left alone.
     *
     * <p>For something that has no front — a hovering platform, a lift — or whose facing is driven by
     * something other than the rider's keys.
     */
    STRAFING
}
