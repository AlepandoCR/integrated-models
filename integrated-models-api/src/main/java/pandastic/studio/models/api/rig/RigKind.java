package pandastic.studio.models.api.rig;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * What a {@link Rig} is for, and therefore which parts of the format it may use.
 *
 * <p>A kind is <em>not</em> a style of movement and it is not a tuning value: every kind runs the same
 * solver over the same pose. It exists because two parts of the format only mean something for one
 * sort of rig, and a format where every key is legal everywhere is a format where a key in the wrong
 * file does nothing and says nothing. {@link #VEHICLE} is the only kind that may declare
 * {@code seats:}, because a seat is a place a player sits on a thing that moves, and a creature's rig
 * declaring one would describe a vehicle without being one.
 *
 * <p>Deliberately not configurable, in either direction: the set of kinds is a property of what this
 * plugin can do with a rig, not of a server, and {@link #CREATURE} is the default because it is the
 * kind that uses none of the kind-gated sections — so a file written before this key existed means
 * exactly what it meant then.
 */
public enum RigKind {

    /**
     * A rig bound to a living thing: the default, and the only kind the gait layer drives.
     *
     * <p>Chosen as the default because it is the least committal of the three. Every rig written before
     * this key existed is a creature rig in everything but the word, and reading one as anything else
     * would change the meaning of a file nobody edited.
     */
    CREATURE,

    /**
     * A rig bound to something a player rides, and the only kind permitted a {@code seats:} section.
     *
     * <p>The riding itself is not this type's business — a seat says where a rider goes, the vehicle
     * layer decides what happens when they get there.
     */
    VEHICLE,

    /**
     * A rig with no entity under it: a banner, a lamp, a tree. Driven by the ambient field rather than
     * by anything's movement.
     */
    DECORATION;

    /**
     * Parses the spelling a rig file uses.
     *
     * <p>Lenient about case and about {@code -} and {@code _}, like every other option value in the rig
     * format, because the only thing a reader gains from being strict here is a rejected file.
     *
     * @param value the authored spelling
     * @throws IllegalArgumentException if the value names no kind. A rig <em>file</em> gets a
     *                                  diagnostic and the default instead; this throw is for a
     *                                  programmatic caller, who has a bug rather than a typo
     */
    public static @NotNull RigKind parse(@NotNull String value) {
        final String canonical = value.trim().toLowerCase(Locale.ROOT)
                .replace("-", "").replace("_", "");
        for (final RigKind kind : values()) {
            if (kind.name().toLowerCase(Locale.ROOT).equals(canonical)) {
                return kind;
            }
        }
        throw new IllegalArgumentException(
                "Unknown rig kind '" + value + "'; expected " + names());
    }

    /** Every kind's authored spelling, for a message that lists the options. */
    public static @NotNull String names() {
        final StringBuilder names = new StringBuilder();
        for (final RigKind kind : values()) {
            if (!names.isEmpty()) {
                names.append(", ");
            }
            names.append(kind.name().toLowerCase(Locale.ROOT));
        }
        return names.toString();
    }

    /** This kind's spelling in a rig file, which is its name in lower case. */
    public @NotNull String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a rig of this kind may declare seats.
     *
     * <p>A method rather than a comparison at every call site, so that the one place the rule lives is
     * the place a reader looks for it.
     */
    public boolean permitsSeats() {
        return this == VEHICLE;
    }
}
