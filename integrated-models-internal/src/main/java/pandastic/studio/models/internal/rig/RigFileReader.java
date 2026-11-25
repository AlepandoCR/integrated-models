package pandastic.studio.models.internal.rig;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.rig.AxisLimits;
import pandastic.studio.models.api.rig.ClipRole;
import pandastic.studio.models.api.rig.PhysicsKind;
import pandastic.studio.models.api.rig.PhysicsProfile;
import pandastic.studio.models.api.rig.ProfileBounds;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.rig.RigClips;
import pandastic.studio.models.api.rig.RigKind;
import pandastic.studio.models.api.rig.RigSeat;
import pandastic.studio.models.api.locomotion.ArmSwingAxis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads one rig file. Single use, not thread-safe: it accumulates diagnostics into a field so that
 * every method can complain without threading a collector through a dozen signatures.
 *
 * <p>Split out of {@link RigLoader} so that the public surface stays three entry points and a format
 * description, and so the per-file state has somewhere to live that is not a static.
 */
final class RigFileReader {

    /** The value of {@code limits} that means "no bounds at all". */
    private static final String UNBOUNDED = "unbounded";

    // Keys are canonicalised before lookup - lower-cased with '-' and '_' stripped - so that
    // `collision-radius`, `collision_radius` and `collisionRadius` are one key and a shouted key
    // still works. That is why the sets below read as one word: `collisionradius` is the canonical
    // form of the key the author writes as `collision-radius`. The *_HELP strings carry the
    // author-facing spelling, because a warning that lists canonical forms helps nobody.
    private static final Set<String> TOP_LEVEL_KEYS = Set.of(
            "name", "model", "kind", "bones", "armswingaxis", "handswingaxis", "seats", "clips");
    private static final String TOP_LEVEL_HELP =
            "name, model, kind, bones, arm-swing-axis, hand-swing-axis, seats, clips";

    private static final Set<String> BONE_KEYS = Set.of("parent", "physics", "animationowned");
    private static final String BONE_HELP = "parent, physics, animation-owned";

    private static final Set<String> SEAT_KEYS = Set.of("name", "locator", "driver", "offset");
    private static final String SEAT_HELP = "name, locator, driver, offset";

    /**
     * The keys the {@code clips} block accepts, which are exactly the locomotion states the pipeline
     * can tell itself apart from — derived from the enum rather than written out, so a role added there
     * cannot be one this reader silently calls unknown.
     */
    private static final Set<String> CLIP_KEYS = clipKeys();

    private static final Set<String> PHYSICS_KEYS = Set.of(
            "kind", "stiffness", "damping", "lag", "gravity", "mass", "wind",
            "collisionradius", "reach", "limits");
    private static final String PHYSICS_HELP =
            "kind, stiffness, damping, lag, gravity, mass, wind, collision-radius, reach, limits";

    private static final Set<String> LIMIT_KEYS = Set.of("pitch", "yaw", "roll");
    private static final String LIMIT_HELP = "pitch, yaw, roll";

    private final @NotNull String source;
    private final @NotNull RigLoadSettings settings;
    private final @NotNull List<RigDiagnostic> diagnostics = new ArrayList<>();

    /**
     * @param settings the one snapshot this whole read is performed against. Taken once, by
     *                 {@link RigLoader}, so that a reload cannot land between two bones of a file and
     *                 clamp the second against bounds the first was not judged by — a rig half-validated
     *                 against two sets of bounds is exactly the state the builder's backstop would
     *                 refuse, with the file blamed for it
     */
    RigFileReader(@NotNull String source, @NotNull RigLoadSettings settings) {
        this.source = source;
        this.settings = settings;
    }

    /** Reads the whole rig. */
    @NotNull RigLoadResult read(@NotNull ConfigurationSection root) {
        final Block top = block(root, TOP_LEVEL_KEYS, TOP_LEVEL_HELP, null, "the rig file");

        final String modelId = text(top, "model");
        if (modelId == null) {
            // Hard: a rig that names no model is a skeleton for nothing. There is no default that
            // would be less wrong than refusing, and guessing one would bind physics to whatever
            // model happened to share the file's name.
            error(null, "has no 'model' key, so there is no model for the rig to drive."
                    + " Add `model: <the id the renderer knows the model by>`.");
            return RigLoadResult.failure(this.diagnostics);
        }

        String name = text(top, "name");
        if (name == null) {
            name = fallbackName(this.source);
            warn(null, "has no 'name' key; using '" + name + "' from the file name. The name only"
                    + " ever appears in logs, so this is cosmetic - but a rig that renames itself"
                    + " when the file is renamed makes older logs harder to read.");
        }

        final Object rawBones = top.get("bones");
        if (!(rawBones instanceof ConfigurationSection boneEntries)) {
            error(null, rawBones == null
                    ? "has no 'bones' section, so it declares no skeleton at all."
                    : "has a 'bones' key that is not a mapping of bone name to options (got "
                            + describe(rawBones) + ").");
            return RigLoadResult.failure(this.diagnostics);
        }

        final List<ParsedBone> declared = readBones(boneEntries);
        if (declared.isEmpty() && !hasErrors()) {
            error(null, "declares no bones. A rig with no bones drives nothing;"
                    + " remember that a bone with no options is written `boneName: {}`.");
        }
        if (hasErrors()) {
            return RigLoadResult.failure(this.diagnostics);
        }

        final List<ParsedBone> ordered = sortTopologically(declared);
        if (ordered == null) {
            return RigLoadResult.failure(this.diagnostics);
        }

        final ArmSwingAxis armSwingAxis = readArmSwingAxis(top);
        final ArmSwingAxis handSwingAxis = readHandSwingAxis(top);
        final RigKind kind = readRigKind(top);
        final RigClips clips = readClips(top);
        // Read against every bone the file declared, not against the ones that survived the ceiling: a
        // seat naming a bone is the rigger pointing at the wrong kind of element, and that is just as
        // true of a bone the ceiling dropped.
        final List<RigSeat> seats = readSeats(top, kind, boneKeys(declared));
        if (hasErrors()) {
            return RigLoadResult.failure(this.diagnostics);
        }
        return build(name, modelId, kind, enforceBoneCeiling(ordered), armSwingAxis, handSwingAxis,
                seats, clips);
    }

    // ------------------------------------------------------------------------------------------
    // Kind
    // ------------------------------------------------------------------------------------------

    /**
     * What the rig is for, defaulting to {@link RigKind#CREATURE}.
     *
     * <p>Hard on an unknown spelling, like {@code arm-swing-axis} and unlike almost everything else in
     * this reader. The kind <em>gates</em> parts of the format rather than tuning anything, so falling
     * back after a typo would load a file that means something other than what it says — and the shape
     * of that mistake is a vehicle whose seats were all quietly discarded, which looks like a seating
     * bug rather than a spelling one.
     */
    private @NotNull RigKind readRigKind(@NotNull Block top) {
        final String value = text(top, "kind");
        if (value == null) {
            return RigKind.CREATURE;
        }
        try {
            return RigKind.parse(value);
        } catch (final IllegalArgumentException refused) {
            error(null, refused.getMessage() + ". A rig that omits 'kind' is a "
                    + RigKind.CREATURE.key() + ", which is the kind that gates nothing.");
            return RigKind.CREATURE;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Clips
    // ------------------------------------------------------------------------------------------

    /**
     * Which of the model's own animations plays in each locomotion state.
     *
     * <p>The clip names are kept exactly as the rigger typed them. Every other name in this format is
     * lower-cased, and this one must not be: it is handed to the renderer to look up a clip in a model
     * file this plugin does not own, and the clips in this repository's own models include
     * {@code standUp} and {@code lookaround}. Lower-casing would turn a correct rig into one that names
     * a clip the model does not have.
     */
    private @NotNull RigClips readClips(@NotNull Block top) {
        final Object raw = top.get("clips");
        if (raw == null) {
            return RigClips.NONE;
        }
        if (!(raw instanceof ConfigurationSection section)) {
            warn(null, "has a 'clips' key that is not a mapping of locomotion state to clip name (got "
                    + describe(raw) + "); no clip is bound. Known states: " + ClipRole.names() + ".");
            return RigClips.NONE;
        }

        final Block states = block(section, CLIP_KEYS, ClipRole.names(), null, "the 'clips' block");
        final Map<ClipRole, String> clips = new EnumMap<>(ClipRole.class);
        for (final ClipRole role : ClipRole.values()) {
            final Object value = states.get(role.key());
            if (value == null) {
                continue;
            }
            if (value instanceof ConfigurationSection || value instanceof List<?>
                    || value instanceof Map<?, ?>) {
                warn(null, "has a '" + states.spelled(role.key()) + "' clip that is not a name (got "
                        + describe(value) + "); that state is left unbound.");
                continue;
            }
            // Trimmed and otherwise untouched: case is the rigger's, not ours.
            final String clip = value.toString().trim();
            if (clip.isEmpty()) {
                warn(null, "has a blank '" + states.spelled(role.key()) + "' clip, which names no"
                        + " animation; that state is left unbound. Remove the key instead.");
                continue;
            }
            clips.put(role, clip);
        }
        // Cannot throw: every blank was filtered above, which is the only thing RigClips refuses.
        return RigClips.of(clips);
    }

    // ------------------------------------------------------------------------------------------
    // Seats
    // ------------------------------------------------------------------------------------------

    /**
     * Where riders sit, in file order.
     *
     * <p>A YAML <em>list</em> rather than a mapping, which is the one place this format departs from
     * "everything is a mapping". Riders are offered seats in the order the file declares them, so the
     * order is part of what the file says — and a sequence's order is guaranteed by the format while a
     * mapping's is a property of whichever parser read it. The list also makes a duplicate seat name
     * expressible, which is what lets it be reported rather than silently collapsed.
     *
     * @param kind     the kind the file declared. Only a vehicle may seat anybody
     * @param boneKeys every bone this file declared, so that a seat pointing at one can be reported as
     *                 the mistake it is
     */
    private @NotNull List<RigSeat> readSeats(
            @NotNull Block top,
            @NotNull RigKind kind,
            @NotNull Set<String> boneKeys
    ) {
        final Object raw = top.get("seats");
        if (raw == null) {
            return List.of();
        }
        if (!kind.permitsSeats()) {
            // Soft, and the seats go rather than the rig: the likely author error is a forgotten
            // `kind: vehicle`, and refusing the file would take the model away as well as the seats.
            // Reading it as a vehicle instead would be worse - the kind is the one thing the file does
            // state, and a creature silently promoted to a vehicle is a creature players can ride.
            warn(null, "declares 'seats' but is a " + kind.key() + " rig, so the seats are ignored."
                    + " Only a " + RigKind.VEHICLE.key() + " rig seats riders - add `kind: "
                    + RigKind.VEHICLE.key() + "` if that is what this model is.");
            return List.of();
        }
        if (!(raw instanceof List<?> entries)) {
            warn(null, "has a 'seats' key that is not a list of seats (got " + describe(raw) + ");"
                    + " no seat is declared. Write each seat as a list entry, `- locator: <locator>`:"
                    + " riders are offered seats in file order, so the order is part of what the file"
                    + " says and a list is the only form that guarantees it.");
            return List.of();
        }

        final List<RigSeat> seats = new ArrayList<>();
        final int ceiling = this.settings.seatBounds().maxSeats();
        for (final Object entry : entries) {
            final RigSeat seat = readSeat(entry, boneKeys, seats);
            if (seat == null) {
                continue;
            }
            if (seats.size() >= ceiling) {
                warn(null, "declares the seat '" + seat.name() + "' past the " + ceiling
                        + "-seat ceiling this server configures; it and any seat after it are dropped."
                        + " The seats that load are the first " + ceiling + ", because riders are"
                        + " offered them in file order.");
                break;
            }
            seats.add(seat);
        }
        return seats;
    }

    /** One seat, or {@code null} if it could not be read as one. */
    private @Nullable RigSeat readSeat(
            @Nullable Object entry,
            @NotNull Set<String> boneKeys,
            @NotNull List<RigSeat> accepted
    ) {
        // A mapping nested inside a YAML list arrives as a plain Map rather than as a
        // ConfigurationSection: the configuration layer only wraps mappings it can address by path.
        if (!(entry instanceof Map<?, ?> fields)) {
            warn(null, "has a 'seats' entry that is not a mapping of seat options (got "
                    + describe(entry) + "); it is ignored. A seat is written `- locator: <locator>`.");
            return null;
        }

        final Block seat = block(fields, SEAT_KEYS, SEAT_HELP, null, "a 'seats' entry");
        final String locator = text(seat, "locator");
        if (locator == null) {
            warn(null, "has a seat with no 'locator', so there is nowhere for the rider to sit; it is"
                    + " ignored. A seat names a locator element of the model - the empty marker a"
                    + " rigger places in Blockbench - and nothing else.");
            return null;
        }

        final String spelledName = text(seat, "name");
        // The locator's name is the obvious default and needs no second spelling in the file. It is
        // normalised the same way, so a tagged locator and the seat named after it agree.
        final String name = spelledName == null ? locator : spelledName;

        final String locatorKey = Rig.normalise(locator.trim());
        if (boneKeys.contains(locatorKey)) {
            // The mistake the seat format exists to catch. A bone is geometry with a pivot that the
            // renderer's animation pass writes every frame a clip plays, so a rider seated on one sits
            // wherever the current clip left it; a locator carries no geometry and is never animated.
            // There is deliberately no fallback to a bone - that is what would make this silent.
            warn(null, "has a seat ('" + name + "') naming '" + locator + "', which this rig declares"
                    + " as a bone rather than as a locator; the seat is dropped. A seat is a locator"
                    + " element - an empty marker with no cubes - never a bone: a bone is geometry the"
                    + " model's own animations move, so a rider on one would sit wherever the playing"
                    + " clip happens to leave it. Add a locator in Blockbench and name that.");
            return null;
        }

        final boolean wantsDriver = flag(seat, "driver", null, "seat '" + name + "'");
        boolean driver = wantsDriver;
        if (wantsDriver) {
            for (final RigSeat existing : accepted) {
                if (existing.driver()) {
                    // Soft, and the seat survives as a passenger's: a vehicle takes input from one
                    // rider, and dropping the seat would lose a place to sit over a question of who
                    // steers.
                    warn(null, "declares the seat '" + name + "' as a second driver's seat; '"
                            + existing.name() + "' already is one, so '" + name + "' is read as a"
                            + " passenger seat. A vehicle takes its input from one rider.");
                    driver = false;
                    break;
                }
            }
        }

        final float[] offset = readSeatOffset(seat, name);
        final RigSeat parsed;
        try {
            parsed = new RigSeat(name, locator, driver, offset[0], offset[1], offset[2]);
        } catch (final IllegalArgumentException refused) {
            // Backstop: the blank cases are handled above and the offsets are clamped finite, so
            // reaching this means the reader and RigSeat disagree about what is legal.
            warn(null, "has a seat the api refused (" + refused.getMessage() + "); it is dropped."
                    + " This is a loader defect, not a file one.");
            return null;
        }

        for (final RigSeat existing : accepted) {
            if (existing.name().equals(parsed.name())) {
                warn(null, "declares the seat '" + parsed.name() + "' twice; the second is dropped."
                        + " Seat names are matched case-insensitively, so a consumer asking for one"
                        + " would always get the first. Give each seat its own 'name'.");
                return null;
            }
        }
        return parsed;
    }

    /** A seat's rider offset in blocks, clamped into the configured range, defaulting to the locator. */
    private float[] readSeatOffset(@NotNull Block seat, @NotNull String seatName) {
        final float[] offset = new float[3];
        final Object raw = seat.get("offset");
        if (raw == null) {
            return offset;
        }
        if (!(raw instanceof List<?> list) || list.size() != 3) {
            warn(null, "has a seat ('" + seatName + "') whose 'offset' is not a three-element"
                    + " [x, y, z] list of blocks (got " + describe(raw) + "); the rider sits on the"
                    + " locator itself.");
            return offset;
        }

        final float limit = this.settings.seatBounds().maxSeatOffsetBlocks();
        for (int axis = 0; axis < offset.length; axis++) {
            final Float value = asFloat(list.get(axis));
            if (value == null || !Float.isFinite(value)) {
                warn(null, "has a seat ('" + seatName + "') whose 'offset' holds something that is not"
                        + " a finite number of blocks (" + describe(list.get(axis)) + "); the rider sits"
                        + " on the locator itself.");
                return new float[3];
            }
            if (value < -limit || value > limit) {
                final float clamped = Math.clamp(value.floatValue(), -limit, limit);
                warn(null, "has a seat ('" + seatName + "') offset " + value + " blocks on axis "
                        + axis + ", outside the permitted +/-" + limit + "; clamped to " + clamped
                        + ". An offset trims a locator - a rider who needs more than this has a locator"
                        + " in the wrong place, and that is fixed in Blockbench.");
                offset[axis] = clamped;
                continue;
            }
            offset[axis] = value;
        }
        return offset;
    }

    // ------------------------------------------------------------------------------------------
    // Bones
    // ------------------------------------------------------------------------------------------

    /** Every bone in declaration order, each already carrying its compiled physics. */
    private @NotNull List<ParsedBone> readBones(@NotNull ConfigurationSection entries) {
        final List<ParsedBone> parsed = new ArrayList<>();
        final Map<String, String> spelledAs = new LinkedHashMap<>();

        for (final Map.Entry<String, Object> entry : entries.getValues(false).entrySet()) {
            final String spelling = entry.getKey();
            if (spelling.isBlank()) {
                error(null, "declares a bone with a blank name.");
                continue;
            }
            final String key = Rig.normalise(spelling.trim());

            final String previous = spelledAs.putIfAbsent(key, spelling);
            if (previous != null) {
                // Hard: the two declarations are one bone and carry two tunings. Picking either is a
                // guess about which the author meant, and the wrong guess is a rig that behaves
                // differently from the file in front of them.
                error(spelling, "collides with '" + previous + "': bone names are matched"
                        + " case-insensitively, so these are the same bone declared twice, and the"
                        + " loader cannot know which of the two tunings you meant.");
                continue;
            }
            parsed.add(readBone(spelling, key, entry.getValue()));
        }
        return parsed;
    }

    private @NotNull ParsedBone readBone(
            @NotNull String spelling,
            @NotNull String key,
            @Nullable Object value
    ) {
        if (!(value instanceof ConfigurationSection section)) {
            // Soft: whatever the author meant, the bone's children named it as their parent. Keeping
            // it as a root pass-through keeps them attached; dropping it would orphan the chain.
            warn(spelling, "is not a mapping of options (got " + describe(value) + "), so it stays"
                    + " in the rig as a pass-through anchor with no parent and no physics."
                    + " Write `" + spelling + ": {}` for a bone that needs no options.");
            return new ParsedBone(key, spelling, null, null, false);
        }

        final Block bone = block(section, BONE_KEYS, BONE_HELP, spelling, "bone '" + spelling + "'");
        final PhysicsProfile physics = readPhysics(bone, spelling);
        final boolean animationOwned =
                flag(bone, "animationowned", spelling, "bone '" + spelling + "'");
        if (animationOwned && physics != null) {
            // Worth a warning precisely because it is legal. Both layers write the same display: the
            // renderer sends bind o animation for whatever clip is playing, this plugin sends
            // bind o physics, and the display keeps whichever packet arrived last in the frame. The
            // author sees a bone flicking between two poses and has no reason to suspect the rig file.
            warn(spelling, "carries " + physics.kind().name().toLowerCase(Locale.ROOT) + " physics and"
                    + " is marked animation-owned. Both write the same bone: the model's own clip sends"
                    + " one pose and this plugin's physics sends another, in the same frame, and the"
                    + " last one there wins - which reads as a bone that flickers. Either drop the"
                    + " physics on this bone or take 'animation-owned' off it, unless you know the clip"
                    + " and the physics move it on different axes.");
        }
        return new ParsedBone(key, spelling, text(bone, "parent"), physics, animationOwned);
    }

    // ------------------------------------------------------------------------------------------
    // Physics
    // ------------------------------------------------------------------------------------------

    /**
     * One bone's physics, or {@code null} for a pass-through bone.
     *
     * <p>Two tiers of leniency, because the two failures are not alike. A broken <em>structure</em> —
     * no kind, an unknown kind, a block that is not a mapping — means nothing can be integrated, so
     * the physics goes and the bone stays. A broken <em>value</em> inside an otherwise readable block
     * costs only that value, which falls back to the configured seed or is clamped into range. Both
     * warn; neither takes the bone out of the hierarchy.
     */
    private @Nullable PhysicsProfile readPhysics(@NotNull Block bone, @NotNull String boneName) {
        final Object raw = bone.get("physics");
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof ConfigurationSection section)) {
            warn(boneName, "has a 'physics' key that is not a mapping of options (got "
                    + describe(raw) + "); it keeps its place in the hierarchy as a pass-through bone.");
            return null;
        }

        final Block physics = block(section, PHYSICS_KEYS, PHYSICS_HELP, boneName,
                "the physics block of bone '" + boneName + "'");

        final PhysicsKind kind = readKind(physics, boneName);
        if (kind == null) {
            return null;
        }
        warnKeysThisKindIgnores(physics, kind, boneName);

        // The same bounds object Rig.Builder will validate against, so what the loader clamps to is
        // what the builder accepts — and, since the api's builder seeds its default limits from them,
        // so that an unspecified axis bound is the operator's figure rather than a second one.
        final ProfileBounds bounds = this.settings.profileBounds();

        // Read back through a built profile rather than straight off the configured defaults, because
        // what an omitted key should fall back to is the *effective* value of the coefficient - the
        // reach already normalised, the limits already resolved, the gravity already selected for this
        // kind - which is also what the stability clamp below needs. RigLoadSettings has checked that
        // the bounds admit the defaults, so this build cannot be the one that fails.
        final PhysicsProfile defaults =
                PhysicsProfile.builder(kind, bounds, this.settings.profileDefaults()).build();

        final float mass = coefficient(physics, "mass", defaults.mass(),
                bounds.minMass(), bounds.maxMass(), boneName);
        final float damping = stabilise(
                coefficient(physics, "damping", defaults.damping(), 0F, 1F, boneName),
                mass, kind, boneName);
        final float stiffness = coefficient(physics, "stiffness", defaults.stiffness(), 0F, 1F, boneName);
        final float lag = coefficient(physics, "lag", defaults.lag(), 0F, 1F, boneName);
        final float gravity = coefficient(physics, "gravity", defaults.gravity(),
                0F, bounds.maxGravity(), boneName);
        final float wind = coefficient(physics, "wind", defaults.wind(), 0F, bounds.maxWind(), boneName);
        final float collisionRadius = coefficient(physics, "collisionradius", defaults.collisionRadius(),
                0F, bounds.maxCollisionRadius(), boneName);
        final float[] reach = readReach(physics, boneName, defaults);
        final AxisLimits limits = readLimits(physics, boneName, defaults.limits());

        try {
            return PhysicsProfile.builder(kind, bounds, this.settings.profileDefaults())
                    .stiffness(stiffness)
                    .damping(damping)
                    .lag(lag)
                    .gravity(gravity)
                    .mass(mass)
                    .wind(wind)
                    .collisionRadius(collisionRadius)
                    .reach(reach[0], reach[1], reach[2])
                    .limits(limits)
                    .build();
        } catch (final IllegalArgumentException refused) {
            // Backstop. Every range above is clamped and every vector checked, so reaching this means
            // the loader and PhysicsProfile disagree about what is legal - a defect in one of them.
            // Losing the physics still beats losing the rig, and the message says where to look.
            warn(boneName, "has physics the api refused even after clamping (" + refused.getMessage()
                    + "); it stays as a pass-through bone. This is a loader defect, not a file one.");
            return null;
        }
    }

    private @Nullable PhysicsKind readKind(@NotNull Block physics, @NotNull String boneName) {
        final Object raw = physics.get("kind");
        if (raw == null) {
            warn(boneName, "has a physics block with no 'kind', and how a bone is integrated is the"
                    + " one thing that cannot be guessed; it stays as a pass-through bone."
                    + " Expected one of " + kindNames() + ".");
            return null;
        }
        final String canonical = canonical(raw.toString());
        for (final PhysicsKind kind : PhysicsKind.values()) {
            if (canonical(kind.name()).equals(canonical)) {
                return kind;
            }
        }
        warn(boneName, "names an unknown physics kind '" + raw + "'; it stays as a pass-through bone."
                + " Expected one of " + kindNames() + ".");
        return null;
    }

    /**
     * Warns about tuning the chosen kind never reads.
     *
     * <p>Worth a warning precisely because it is not an error: the rig loads, the value sits in the
     * file, and the author spends an afternoon tuning a number nothing consumes.
     */
    private void warnKeysThisKindIgnores(
            @NotNull Block physics,
            @NotNull PhysicsKind kind,
            @NotNull String boneName
    ) {
        final Set<String> ignored = switch (kind) {
            // Drag has no spring and no velocity term, so three of the coefficients are inert.
            case DRAG -> Set.of("stiffness", "damping", "gravity", "collisionradius");
            // Lag drives drag and nothing else; the clearance cone is folded in for cloth only.
            case PENDULUM, SPRING -> Set.of("lag", "collisionradius");
            case CLOTH_STRIP -> Set.of("lag");
        };
        for (final String key : ignored) {
            if (physics.has(key)) {
                warn(boneName, "sets '" + physics.spelled(key) + "', which physics kind "
                        + kind.name().toLowerCase(Locale.ROOT) + " does not read; it has no effect.");
            }
        }
    }

    /**
     * A coefficient, clamped into {@code [min, max]} with a warning, or the configured seed if it is
     * not a number at all.
     */
    private float coefficient(
            @NotNull Block physics,
            @NotNull String key,
            float fallback,
            float min,
            float max,
            @NotNull String boneName
    ) {
        final Object raw = physics.get(key);
        if (raw == null) {
            return fallback;
        }
        final Float parsed = asFloat(raw);
        if (parsed == null || Float.isNaN(parsed)) {
            warn(boneName, "has a '" + physics.spelled(key) + "' that is not a number ("
                    + describe(raw) + "); using the default " + fallback + ".");
            return fallback;
        }
        final float value = parsed;
        if (value < min || value > max) {
            final float clamped = Math.clamp(value, min, max);
            warn(boneName, "has '" + physics.spelled(key) + "' " + value + ", outside the permitted"
                    + " range [" + min + ", " + max + "]; clamped to " + clamped + ".");
            return clamped;
        }
        return value;
    }

    /**
     * Damping, held strictly below the integrator's stability bound.
     *
     * <p>This is the clamp that justifies the whole clamp-and-warn policy. Staying inside every
     * documented range is not enough on its own: the velocity update is explicit, so once damping
     * passes mass the bone's velocity flips sign and grows every step, and the author sees a limb that
     * detonates rather than a value out of range. The bound is the solver's to know, which is why it
     * publishes it instead of only refusing — and why it is asked of the solver that will actually run
     * this rig rather than computed from a number copied out here.
     */
    private float stabilise(
            float damping,
            float mass,
            @NotNull PhysicsKind kind,
            @NotNull String boneName
    ) {
        if (kind == PhysicsKind.DRAG) {
            // Exponential lag has no velocity to diverge, so the bound does not apply and warning
            // about a value drag ignores anyway would be noise.
            return damping;
        }
        final int substepRateHz = this.settings.substepRateHz();
        final float ceiling = this.settings.solver().maxStableDamping(mass, substepRateHz)
                * this.settings.stabilityMargin();
        if (damping <= ceiling) {
            return damping;
        }
        warn(boneName, "has damping " + damping + ", which is past the integrator's stability bound"
                + " for mass " + mass + " at " + substepRateHz + " Hz - damping has to stay below"
                + " mass, or the bone gains energy every step until it is NaN; clamped to "
                + ceiling + ". Raise mass if you want this much damping.");
        return ceiling;
    }

    /** The direction the bone extends along, defaulting to the configured seed. */
    private float[] readReach(
            @NotNull Block physics,
            @NotNull String boneName,
            @NotNull PhysicsProfile defaults
    ) {
        final float[] fallback = {defaults.reachX(), defaults.reachY(), defaults.reachZ()};
        final Object raw = physics.get("reach");
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof List<?> list) || list.size() != 3) {
            warn(boneName, "has a 'reach' that is not a three-element [x, y, z] list in model space"
                    + " (got " + describe(raw) + "); using the default " + vector(fallback) + ".");
            return fallback;
        }

        final float[] reach = new float[3];
        for (int axis = 0; axis < reach.length; axis++) {
            final Float value = asFloat(list.get(axis));
            if (value == null || !Float.isFinite(value)) {
                warn(boneName, "has a 'reach' holding something that is not a finite number ("
                        + describe(list.get(axis)) + "); using the default " + vector(fallback) + ".");
                return fallback;
            }
            reach[axis] = value;
        }

        final double length = Math.sqrt((double) reach[0] * reach[0]
                + (double) reach[1] * reach[1] + (double) reach[2] * reach[2]);
        if (length < this.settings.minReachLengthBlocks()) {
            // The api refuses this outright, since it normalises the vector and cannot divide by zero.
            warn(boneName, "has a 'reach' of " + vector(reach) + ", which points nowhere;"
                    + " using the default " + vector(fallback) + ".");
            return fallback;
        }
        return reach;
    }

    /** Per-axis bounds, written in degrees by the author and stored in radians by the api. */
    private @NotNull AxisLimits readLimits(
            @NotNull Block physics,
            @NotNull String boneName,
            @NotNull AxisLimits fallback
    ) {
        final Object raw = physics.get("limits");
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof String text && UNBOUNDED.equalsIgnoreCase(text.trim())) {
            return AxisLimits.UNBOUNDED;
        }

        final Float symmetric = asFloat(raw);
        if (symmetric != null) {
            if (!Float.isFinite(symmetric)) {
                warn(boneName, "has a 'limits' of " + describe(raw) + ", which is not a finite number"
                        + " of degrees; keeping the defaults. Write `limits: unbounded` for no bounds.");
                return fallback;
            }
            // A symmetric bound is a half-width, so its sign carries no information.
            return AxisLimits.symmetricDegrees(Math.abs(symmetric));
        }

        if (raw instanceof ConfigurationSection section) {
            final Block axes = block(section, LIMIT_KEYS, LIMIT_HELP, boneName,
                    "the limits block of bone '" + boneName + "'");
            final float[] bounds = {
                    fallback.minPitch(), fallback.maxPitch(),
                    fallback.minYaw(), fallback.maxYaw(),
                    fallback.minRoll(), fallback.maxRoll()
            };
            readAxis(axes, "pitch", bounds, 0, boneName);
            readAxis(axes, "yaw", bounds, 2, boneName);
            readAxis(axes, "roll", bounds, 4, boneName);
            return new AxisLimits(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
        }

        warn(boneName, "has a 'limits' of " + describe(raw) + "; expected a number of degrees,"
                + " '" + UNBOUNDED + "', or a mapping of " + LIMIT_HELP + ". Keeping the defaults.");
        return fallback;
    }

    /** One axis of a limits mapping, left at its fallback if it is missing or unreadable. */
    private void readAxis(
            @NotNull Block axes,
            @NotNull String key,
            float[] bounds,
            int slot,
            @NotNull String boneName
    ) {
        final Object raw = axes.get(key);
        if (raw == null) {
            return;
        }

        final Float symmetric = asFloat(raw);
        if (symmetric != null) {
            if (!Float.isFinite(symmetric)) {
                warn(boneName, "has a '" + key + "' limit of " + describe(raw) + ", which is not a"
                        + " finite number of degrees; keeping the default for that axis.");
                return;
            }
            final float half = (float) Math.toRadians(Math.abs(symmetric));
            bounds[slot] = -half;
            bounds[slot + 1] = half;
            return;
        }

        if (raw instanceof List<?> list && list.size() == 2) {
            final Float min = asFloat(list.get(0));
            final Float max = asFloat(list.get(1));
            if (min == null || max == null || !Float.isFinite(min) || !Float.isFinite(max)) {
                warn(boneName, "has a '" + key + "' limit list that does not hold two finite numbers"
                        + " of degrees (got " + describe(raw) + "); keeping the default for that axis.");
                return;
            }
            float low = (float) Math.toRadians(min);
            float high = (float) Math.toRadians(max);
            if (low > high) {
                // The intended range is unambiguous, so swapping costs nothing and refusing a rig
                // over a transposed pair would be the worst trade in this file.
                warn(boneName, "has inverted '" + key + "' limits (" + min + " > " + max
                        + "); swapped them.");
                final float swap = low;
                low = high;
                high = swap;
            }
            bounds[slot] = low;
            bounds[slot + 1] = high;
            return;
        }

        warn(boneName, "has a '" + key + "' limit of " + describe(raw) + "; expected a number of"
                + " degrees or a two-element [min, max] list. Keeping the default for that axis.");
    }

    // ------------------------------------------------------------------------------------------
    // Hierarchy
    // ------------------------------------------------------------------------------------------

    /**
     * The bones with every parent ahead of its children, or {@code null} if the hierarchy is broken.
     *
     * <p>A breadth-first walk from the roots outward. Each bone has at most one parent, so the graph
     * is a forest plus whatever cycles the author wrote, and a bone is queued only once its parent has
     * been emitted — which is exactly {@link Rig.Builder}'s requirement. The walk is iterative because
     * the alternative, recursion, turns a deep chain into a stack overflow and a cycle into an
     * infinite one, and neither reads as a rig problem in the log.
     *
     * <p>Anything the walk never reaches is in a cycle or hangs off one. Reporting that rather than
     * looping is the whole reason the sort is explicit instead of a {@code TreeMap} and a hope.
     */
    private @Nullable List<ParsedBone> sortTopologically(@NotNull List<ParsedBone> declared) {
        final Map<String, ParsedBone> byKey = LinkedHashMap.newLinkedHashMap(declared.size());
        for (final ParsedBone bone : declared) {
            byKey.put(bone.key(), bone);
        }

        final Map<String, List<ParsedBone>> children = new LinkedHashMap<>();
        final Deque<ParsedBone> queue = new ArrayDeque<>();
        boolean sound = true;

        for (final ParsedBone bone : declared) {
            final String parentKey = bone.parentKey();
            if (parentKey == null) {
                queue.addLast(bone);
                continue;
            }
            if (!byKey.containsKey(parentKey)) {
                // Hard: a bone whose parent is absent has nothing to hang off, and silently promoting
                // it to a root would put secondary motion on something that never moves. Usually a
                // typo, and a typo the author can fix in one line once told which name is wrong.
                error(bone.spelling(), "is parented to '" + bone.parent() + "', which this file does"
                        + " not declare. Check the spelling, or declare the parent as a pass-through"
                        + " bone with `" + bone.parent() + ": {}`.");
                sound = false;
                continue;
            }
            children.computeIfAbsent(parentKey, key -> new ArrayList<>()).add(bone);
        }
        if (!sound) {
            return null;
        }

        final List<ParsedBone> ordered = new ArrayList<>(declared.size());
        while (!queue.isEmpty()) {
            final ParsedBone bone = queue.removeFirst();
            ordered.add(bone);
            final List<ParsedBone> kids = children.get(bone.key());
            if (kids != null) {
                queue.addAll(kids);
            }
        }

        if (ordered.size() != declared.size()) {
            reportCycle(declared, ordered, byKey);
            return null;
        }
        return ordered;
    }

    /** Names the cycle that stopped the walk, rather than just the bones it could not reach. */
    private void reportCycle(
            @NotNull List<ParsedBone> declared,
            @NotNull List<ParsedBone> reached,
            @NotNull Map<String, ParsedBone> byKey
    ) {
        final Set<String> emitted = new HashSet<>();
        for (final ParsedBone bone : reached) {
            emitted.add(bone.key());
        }
        final List<ParsedBone> stranded = new ArrayList<>();
        for (final ParsedBone bone : declared) {
            if (!emitted.contains(bone.key())) {
                stranded.add(bone);
            }
        }

        final ParsedBone first = stranded.getFirst();
        error(first.spelling(), "is caught in a parent cycle: " + describeCycle(first, byKey)
                + " (reading each bone to its parent). " + stranded.size() + " of " + declared.size()
                + " bones cannot be reached from any root, so the hierarchy has no order to build in.");
    }

    /** Walks parents from {@code start} until a bone repeats, and renders the loop it closed. */
    private static @NotNull String describeCycle(
            @NotNull ParsedBone start,
            @NotNull Map<String, ParsedBone> byKey
    ) {
        final List<String> path = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        ParsedBone cursor = start;
        while (cursor != null && seen.add(cursor.key())) {
            path.add(cursor.key());
            final String parentKey = cursor.parentKey();
            cursor = parentKey == null ? null : byKey.get(parentKey);
        }
        if (cursor == null) {
            // Unreachable in practice: a bone outside a cycle would have been emitted by the walk.
            return String.join(" -> ", path);
        }
        final List<String> loop = new ArrayList<>(path.subList(path.indexOf(cursor.key()), path.size()));
        loop.add(cursor.key());
        return String.join(" -> ", loop);
    }

    /**
     * The bones that fit, dropping the deep end of the hierarchy if the file declares too many.
     *
     * <p>Safe to cut as a suffix precisely because the list is already topological: a dropped bone's
     * descendants are all behind it, so they go too, and what survives is a complete hierarchy rather
     * than a set of bones parented to something that is no longer there.
     */
    private @NotNull List<ParsedBone> enforceBoneCeiling(@NotNull List<ParsedBone> ordered) {
        final int ceiling = this.settings.rigBounds().maxBones();
        if (ordered.size() <= ceiling) {
            return ordered;
        }
        final ParsedBone firstDropped = ordered.get(ceiling);
        final int dropped = ordered.size() - ceiling;
        warn(firstDropped.spelling(), "is the first of " + dropped + " bones dropped: the rig declares "
                + ordered.size() + ", past the " + ceiling + "-bone ceiling. The deepest bones go"
                + " first, together with their descendants, so what loads is still a whole hierarchy.");
        return List.copyOf(ordered.subList(0, ceiling));
    }

    /** Feeds the sorted bones to the builder. */
    private @NotNull RigLoadResult build(
            @NotNull String name,
            @NotNull String modelId,
            @NotNull RigKind kind,
            @NotNull List<ParsedBone> ordered,
            @NotNull ArmSwingAxis armSwingAxis,
            @NotNull ArmSwingAxis handSwingAxis,
            @NotNull List<RigSeat> seats,
            @NotNull RigClips clips
    ) {
        try {
            final Rig.Builder builder = Rig.builder(
                    name, modelId, this.settings.rigBounds(), this.settings.profileBounds());
            builder.kind(kind);
            builder.armSwingAxis(armSwingAxis);
            builder.handSwingAxis(handSwingAxis);
            builder.clips(clips);
            builder.seatBounds(this.settings.seatBounds());
            for (final ParsedBone bone : ordered) {
                builder.bone(bone.key(), bone.parentKey(), bone.physics(), bone.animationOwned());
            }
            for (final RigSeat seat : seats) {
                builder.seat(seat);
            }

            return RigLoadResult.success(builder.build(), this.diagnostics);
        } catch (final IllegalArgumentException refused) {
            // Backstop, like the one in readPhysics: duplicates, unknown parents, ordering and the
            // bone ceiling are all handled above, so the builder has nothing left to refuse. If it
            // does, the two disagree and shipping a half-built rig would be worse than failing.
            error(null, "was refused by the rig builder after loading: " + refused.getMessage()
                    + ". This is a loader defect, not a file one.");
            return RigLoadResult.failure(this.diagnostics);
        }
    }

    private @NotNull ArmSwingAxis readArmSwingAxis(@NotNull Block top) {
        final String value = text(top, "armswingaxis");
        if (value == null || value.isBlank()) {
            return ArmSwingAxis.PITCH;
        }

        try {
            return ArmSwingAxis.parse(value);
        } catch (IllegalArgumentException refused) {
            error(null, refused.getMessage() + ".");
            return ArmSwingAxis.PITCH;
        }
    }

    private @NotNull ArmSwingAxis readHandSwingAxis(@NotNull Block top) {
        final String value = text(top, "handswingaxis");
        if (value == null || value.isBlank()) {
            return ArmSwingAxis.PITCH;
        }
        try {
            return ArmSwingAxis.parse(value);
        } catch (IllegalArgumentException refused) {
            error(null, refused.getMessage() + ".");
            return ArmSwingAxis.PITCH;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Reading primitives
    // ------------------------------------------------------------------------------------------

    /**
     * One block of the file, keyed canonically, with unknown and repeated keys already reported.
     *
     * <p>Reading through this instead of {@code section.get(key)} buys three things: case and
     * separator insensitivity for option keys, a warning for every key nothing reads, and immunity to
     * the configuration layer's path separator, which would otherwise read a key containing a dot as
     * a path into a nested section.
     */
    private @NotNull Block block(
            @NotNull ConfigurationSection section,
            @NotNull Set<String> known,
            @NotNull String help,
            @Nullable String boneName,
            @NotNull String what
    ) {
        return blockOf(section.getValues(false), known, help, boneName, what);
    }

    /**
     * The same, for a mapping that is not a {@link ConfigurationSection}.
     *
     * <p>Which is what every mapping nested inside a YAML list is: the configuration layer wraps only
     * the mappings it can address by path, so a {@code seats:} entry arrives as a plain {@link Map}.
     * Keys are rendered through {@link String#valueOf} because YAML permits a number or a boolean as a
     * key, and an unknown-key warning quoting {@code 3} is more use than a class cast.
     */
    private @NotNull Block block(
            @NotNull Map<?, ?> section,
            @NotNull Set<String> known,
            @NotNull String help,
            @Nullable String boneName,
            @NotNull String what
    ) {
        final Map<String, Object> raw = LinkedHashMap.newLinkedHashMap(section.size());
        for (final Map.Entry<?, ?> entry : section.entrySet()) {
            raw.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return blockOf(raw, known, help, boneName, what);
    }

    /**
     * The shared body of both overloads: canonicalise, report what nothing reads, keep the rest.
     *
     * <p>Named differently rather than overloaded a third time, because {@code Map<?, ?>} and
     * {@code Map<String, Object>} erase to the same signature.
     */
    private @NotNull Block blockOf(
            @NotNull Map<String, Object> section,
            @NotNull Set<String> known,
            @NotNull String help,
            @Nullable String boneName,
            @NotNull String what
    ) {
        final Map<String, Object> values = new LinkedHashMap<>();
        final Map<String, String> spelling = new LinkedHashMap<>();

        for (final Map.Entry<String, Object> entry : section.entrySet()) {
            final String key = canonical(entry.getKey());
            if (!known.contains(key)) {
                // A typo'd key is the quietest failure in a config format: the author's value is
                // simply never read, and everything else works. Say so.
                warn(boneName, "has an unknown key '" + entry.getKey() + "' in " + what
                        + "; ignored. Known keys here: " + help + ".");
                continue;
            }
            if (values.put(key, entry.getValue()) != null) {
                warn(boneName, "sets '" + entry.getKey() + "' in " + what + " more than once"
                        + " (keys are matched ignoring case, '-' and '_'); the last one wins.");
            }
            spelling.put(key, entry.getKey());
        }
        return new Block(values, spelling);
    }

    /** A trimmed, non-empty string value, or {@code null} if the key is absent or blank. */
    private static @Nullable String text(@NotNull Block block, @NotNull String key) {
        final Object raw = block.get(key);
        if (raw == null) {
            return null;
        }
        final String text = raw.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * A boolean option, defaulting to {@code false} when absent and when unreadable.
     *
     * <p>Both flags this format has — a bone's {@code animation-owned} and a seat's {@code driver} —
     * default to false, and in both cases false is the absence of a claim rather than a chosen value:
     * a bone nobody said was animated, a seat nobody said steers.
     *
     * @param what how to name the block in a warning, e.g. {@code bone 'tail'}
     */
    private boolean flag(
            @NotNull Block block,
            @NotNull String key,
            @Nullable String boneName,
            @NotNull String what
    ) {
        final Object raw = block.get(key);
        if (raw == null) {
            return false;
        }
        if (raw instanceof Boolean value) {
            return value;
        }
        final String text = raw.toString().trim().toLowerCase(Locale.ROOT);
        if (text.equals("true")) {
            return true;
        }
        if (text.equals("false")) {
            return false;
        }
        warn(boneName, "has a '" + block.spelled(key) + "' in " + what + " that is not true or false"
                + " (got " + describe(raw) + "); read as false.");
        return false;
    }

    /** Numbers, and strings that are numbers. Anything else is {@code null}. */
    private static @Nullable Float asFloat(@Nullable Object raw) {
        if (raw instanceof Number number) {
            return number.floatValue();
        }
        if (raw instanceof String text) {
            try {
                return Float.parseFloat(text.trim());
            } catch (final NumberFormatException notANumber) {
                return null;
            }
        }
        return null;
    }

    /** Lower-cased with separators stripped, so one option key has one meaning however it is typed. */
    private static @NotNull String canonical(@NotNull String key) {
        return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").trim();
    }

    /** A rig name derived from the file name, for a file that does not name itself. */
    private static @NotNull String fallbackName(@NotNull String source) {
        String base = source.trim();
        final int separator = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (separator >= 0) {
            base = base.substring(separator + 1);
        }
        final int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        // Rig.Builder refuses a blank name, so this last resort has to be non-blank.
        return base.isBlank() ? "unnamed" : base;
    }

    private static @NotNull String describe(@Nullable Object raw) {
        if (raw == null) {
            return "nothing";
        }
        if (raw instanceof ConfigurationSection || raw instanceof Map<?, ?>) {
            // A mapping reaches here as a Map rather than a section whenever it is nested inside a
            // list, which is every `seats:` entry.
            return "a mapping";
        }
        return "'" + raw + "'";
    }

    /** Every bone the file declared, by the key the rig matches on. */
    private static @NotNull Set<String> boneKeys(@NotNull List<ParsedBone> declared) {
        final Set<String> keys = LinkedHashSet.newLinkedHashSet(declared.size());
        for (final ParsedBone bone : declared) {
            keys.add(bone.key());
        }
        return keys;
    }

    /** The {@code clips} block's keys, canonicalised, derived from the roles themselves. */
    private static @NotNull Set<String> clipKeys() {
        final Set<String> keys = LinkedHashSet.newLinkedHashSet(ClipRole.COUNT);
        for (final ClipRole role : ClipRole.values()) {
            keys.add(canonical(role.key()));
        }
        return Set.copyOf(keys);
    }

    private static @NotNull String vector(float[] values) {
        return "[" + values[0] + ", " + values[1] + ", " + values[2] + "]";
    }

    private static @NotNull String kindNames() {
        final List<String> names = new ArrayList<>(PhysicsKind.values().length);
        for (final PhysicsKind kind : PhysicsKind.values()) {
            names.add(kind.name().toLowerCase(Locale.ROOT));
        }
        return String.join(", ", names);
    }

    private void warn(@Nullable String boneName, @NotNull String message) {
        this.diagnostics.add(RigDiagnostic.warning(this.source, boneName, message));
    }

    private void error(@Nullable String boneName, @NotNull String message) {
        this.diagnostics.add(RigDiagnostic.error(this.source, boneName, message));
    }

    private boolean hasErrors() {
        for (final RigDiagnostic diagnostic : this.diagnostics) {
            if (diagnostic.isError()) {
                return true;
            }
        }
        return false;
    }

    /**
     * A bone as the file declares it: its normalised key, the spelling to quote back at the author,
     * the parent it names, whatever physics survived reading, and whether the model's own animations
     * already drive it.
     */
    private record ParsedBone(
            @NotNull String key,
            @NotNull String spelling,
            @Nullable String parent,
            @Nullable PhysicsProfile physics,
            boolean animationOwned
    ) {

        /** The parent's name in the form the rig keys on, or {@code null} for a root. */
        @Nullable String parentKey() {
            return this.parent == null ? null : Rig.normalise(this.parent.trim());
        }
    }

    /** A block's values keyed canonically, remembering how the author spelled each key. */
    private record Block(
            @NotNull Map<String, Object> values,
            @NotNull Map<String, String> spelling
    ) {

        boolean has(@NotNull String key) {
            return this.values.containsKey(key);
        }

        @Nullable Object get(@NotNull String key) {
            return this.values.get(key);
        }

        /** The author's spelling of {@code key}, for a message they can search their file for. */
        @NotNull String spelled(@NotNull String key) {
            return this.spelling.getOrDefault(key, key);
        }
    }
}
