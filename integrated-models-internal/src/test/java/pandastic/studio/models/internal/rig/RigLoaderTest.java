package pandastic.studio.models.internal.rig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pandastic.studio.models.api.rig.AxisLimits;
import pandastic.studio.models.api.rig.ClipRole;
import pandastic.studio.models.api.rig.PhysicsKind;
import pandastic.studio.models.api.rig.PhysicsProfile;
import pandastic.studio.models.api.rig.ProfileBounds;
import pandastic.studio.models.api.rig.ProfileDefaults;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.rig.RigBone;
import pandastic.studio.models.api.rig.RigBounds;
import pandastic.studio.models.api.rig.RigKind;
import pandastic.studio.models.api.rig.RigSeat;
import pandastic.studio.models.api.rig.SeatBounds;
import pandastic.studio.models.internal.physics.AngularPhysicsSolver;
import pandastic.studio.models.internal.rig.RigDiagnostic.Severity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lives in the loader's own package so it can read {@link RigFileReader}'s view of a file if it ever
 * needs to, but deliberately drives everything through the public entry points: the format is the
 * contract, and a test that reached around it would stop defending the format.
 *
 * <p>Every case asserts on the <strong>diagnostics</strong> as well as the outcome. A clamp nobody can
 * see is indistinguishable from a value that was honoured, which is precisely the failure the
 * clamp-and-warn policy exists to avoid.
 */
class RigLoaderTest {

    private static final String SOURCE = "rigs/banner.yml";
    private static final float EPSILON = 1.0E-5F;

    /** The configured bone ceiling these cases run against, not a property of the loader. */
    private static final int CEILING = RigFixtures.RIG_BOUNDS.maxBones();

    /** The axis bound a rig that omits one gets, which is the operator's figure rather than ours. */
    private static final AxisLimits DEFAULT_LIMITS =
            AxisLimits.symmetricDegrees(RigFixtures.PROFILE_BOUNDS.defaultLimitDegrees());

    private static RigLoadResult load(String yaml) {
        return RigLoader.loadText(SOURCE, yaml, RigFixtures.SETTINGS);
    }

    private static RigLoadResult load(String yaml, RigLoadSettings settings) {
        return RigLoader.loadText(SOURCE, yaml, settings);
    }

    /** A minimal valid file with {@code physics} spliced into a single hair bone. */
    private static RigLoadResult loadHair(String physicsBlock) {
        return load("""
                name: doll
                model: doll_model
                bones:
                  body: {}
                  hair:
                    parent: body
                    physics:
                """ + physicsBlock.indent(6));
    }

    private static PhysicsProfile hairPhysics(RigLoadResult result) {
        final RigBone hair = result.rigOrThrow().bone(1);
        assertEquals("hair", hair.name());
        final PhysicsProfile physics = hair.physics();
        assertNotNull(physics, () -> "expected the hair bone to keep its physics\n" + render(result));
        return physics;
    }

    private static String render(RigLoadResult result) {
        final StringBuilder rendered = new StringBuilder("diagnostics:");
        for (final RigDiagnostic diagnostic : result.diagnostics()) {
            rendered.append("\n  ").append(diagnostic.format());
        }
        return rendered.toString();
    }

    /** Asserts that some diagnostic of this severity mentions every one of {@code needles}. */
    private static void assertSays(RigLoadResult result, Severity severity, String... needles) {
        for (final RigDiagnostic diagnostic : result.diagnostics(severity)) {
            boolean matches = true;
            for (final String needle : needles) {
                matches &= diagnostic.format().contains(needle);
            }
            if (matches) {
                return;
            }
        }
        throw new AssertionError("no " + severity + " mentioning " + String.join(" + ", needles)
                + "\n" + render(result));
    }

    private static void assertNoDiagnostics(RigLoadResult result) {
        assertTrue(result.diagnostics().isEmpty(), () -> "expected a clean load but got " + render(result));
    }

    // ------------------------------------------------------------------------------------------
    // The happy path, and the documented example
    // ------------------------------------------------------------------------------------------

    @Test
    void loadsAValidRigEndToEnd() {
        final RigLoadResult result = load("""
                name: doll
                model: doll_model
                bones:
                  body: {}
                  hair:
                    parent: body
                    physics:
                      kind: pendulum
                      stiffness: 0.1
                      damping: 0.02
                      gravity: 0.5
                      mass: 2.0
                      wind: 1.5
                """);

        final Rig rig = result.rigOrThrow();
        final PhysicsProfile hair = hairPhysics(result);
        assertAll(
                () -> assertNoDiagnostics(result),
                () -> assertTrue(result.isSuccess()),
                () -> assertFalse(result.hasWarnings()),
                () -> assertEquals("doll", rig.getName()),
                () -> assertEquals("doll_model", rig.getModelId()),
                () -> assertEquals(2, rig.boneCount()),
                () -> assertEquals(1, rig.physicsBoneCount()),
                () -> assertTrue(rig.bone(0).isRoot()),
                () -> assertNull(rig.bone(0).physics(), "an anchor bone carries no physics"),
                () -> assertEquals(0, rig.bone(1).parentIndex()),
                () -> assertEquals(PhysicsKind.PENDULUM, hair.kind()),
                () -> assertEquals(0.1F, hair.stiffness(), EPSILON),
                () -> assertEquals(0.02F, hair.damping(), EPSILON),
                () -> assertEquals(0.5F, hair.gravity(), EPSILON),
                () -> assertEquals(2F, hair.mass(), EPSILON),
                () -> assertEquals(1.5F, hair.wind(), EPSILON));
    }

    @Test
    void theDocumentedExampleIsItselfAValidRig() {
        final RigLoadResult result = load(RigLoader.EXAMPLE);
        final Rig rig = result.rigOrThrow();

        assertAll(
                // The example is the format's documentation; a documented format nothing parses
                // drifts away from the parser inside one release.
                () -> assertNoDiagnostics(result),
                () -> assertEquals("windswept_banner", rig.getName()),
                () -> assertEquals("banner", rig.getModelId()),
                () -> assertEquals(5, rig.boneCount()),
                () -> assertEquals(4, rig.physicsBoneCount()),
                // The example documents the whole format, so it has to exercise the whole format:
                // a kind, the clips, the animation-owned flag and a seats section with a driver.
                () -> assertEquals(RigKind.VEHICLE, rig.kind()),
                () -> assertEquals("idle", rig.clips().clip(ClipRole.IDLE)),
                () -> assertEquals("walk", rig.clips().clip(ClipRole.WALK)),
                () -> assertFalse(rig.clips().declares(ClipRole.RUN),
                        "the example leaves run commented out, to document that a role may be omitted"),
                () -> assertTrue(rig.bone(0).animationOwned()),
                () -> assertEquals(1, rig.animationOwnedBoneCount()),
                () -> assertEquals(2, rig.seatCount()),
                () -> assertEquals("seat_driver", rig.driverSeat().orElseThrow().locator()),
                () -> assertEquals("seat_driver", rig.driverSeat().orElseThrow().name(),
                        "a seat with no 'name' is named after its locator"),
                () -> assertEquals(0.1F, rig.findSeat("bench_left").orElseThrow().offsetY(), EPSILON),
                () -> assertEquals(
                        Set.of("pole", "tail", "antenna", "cloth_left", "cloak"),
                        result.expectedBoneNames()),
                () -> assertNull(rig.bone(0).physics()),
                () -> assertEquals(PhysicsKind.PENDULUM, kindOf(rig, "tail")),
                () -> assertEquals(PhysicsKind.SPRING, kindOf(rig, "antenna")),
                () -> assertEquals(PhysicsKind.CLOTH_STRIP, kindOf(rig, "cloth_left")),
                () -> assertEquals(PhysicsKind.DRAG, kindOf(rig, "cloak")),
                // And the tuning it documents has to be tuning the real solver accepts, or the
                // example would be teaching people to write rigs that refuse to simulate.
                () -> assertDoesNotThrow(() -> RigFixtures.SOLVER.createState(rig)));
    }

    private static PhysicsKind kindOf(Rig rig, String boneName) {
        final RigBone bone = rig.bone(rig.indexOf(boneName).orElseThrow());
        assertNotNull(bone.physics(), boneName + " should carry physics");
        return bone.physics().kind();
    }

    @Test
    void acceptsKindNamesWhateverTheSeparatorAndCase() {
        for (final String spelling : new String[]{"cloth_strip", "cloth-strip", "CLOTH-STRIP", "ClothStrip"}) {
            final RigLoadResult result = loadHair("kind: " + spelling);
            assertAll(
                    () -> assertNoDiagnostics(result),
                    () -> assertEquals(PhysicsKind.CLOTH_STRIP, hairPhysics(result).kind(),
                            "'" + spelling + "' should name the cloth strip kind"));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Topological ordering
    // ------------------------------------------------------------------------------------------

    @Test
    void sortsParentsBeforeChildrenWhenTheFileListsTheChildFirst() {
        final RigLoadResult result = load("""
                name: doll
                model: doll_model
                bones:
                  hair:
                    parent: body
                    physics:
                      kind: drag
                  body: {}
                """);

        final Rig rig = result.rigOrThrow();
        assertAll(
                () -> assertNoDiagnostics(result),
                // The builder requires it, and the solver reads a bone's ancestors out of the pose
                // the same pass already wrote - so declaration order has to be discarded, not trusted.
                () -> assertEquals("body", rig.bone(0).name()),
                () -> assertEquals("hair", rig.bone(1).name()),
                () -> assertEquals(0, rig.bone(1).parentIndex()));
    }

    @Test
    void sortsADeepChainDeclaredEntirelyBackwards() {
        final RigLoadResult result = load("""
                name: chain
                model: m
                bones:
                  d: {parent: c}
                  c: {parent: b}
                  b: {parent: a}
                  a: {}
                """);

        final Rig rig = result.rigOrThrow();
        assertAll(
                () -> assertNoDiagnostics(result),
                () -> assertEquals(List.of("a", "b", "c", "d"), names(rig)),
                () -> assertEquals(RigBone.NO_PARENT, rig.bone(0).parentIndex()),
                () -> assertEquals(0, rig.bone(1).parentIndex()),
                () -> assertEquals(1, rig.bone(2).parentIndex()),
                () -> assertEquals(2, rig.bone(3).parentIndex()));
    }

    private static List<String> names(Rig rig) {
        final List<String> names = new ArrayList<>(rig.boneCount());
        for (final RigBone bone : rig.getBones()) {
            names.add(bone.name());
        }
        return names;
    }

    @Test
    void refusesACycleInsteadOfLoopingForever() {
        final RigLoadResult result = load("""
                name: knot
                model: m
                bones:
                  a: {parent: c}
                  b: {parent: a}
                  c: {parent: b}
                """);

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(result.rig().isEmpty()),
                () -> assertSays(result, Severity.ERROR, "cycle", "a -> c -> b -> a"),
                () -> assertSays(result, Severity.ERROR, "3 of 3 bones"));
    }

    @Test
    void refusesABoneParentedToItself() {
        final RigLoadResult result = load("""
                name: knot
                model: m
                bones:
                  body: {}
                  hair: {parent: hair}
                """);

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertSays(result, Severity.ERROR, "cycle", "hair -> hair"));
    }

    @Test
    void refusesAParentThatDoesNotExist() {
        final RigLoadResult result = load("""
                name: doll
                model: m
                bones:
                  body: {}
                  hair: {parent: torso}
                """);

        assertAll(
                () -> assertFalse(result.isSuccess()),
                // Promoting it to a root would put secondary motion on something that never moves,
                // which looks like "the physics does not work" rather than like a typo.
                () -> assertSays(result, Severity.ERROR, "bone 'hair'", "'torso'", "does not declare"));
    }

    @Test
    void reportsEveryUnknownParentRatherThanOnlyTheFirst() {
        final RigLoadResult result = load("""
                name: doll
                model: m
                bones:
                  body: {}
                  hair: {parent: torso}
                  tail: {parent: spine}
                """);

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertEquals(2, result.diagnostics(Severity.ERROR).size(), render(result)),
                () -> assertSays(result, Severity.ERROR, "'torso'"),
                () -> assertSays(result, Severity.ERROR, "'spine'"));
    }

    // ------------------------------------------------------------------------------------------
    // Structural errors
    // ------------------------------------------------------------------------------------------

    @Test
    void refusesTwoBonesThatAreTheSameBoneOnceNamesAreFolded() {
        final RigLoadResult result = load("""
                name: doll
                model: m
                bones:
                  Hair: {}
                  hair: {}
                """);

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertSays(result, Severity.ERROR, "bone 'hair'", "collides with 'Hair'"));
    }

    @Test
    void refusesAFileWithNoModelId() {
        final RigLoadResult result = load("""
                name: doll
                bones:
                  body: {}
                """);

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertSays(result, Severity.ERROR, "no 'model' key"));
    }

    @Test
    void refusesAFileWithNoBones() {
        final RigLoadResult empty = load("""
                name: doll
                model: m
                bones: {}
                """);
        final RigLoadResult absent = load("""
                name: doll
                model: m
                """);

        assertAll(
                () -> assertFalse(empty.isSuccess()),
                () -> assertSays(empty, Severity.ERROR, "declares no bones"),
                () -> assertFalse(absent.isSuccess()),
                () -> assertSays(absent, Severity.ERROR, "no 'bones' section"));
    }

    @Test
    void refusesABonesKeyThatIsNotAMapping() {
        final RigLoadResult result = load("""
                name: doll
                model: m
                bones:
                  - body
                  - hair
                """);

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertSays(result, Severity.ERROR, "'bones'", "not a mapping"));
    }

    @Test
    void refusesTextThatIsNotYamlAtAll() {
        final RigLoadResult broken = load("model: [unclosed\n");
        final RigLoadResult notAMapping = load("- body\n- hair\n");

        assertAll(
                () -> assertFalse(broken.isSuccess()),
                () -> assertSays(broken, Severity.ERROR, "not readable YAML"),
                () -> assertFalse(notAMapping.isSuccess()),
                () -> assertSays(notAMapping, Severity.ERROR, "not readable YAML"));
    }

    @Test
    void aFailureCarriesNoRigButKeepsEveryDiagnostic() {
        // The name warning is raised before the cycle is found, so a failed load still has to hand
        // back everything it learned on the way down.
        final RigLoadResult result = load("""
                model: m
                bones:
                  a: {parent: b}
                  b: {parent: a}
                """);

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(result.hasWarnings(), render(result)),
                () -> assertSays(result, Severity.WARNING, "no 'name' key"),
                () -> assertSays(result, Severity.ERROR, "cycle"),
                () -> assertTrue(result.expectedBoneNames().isEmpty()),
                () -> assertThrows(IllegalStateException.class, result::rigOrThrow));
    }

    @Test
    void defaultsTheRigNameToTheFileNameWithAWarning() {
        final RigLoadResult result = load("""
                model: m
                bones:
                  body: {}
                """);

        assertAll(
                () -> assertEquals("banner", result.rigOrThrow().getName(),
                        "the directory and the extension are not part of a rig name"),
                () -> assertSays(result, Severity.WARNING, "no 'name' key", "'banner'"));
    }

    @Test
    void refusesALoadWithNoSourceToNameInItsDiagnostics() {
        // The only thing this loader throws on: a caller bug, not a file problem.
        assertThrows(IllegalArgumentException.class,
                () -> RigLoader.loadText("  ", "model: m", RigFixtures.SETTINGS));
    }

    // ------------------------------------------------------------------------------------------
    // Clamping
    // ------------------------------------------------------------------------------------------

    @Test
    void clampsACoefficientAboveItsRange() {
        final RigLoadResult result = loadHair("""
                kind: pendulum
                stiffness: 5.0
                """);

        assertAll(
                () -> assertEquals(1F, hairPhysics(result).stiffness(), EPSILON),
                () -> assertSays(result, Severity.WARNING,
                        "bone 'hair'", "'stiffness' 5.0", "clamped to 1.0"));
    }

    @Test
    void clampsACoefficientBelowItsRange() {
        final RigLoadResult result = loadHair("""
                kind: pendulum
                gravity: -3.0
                mass: 0.0001
                """);

        final PhysicsProfile hair = hairPhysics(result);
        assertAll(
                () -> assertEquals(0F, hair.gravity(), EPSILON),
                () -> assertEquals(RigFixtures.PROFILE_BOUNDS.minMass(), hair.mass(), EPSILON),
                () -> assertSays(result, Severity.WARNING, "'gravity' -3.0", "clamped to 0.0"),
                () -> assertSays(result, Severity.WARNING, "'mass' 1.0E-4", "clamped to 0.05"));
    }

    @Test
    void clampsEveryCoefficientToItsOwnCeiling() {
        final RigLoadResult result = loadHair("""
                kind: cloth_strip
                stiffness: 9
                gravity: 9
                mass: 99
                wind: 9
                collision-radius: 9
                """);

        final PhysicsProfile hair = hairPhysics(result);
        assertAll(
                () -> assertEquals(1F, hair.stiffness(), EPSILON),
                () -> assertEquals(RigFixtures.PROFILE_BOUNDS.maxGravity(), hair.gravity(), EPSILON),
                () -> assertEquals(RigFixtures.PROFILE_BOUNDS.maxMass(), hair.mass(), EPSILON),
                () -> assertEquals(RigFixtures.PROFILE_BOUNDS.maxWind(), hair.wind(), EPSILON),
                () -> assertEquals(RigFixtures.PROFILE_BOUNDS.maxCollisionRadius(),
                        hair.collisionRadius(), EPSILON),
                () -> assertEquals(5, result.diagnostics(Severity.WARNING).size(),
                        "one warning per clamped value, so none of them hides the others:\n"
                                + render(result)));
    }

    @Test
    void clampsDampingToTheIntegratorsStabilityBoundAndTheResultActuallySimulates() {
        // Damping 1 against mass 0.1 is inside every documented range and still diverges: the
        // explicit velocity update flips sign once damping passes mass at 60 Hz. This is the clamp
        // that justifies the whole policy, since the author has no way to know the bound exists.
        final RigLoadResult result = loadHair("""
                kind: pendulum
                damping: 1.0
                mass: 0.1
                """);

        final PhysicsProfile hair = hairPhysics(result);
        final float bound = RigFixtures.SOLVER.maxStableDamping(0.1F, 60);
        assertAll(
                () -> assertTrue(hair.damping() < bound,
                        "expected damping below " + bound + ", got " + hair.damping()),
                () -> assertTrue(hair.damping() > bound * 0.9F,
                        "and as close to it as stability allows, got " + hair.damping()),
                () -> assertSays(result, Severity.WARNING,
                        "bone 'hair'", "damping 1.0", "stability bound", "mass 0.1"),
                // The proof that the clamp is correct rather than merely present: the real solver
                // refuses this tuning unclamped.
                () -> assertDoesNotThrow(() -> RigFixtures.SOLVER.createState(result.rigOrThrow())));
    }

    @Test
    void leavesDampingAloneForDragBecauseDragCannotDiverge() {
        final RigLoadResult result = loadHair("""
                kind: drag
                damping: 1.0
                mass: 0.05
                lag: 0.5
                """);

        assertAll(
                () -> assertEquals(1F, hairPhysics(result).damping(), EPSILON,
                        "drag has no velocity term, so the bound does not apply"),
                // It still warns that drag reads none of it, which is a different complaint.
                () -> assertSays(result, Severity.WARNING, "'damping'", "does not read"),
                () -> assertDoesNotThrow(() -> RigFixtures.SOLVER.createState(result.rigOrThrow())));
    }

    @Test
    void fallsBackToTheKindsDefaultWhenACoefficientIsNotANumber() {
        final RigLoadResult result = loadHair("""
                kind: pendulum
                stiffness: quite firm
                mass: ~
                """);

        final PhysicsProfile defaults = PhysicsProfile
                .builder(PhysicsKind.PENDULUM, RigFixtures.PROFILE_BOUNDS, RigFixtures.PROFILE_DEFAULTS)
                .build();
        assertAll(
                () -> assertEquals(defaults.stiffness(), hairPhysics(result).stiffness(), EPSILON),
                () -> assertSays(result, Severity.WARNING,
                        "'stiffness'", "not a number", "quite firm"),
                // `mass: ~` is YAML null, which the configuration layer deletes outright - so the
                // default applies and there is nothing to warn about.
                () -> assertEquals(defaults.mass(), hairPhysics(result).mass(), EPSILON));
    }

    @Test
    void readsANumberThatWasQuotedAsAString() {
        final RigLoadResult result = loadHair("""
                kind: pendulum
                stiffness: "0.25"
                """);

        assertAll(
                () -> assertNoDiagnostics(result),
                () -> assertEquals(0.25F, hairPhysics(result).stiffness(), EPSILON));
    }

    @Test
    void dropsTheBonesPastTheCeilingFromTheDeepEnd() {
        final StringBuilder yaml = new StringBuilder("name: huge\nmodel: m\nbones:\n  b1: {}\n");
        final int declared = CEILING + 44;
        for (int index = 2; index <= declared; index++) {
            yaml.append("  b").append(index).append(": {parent: b").append(index - 1).append("}\n");
        }

        final RigLoadResult result = load(yaml.toString());
        final Rig rig = result.rigOrThrow();

        assertAll(
                () -> assertEquals(CEILING, rig.boneCount()),
                () -> assertTrue(rig.indexOf("b" + CEILING).isPresent(), "the last kept bone"),
                () -> assertTrue(rig.indexOf("b" + (CEILING + 1)).isEmpty(), "the first dropped one"),
                // The cut is a suffix of a topological order, so every dropped bone's ancestors are
                // still present and the hierarchy that survives is whole.
                () -> assertEquals(CEILING - 2, rig.bone(CEILING - 1).parentIndex()),
                () -> assertSays(result, Severity.WARNING,
                        "44 bones dropped", String.valueOf(declared), CEILING + "-bone ceiling"));
    }

    // ------------------------------------------------------------------------------------------
    // Malformed physics degrades to a pass-through bone
    // ------------------------------------------------------------------------------------------

    @Test
    void aPhysicsBlockThatIsNotAMappingCostsThePhysicsAndNotTheBone() {
        final RigLoadResult result = load("""
                name: doll
                model: m
                bones:
                  body: {}
                  hair:
                    parent: body
                    physics: yes please
                  tip:
                    parent: hair
                    physics:
                      kind: drag
                """);

        final Rig rig = result.rigOrThrow();
        assertAll(
                // The bone has to survive, or its children lose their anchor and the whole chain
                // below one typo stops working.
                () -> assertEquals(3, rig.boneCount()),
                () -> assertNull(rig.bone(1).physics()),
                () -> assertEquals(1, rig.bone(2).parentIndex(), "the chain below it still hangs on"),
                () -> assertNotNull(rig.bone(2).physics()),
                () -> assertSays(result, Severity.WARNING,
                        "bone 'hair'", "'physics'", "pass-through"));
    }

    @Test
    void anUnknownPhysicsKindCostsThePhysicsAndNotTheBone() {
        final RigLoadResult result = loadHair("""
                kind: jelly
                stiffness: 0.5
                """);

        assertAll(
                () -> assertEquals(2, result.rigOrThrow().boneCount()),
                () -> assertNull(result.rigOrThrow().bone(1).physics()),
                () -> assertEquals(0, result.rigOrThrow().physicsBoneCount()),
                () -> assertSays(result, Severity.WARNING,
                        "unknown physics kind 'jelly'", "pendulum, spring, cloth_strip, drag"));
    }

    @Test
    void aPhysicsBlockWithNoKindCostsThePhysicsAndNotTheBone() {
        final RigLoadResult result = loadHair("stiffness: 0.5");

        assertAll(
                () -> assertEquals(2, result.rigOrThrow().boneCount()),
                () -> assertNull(result.rigOrThrow().bone(1).physics()),
                () -> assertSays(result, Severity.WARNING, "no 'kind'", "pass-through"));
    }

    @Test
    void aBoneThatIsNotAMappingStaysAsAPassThroughAnchor() {
        final RigLoadResult result = load("""
                name: doll
                model: m
                bones:
                  body: 17
                  hair:
                    parent: body
                    physics:
                      kind: drag
                """);

        final Rig rig = result.rigOrThrow();
        assertAll(
                () -> assertEquals(2, rig.boneCount()),
                () -> assertTrue(rig.bone(0).isRoot()),
                () -> assertEquals(0, rig.bone(1).parentIndex()),
                () -> assertSays(result, Severity.WARNING,
                        "bone 'body'", "not a mapping", "pass-through anchor"));
    }

    // ------------------------------------------------------------------------------------------
    // Limits: degrees in, radians out
    // ------------------------------------------------------------------------------------------

    @Test
    void convertsPerAxisLimitsFromDegreesToRadians() {
        final RigLoadResult result = loadHair("""
                kind: spring
                limits:
                  pitch: [-90, 90]
                  yaw: 30
                """);

        final AxisLimits limits = hairPhysics(result).limits();
        assertAll(
                () -> assertNoDiagnostics(result),
                () -> assertEquals((float) -Math.PI / 2F, limits.minPitch(), EPSILON),
                () -> assertEquals((float) Math.PI / 2F, limits.maxPitch(), EPSILON),
                () -> assertEquals((float) -Math.toRadians(30), limits.minYaw(), EPSILON),
                () -> assertEquals((float) Math.toRadians(30), limits.maxYaw(), EPSILON),
                // An omitted axis keeps the api's default rather than becoming unbounded.
                () -> assertEquals(DEFAULT_LIMITS.maxRoll(), limits.maxRoll(), EPSILON));
    }

    @Test
    void readsASingleNumberAsASymmetricHalfWidth() {
        final RigLoadResult result = loadHair("""
                kind: spring
                limits: 60
                """);

        final AxisLimits limits = hairPhysics(result).limits();
        assertAll(
                () -> assertNoDiagnostics(result),
                () -> assertEquals((float) Math.toRadians(60), limits.maxPitch(), EPSILON),
                () -> assertEquals((float) -Math.toRadians(60), limits.minYaw(), EPSILON),
                () -> assertEquals((float) Math.toRadians(60), limits.maxRoll(), EPSILON));
    }

    @Test
    void readsUnboundedLimits() {
        final RigLoadResult result = loadHair("""
                kind: drag
                lag: 0.5
                limits: UNBOUNDED
                """);

        final AxisLimits limits = hairPhysics(result).limits();
        assertAll(
                () -> assertNoDiagnostics(result),
                () -> assertEquals(Float.POSITIVE_INFINITY, limits.maxPitch()),
                () -> assertEquals(Float.NEGATIVE_INFINITY, limits.minRoll()));
    }

    @Test
    void swapsInvertedLimitsInsteadOfRefusingTheRig() {
        final RigLoadResult result = loadHair("""
                kind: spring
                limits:
                  pitch: [45, -45]
                """);

        final AxisLimits limits = hairPhysics(result).limits();
        assertAll(
                () -> assertEquals((float) -Math.toRadians(45), limits.minPitch(), EPSILON),
                () -> assertEquals((float) Math.toRadians(45), limits.maxPitch(), EPSILON),
                () -> assertSays(result, Severity.WARNING, "inverted 'pitch' limits", "swapped"));
    }

    @Test
    void keepsTheDefaultLimitsWhenTheBlockIsUnreadable() {
        final RigLoadResult whole = loadHair("""
                kind: spring
                limits: sideways
                """);
        final RigLoadResult axis = loadHair("""
                kind: spring
                limits:
                  pitch: sideways
                  yaw: [10, up]
                """);

        assertAll(
                () -> assertEquals(DEFAULT_LIMITS, hairPhysics(whole).limits()),
                () -> assertSays(whole, Severity.WARNING, "'limits'", "sideways", "unbounded"),
                () -> assertEquals(DEFAULT_LIMITS, hairPhysics(axis).limits()),
                () -> assertSays(axis, Severity.WARNING, "'pitch' limit", "sideways"),
                () -> assertSays(axis, Severity.WARNING, "'yaw' limit", "two finite numbers"));
    }

    @Test
    void foldsAClothStripsClearanceIntoAPitchCeiling() {
        final RigLoadResult result = loadHair("""
                kind: cloth_strip
                collision-radius: 0.3
                limits:
                  pitch: [-80, 80]
                """);

        final AxisLimits limits = hairPhysics(result).limits();
        assertAll(
                () -> assertNoDiagnostics(result),
                // The rig builder turns the clearance into a clamp so the solver never collides.
                () -> assertTrue(limits.maxPitch() < (float) Math.toRadians(80),
                        "the clearance has to tighten the ceiling, got " + limits.maxPitch()),
                () -> assertEquals(0.51F, limits.maxPitch(), 1.0E-3F),
                () -> assertEquals((float) -Math.toRadians(80), limits.minPitch(), EPSILON));
    }

    // ------------------------------------------------------------------------------------------
    // Reach
    // ------------------------------------------------------------------------------------------

    @Test
    void readsAReachVectorAndLetsTheApiNormaliseIt() {
        final RigLoadResult result = loadHair("""
                kind: pendulum
                reach: [0.0, -2.0, 0.0]
                """);

        final PhysicsProfile hair = hairPhysics(result);
        assertAll(
                () -> assertNoDiagnostics(result),
                () -> assertEquals(-1F, hair.reachY(), EPSILON, "magnitude must not matter"),
                () -> assertEquals(0F, hair.reachX(), EPSILON));
    }

    @Test
    void fallsBackToTheDefaultReachWhenItPointsNowhere() {
        final RigLoadResult result = loadHair("""
                kind: pendulum
                reach: [0.0, 0.0, 0.0]
                """);

        assertAll(
                // The api refuses a zero vector outright, because it cannot normalise it.
                () -> assertEquals(-1F, hairPhysics(result).reachY(), EPSILON),
                () -> assertSays(result, Severity.WARNING, "'reach'", "points nowhere"));
    }

    @Test
    void fallsBackToTheDefaultReachWhenItIsNotAThreeElementList() {
        final RigLoadResult text = loadHair("""
                kind: pendulum
                reach: down
                """);
        final RigLoadResult tooShort = loadHair("""
                kind: pendulum
                reach: [0.0, -1.0]
                """);
        final RigLoadResult notNumbers = loadHair("""
                kind: pendulum
                reach: [0.0, down, 0.0]
                """);

        assertAll(
                () -> assertEquals(-1F, hairPhysics(text).reachY(), EPSILON),
                () -> assertSays(text, Severity.WARNING, "'reach'", "three-element"),
                () -> assertSays(tooShort, Severity.WARNING, "'reach'", "three-element"),
                () -> assertSays(notNumbers, Severity.WARNING, "'reach'", "not a finite number"));
    }

    // ------------------------------------------------------------------------------------------
    // Nothing is ignored silently
    // ------------------------------------------------------------------------------------------

    @Test
    void warnsAboutAnUnknownKeyAtEveryLevel() {
        final RigLoadResult result = load("""
                name: doll
                model: m
                version: 2
                bones:
                  body:
                    colour: red
                  hair:
                    parent: body
                    physics:
                      kind: pendulum
                      stifness: 0.5
                      limits:
                        pich: 10
                """);

        assertAll(
                // A typo'd key is the quietest failure a config format has: the value is simply never
                // read and everything else works.
                () -> assertTrue(result.isSuccess()),
                () -> assertSays(result, Severity.WARNING, "'version'", "the rig file"),
                () -> assertSays(result, Severity.WARNING, "'colour'", "bone 'body'"),
                () -> assertSays(result, Severity.WARNING, "'stifness'", "physics block of bone 'hair'"),
                () -> assertSays(result, Severity.WARNING, "'pich'", "limits block of bone 'hair'"),
                () -> assertEquals(4, result.diagnostics(Severity.WARNING).size(), render(result)));
    }

    @Test
    void warnsAboutTuningTheChosenKindNeverReads() {
        final RigLoadResult pendulum = loadHair("""
                kind: pendulum
                lag: 0.9
                collision-radius: 0.5
                """);
        final RigLoadResult drag = loadHair("""
                kind: drag
                stiffness: 0.5
                gravity: 1.0
                """);

        assertAll(
                () -> assertSays(pendulum, Severity.WARNING, "'lag'", "pendulum does not read"),
                () -> assertSays(pendulum, Severity.WARNING, "'collision-radius'", "does not read"),
                () -> assertSays(drag, Severity.WARNING, "'stiffness'", "drag does not read"),
                () -> assertSays(drag, Severity.WARNING, "'gravity'", "drag does not read"));
    }

    @Test
    void acceptsAnOptionKeyHoweverItIsSpelled() {
        final RigLoadResult result = loadHair("""
                KIND: cloth_strip
                Collision_Radius: 0.5
                """);

        assertAll(
                () -> assertNoDiagnostics(result),
                () -> assertEquals(PhysicsKind.CLOTH_STRIP, hairPhysics(result).kind()),
                () -> assertEquals(0.5F, hairPhysics(result).collisionRadius(), EPSILON));
    }

    @Test
    void warnsWhenTwoSpellingsOfOneKeyCollide() {
        final RigLoadResult result = loadHair("""
                kind: cloth_strip
                collision-radius: 0.5
                collisionRadius: 1.5
                """);

        assertAll(
                () -> assertEquals(1.5F, hairPhysics(result).collisionRadius(), EPSILON),
                () -> assertSays(result, Severity.WARNING, "more than once", "the last one wins"));
    }

    @Test
    void collectsEveryBonesComplaintRatherThanStoppingAtTheFirst() {
        final RigLoadResult result = load("""
                name: doll
                model: m
                bones:
                  body: {}
                  hair:
                    parent: body
                    physics:
                      kind: pendulum
                      stiffness: 7
                  tail:
                    parent: body
                    physics:
                      kind: spring
                      mass: 99
                """);

        assertAll(
                () -> assertTrue(result.isSuccess()),
                // One bad bone must not hide the next nine; that is the whole reason the loader
                // accumulates diagnostics instead of throwing on the first problem.
                () -> assertSays(result, Severity.WARNING, "bone 'hair'", "'stiffness' 7.0"),
                () -> assertSays(result, Severity.WARNING, "bone 'tail'", "'mass' 99.0"),
                () -> assertEquals(2, result.diagnostics(Severity.WARNING).size(), render(result)));
    }

    // ------------------------------------------------------------------------------------------
    // Names, and what a caller gets to check against a real model
    // ------------------------------------------------------------------------------------------

    @Test
    void matchesBoneNamesOnTheBareLowerCasedName() {
        final RigLoadResult result = load("""
                name: doll
                model: m
                bones:
                  Body: {}
                  HAIR:
                    parent: bOdY
                    physics:
                      kind: drag
                """);

        final Rig rig = result.rigOrThrow();
        assertAll(
                () -> assertNoDiagnostics(result),
                () -> assertEquals(List.of("body", "hair"), names(rig)),
                () -> assertEquals(0, rig.bone(1).parentIndex(), "a parent reference folds the same way"),
                () -> assertTrue(rig.indexOf("hAiR").isPresent()));
    }

    @Test
    void exposesTheBoneNamesARigExpectsSoABinderCanCheckThemFirst() {
        final RigLoadResult result = load("""
                name: doll
                model: m
                bones:
                  Body: {}
                  hair: {parent: body}
                """);
        final Set<String> expected = result.expectedBoneNames();

        assertAll(
                () -> assertEquals(Set.of("body", "hair"), expected),
                () -> assertEquals(List.of("body", "hair"), List.copyOf(expected),
                        "root-first, so a mismatch report reads outward from the root"),
                () -> assertEquals(expected, RigLoader.expectedBoneNames(result.rigOrThrow())),
                () -> assertThrows(UnsupportedOperationException.class, () -> expected.add("tail")),
                // The loader never resolves these against a model: it has none to ask, and a rig file
                // is readable long before the renderer has loaded its assets.
                () -> assertFalse(result.hasWarnings(), "an unresolved name is not a load problem"));
    }

    // ------------------------------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------------------------------

    @Test
    void everyDiagnosticNamesTheFileItCameFrom() {
        final RigLoadResult result = load("""
                model: m
                mystery: 1
                bones:
                  body: {}
                  hair:
                    parent: body
                    physics:
                      kind: pendulum
                      stiffness: 9
                """);

        assertFalse(result.diagnostics().isEmpty());
        for (final RigDiagnostic diagnostic : result.diagnostics()) {
            // An operator running thirty rigs cannot act on a warning that does not say which file
            // to open, and that is the only reason the loader takes a source name at all.
            assertTrue(diagnostic.format().contains(SOURCE),
                    () -> "a diagnostic that names no file: " + diagnostic.format());
        }
    }

    @Test
    void loadsFromAFileAndNamesThatFileRatherThanItsPath(@TempDir Path directory) throws IOException {
        final Path file = directory.resolve("windswept.yml");
        Files.writeString(file, """
                model: banner
                bones:
                  pole: {}
                  flag:
                    parent: pole
                    physics:
                      kind: cloth_strip
                """);

        final RigLoadResult result = RigLoader.loadFile(file, RigFixtures.SETTINGS);
        final RigDiagnostic named = result.diagnostics().getFirst();

        assertAll(
                () -> assertTrue(result.isSuccess(), () -> render(result)),
                () -> assertEquals(2, result.rigOrThrow().boneCount()),
                () -> assertEquals("windswept", result.rigOrThrow().getName(),
                        "a file that does not name itself is named after the file"),
                () -> assertTrue(named.format().contains("windswept.yml"), named::format),
                () -> assertFalse(named.format().contains(directory.toString()),
                        "the file name is enough; a temp path is noise in a log"));
    }

    @Test
    void reportsAnUnreadableFileAsAnErrorRatherThanThrowing(@TempDir Path directory) {
        final RigLoadResult result =
                RigLoader.loadFile(directory.resolve("absent.yml"), RigFixtures.SETTINGS);

        assertAll(
                () -> assertFalse(result.isSuccess()),
                // A caller walking a directory of rigs wants to report the one that failed and load
                // the rest, which an IOException out of the middle of the loop makes awkward.
                () -> assertSays(result, Severity.ERROR, "absent.yml", "could not be read"));
    }

    @Test
    void loadsTheDocumentedExampleFromARealFileToo(@TempDir Path directory) throws IOException {
        final Path file = directory.resolve("example.yml");
        Files.writeString(file, RigLoader.EXAMPLE);

        final RigLoadResult result = RigLoader.loadFile(file, RigFixtures.SETTINGS);

        assertAll(
                () -> assertNoDiagnostics(result),
                () -> assertEquals(5, result.rigOrThrow().boneCount()));
    }

    // ------------------------------------------------------------------------------------------
    // Injected configuration is honoured, not shadowed by a leftover default
    // ------------------------------------------------------------------------------------------
    //
    // Every case below uses deliberately unusual values. That is the point: a suite that only ever
    // exercised the stock figures cannot distinguish a loader that reads its settings from one that
    // ignores them and happens to agree.

    /**
     * Seeds that disagree with the stock figures in both directions.
     *
     * <p>Stiffness, mass and the pendulum's gravity above the stock value; damping, lag, wind and the
     * cloth strip's gravity below it; the spring's gravity off zero; the four gravities mutually
     * distinct so a seed reaching the wrong kind shows up; and a reach pointing up and sideways, which
     * no fall-back to a hangs-straight-down default could produce. All of them inside
     * {@link RigFixtures#PROFILE_BOUNDS}, as {@link RigLoadSettings} insists.
     */
    private static final ProfileDefaults ODD_DEFAULTS = new ProfileDefaults(
            0.92F, 0.004F, 0.11F, 3.75F, 0.3F,
            1.9F, 0.6F, 0.05F, 2.5F,
            2F, 0.5F, -1F);

    @Test
    void inheritsTheConfiguredSeedForEveryCoefficientTheFileOmits() {
        // A bone that names nothing but its kind is tuned entirely by configuration, which is the whole
        // reason the seeds are configuration and not literals.
        final RigLoadResult result = load("""
                name: doll
                model: doll_model
                bones:
                  body: {}
                  hair:
                    parent: body
                    physics:
                      kind: pendulum
                """, new RigLoadSettings(RigFixtures.SOLVER, RigFixtures.RIG_BOUNDS,
                        RigFixtures.SEAT_BOUNDS, RigFixtures.PROFILE_BOUNDS, ODD_DEFAULTS,
                        0.99F, 1.0E-4F));

        final PhysicsProfile hair = hairPhysics(result);
        // The api normalises the reach, so what the file inherits is the configured direction.
        final float length = (float) Math.sqrt(2F * 2F + 0.5F * 0.5F + 1F * 1F);
        assertAll(
                () -> assertNoDiagnostics(result),
                () -> assertEquals(0.92F, hair.stiffness(), EPSILON),
                () -> assertEquals(0.004F, hair.damping(), EPSILON),
                () -> assertEquals(0.11F, hair.lag(), EPSILON),
                () -> assertEquals(3.75F, hair.mass(), EPSILON),
                () -> assertEquals(0.3F, hair.wind(), EPSILON),
                () -> assertEquals(1.9F, hair.gravity(), EPSILON),
                () -> assertEquals(2F / length, hair.reachX(), EPSILON),
                () -> assertEquals(0.5F / length, hair.reachY(), EPSILON),
                () -> assertEquals(-1F / length, hair.reachZ(), EPSILON),
                // The axis bound still comes from the bounds rather than from the seeds, which is where
                // the operator's half-width lives.
                () -> assertEquals(DEFAULT_LIMITS, hair.limits()));
    }

    @Test
    void inheritsTheConfiguredGravityOfEachKindSeparately() {
        // The one difference between the kinds that has to survive being configurable: a pendulum with
        // no gravity hangs nowhere and a spring with gravity is not a spring, so a single seed shared
        // across the kinds would be wrong whichever value it held.
        final RigLoadResult result = load("""
                name: doll
                model: doll_model
                bones:
                  body: {}
                  hair:
                    parent: body
                    physics:
                      kind: pendulum
                  antenna:
                    parent: body
                    physics:
                      kind: spring
                  cape:
                    parent: body
                    physics:
                      kind: cloth_strip
                  tassel:
                    parent: body
                    physics:
                      kind: drag
                """, new RigLoadSettings(RigFixtures.SOLVER, RigFixtures.RIG_BOUNDS,
                        RigFixtures.SEAT_BOUNDS, RigFixtures.PROFILE_BOUNDS, ODD_DEFAULTS,
                        0.99F, 1.0E-4F));

        final Rig rig = result.rigOrThrow();
        assertAll(
                () -> assertNoDiagnostics(result),
                () -> assertEquals(1.9F, gravityOf(rig, "hair"), EPSILON),
                () -> assertEquals(0.6F, gravityOf(rig, "antenna"), EPSILON,
                        "a configured spring gravity is inherited, not replaced with a hardcoded zero"),
                () -> assertEquals(0.05F, gravityOf(rig, "cape"), EPSILON),
                () -> assertEquals(2.5F, gravityOf(rig, "tassel"), EPSILON));
    }

    private static float gravityOf(Rig rig, String boneName) {
        final PhysicsProfile physics = rig.bone(rig.indexOf(boneName).orElseThrow()).physics();
        assertNotNull(physics, () -> "expected " + boneName + " to keep its physics");
        return physics.gravity();
    }

    @Test
    void refusesSettingsWhoseBoundsWouldNotAdmitTheirOwnSeeds() {
        // A loader assembled this way could only ever produce bones the builder refuses, one per bone
        // that trusted a seed. Better to refuse the settings than to report the mismatch as a rig
        // problem forty times.
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new RigLoadSettings(RigFixtures.SOLVER, RigFixtures.RIG_BOUNDS,
                        RigFixtures.SEAT_BOUNDS,
                        new ProfileBounds(0.5F, 1.5F, 4F, 4F, 2F, 1.7F, 45F),
                        ODD_DEFAULTS, 0.99F, 1.0E-4F));
        assertTrue(refused.getMessage().contains("mass"),
                () -> "it has to name the seed that does not fit: " + refused.getMessage());
    }

    @Test
    void clampsToTheConfiguredRangesRatherThanTheStockOnes() {
        // Tight, odd bounds — but wide enough to admit the configured seeds, which the loader reads
        // back for any value the file omits and which RigLoadSettings refuses to pair with bounds that
        // would exclude them.
        final ProfileBounds narrow =
                new ProfileBounds(0.5F, 1.5F, 0.3F, 1.2F, 0.1F, 3.4F, 10F);
        final RigLoadResult result = load("""
                name: doll
                model: doll_model
                bones:
                  body: {}
                  hair:
                    parent: body
                    physics:
                      kind: cloth_strip
                      mass: 9
                      gravity: 3
                      wind: 3
                      collision-radius: 1.5
                """, new RigLoadSettings(RigFixtures.SOLVER, RigFixtures.RIG_BOUNDS,
                        RigFixtures.SEAT_BOUNDS, narrow, RigFixtures.PROFILE_DEFAULTS,
                        0.99F, 1.0E-4F));

        final PhysicsProfile hair = hairPhysics(result);
        assertAll(
                // Each of these is far below the stock ceiling, so a loader using the stock bounds
                // would keep the file's value verbatim and fail every one of them.
                () -> assertEquals(1.5F, hair.mass(), EPSILON),
                () -> assertEquals(0.3F, hair.gravity(), EPSILON),
                () -> assertEquals(1.2F, hair.wind(), EPSILON),
                () -> assertEquals(0.1F, hair.collisionRadius(), EPSILON),
                // And the warning quotes the configured range, so the operator can see which bound
                // they are up against rather than a figure from somebody else's server.
                () -> assertSays(result, Severity.WARNING, "'mass' 9.0", "[0.5, 1.5]", "clamped to 1.5"),
                () -> assertSays(result, Severity.WARNING, "'gravity' 3.0", "clamped to 0.3"));
    }

    @Test
    void dropsBonesPastTheConfiguredCeilingAndQuotesIt() {
        final RigLoadSettings tiny = new RigLoadSettings(
                RigFixtures.SOLVER, new RigBounds(3), RigFixtures.SEAT_BOUNDS,
                RigFixtures.PROFILE_BOUNDS,
                RigFixtures.PROFILE_DEFAULTS, 0.99F, 1.0E-4F);
        final RigLoadResult result = load("""
                name: chain
                model: m
                bones:
                  b1: {}
                  b2: {parent: b1}
                  b3: {parent: b2}
                  b4: {parent: b3}
                  b5: {parent: b4}
                """, tiny);

        final Rig rig = result.rigOrThrow();
        assertAll(
                // A loader holding the stock 256 would load all five and fail here.
                () -> assertEquals(3, rig.boneCount()),
                () -> assertTrue(rig.indexOf("b3").isPresent()),
                () -> assertTrue(rig.indexOf("b4").isEmpty()),
                () -> assertSays(result, Severity.WARNING, "2 bones dropped", "3-bone ceiling"));
    }

    @Test
    void clampsDampingAgainstTheConfiguredSubstepRate() {
        // The bound is 2 · mass · rate / dampingToRate, so a 20 Hz calibration permits a third of
        // what a 60 Hz one does. Damping 0.5 against mass 1 is comfortably legal at 60 Hz and
        // divergent at 20 — which is exactly the pair that tells the two apart.
        final AngularPhysicsSolver slow = new AngularPhysicsSolver(RigFixtures.tuning(20));
        final String physics = """
                kind: pendulum
                damping: 0.5
                mass: 1.0
                """;

        final RigLoadResult at60 = loadHair(physics);
        final RigLoadResult at20 = load("""
                name: doll
                model: doll_model
                bones:
                  body: {}
                  hair:
                    parent: body
                    physics:
                """ + physics.indent(6), RigFixtures.settings(slow));

        assertAll(
                () -> assertEquals(0.5F, hairPhysics(at60).damping(), EPSILON,
                        "legal at the stock rate, so nothing to clamp"),
                () -> assertNoDiagnostics(at60),
                () -> assertEquals(slow.maxStableDamping(1F, 20) * 0.99F,
                        hairPhysics(at20).damping(), EPSILON,
                        "and clamped to the slower calibration's own bound"),
                // The diagnostic has to quote the configured rate: "at 60 Hz" on a 20 Hz server sends
                // the author looking for a bound that is not the one that fired.
                () -> assertSays(at20, Severity.WARNING, "bone 'hair'", "at 20 Hz", "damping 0.5"));
    }

    @Test
    void placesAClampedDampingAtTheConfiguredMargin() {
        final RigLoadSettings halfway = new RigLoadSettings(
                RigFixtures.SOLVER, RigFixtures.RIG_BOUNDS, RigFixtures.SEAT_BOUNDS,
                RigFixtures.PROFILE_BOUNDS,
                RigFixtures.PROFILE_DEFAULTS, 0.5F, 1.0E-4F);
        final RigLoadResult result = load("""
                name: doll
                model: doll_model
                bones:
                  body: {}
                  hair:
                    parent: body
                    physics:
                      kind: pendulum
                      damping: 1.0
                      mass: 0.5
                """, halfway);

        // maxStableDamping(0.5, 60) is 0.5; at a margin of 0.5 the clamp lands on 0.25, which is
        // nowhere near where the stock 0.99 margin would put it.
        assertAll(
                () -> assertEquals(0.25F, hairPhysics(result).damping(), EPSILON),
                () -> assertSays(result, Severity.WARNING, "clamped to 0.25"));
    }

    @Test
    void treatsAReachShorterThanTheConfiguredMinimumAsPointingNowhere() {
        // Normalisation hides a bad threshold: a vector that is merely short still normalises to the
        // right direction. So the discriminator is a short vector pointing somewhere the default does
        // not — kept, it gives reachX 1; refused, it falls back to the default's reachY of -1.
        final RigLoadSettings coarse = new RigLoadSettings(
                RigFixtures.SOLVER, RigFixtures.RIG_BOUNDS, RigFixtures.SEAT_BOUNDS,
                RigFixtures.PROFILE_BOUNDS,
                RigFixtures.PROFILE_DEFAULTS, 0.99F, 1F);
        final String physics = """
                kind: pendulum
                reach: [0.5, 0.0, 0.0]
                """;
        final RigLoadResult kept = loadHair(physics);
        final RigLoadResult refused = load("""
                name: doll
                model: doll_model
                bones:
                  body: {}
                  hair:
                    parent: body
                    physics:
                """ + physics.indent(6), coarse);

        assertAll(
                () -> assertEquals(1F, hairPhysics(kept).reachX(), EPSILON,
                        "half a block is far above the stock minimum"),
                () -> assertEquals(0F, hairPhysics(refused).reachX(), EPSILON),
                () -> assertEquals(-1F, hairPhysics(refused).reachY(), EPSILON,
                        "a refused reach falls back to the kind's default direction"),
                () -> assertSays(refused, Severity.WARNING, "points nowhere"));
    }

    @Test
    void refusesSettingsThatCouldNotClampSafely() {
        assertAll(
                // A margin of 1 lands the clamp exactly on an exclusive bound, so the solver refuses
                // the rig the loader just reported it had made safe.
                () -> assertThrows(IllegalArgumentException.class, () -> new RigLoadSettings(
                        RigFixtures.SOLVER, RigFixtures.RIG_BOUNDS, RigFixtures.SEAT_BOUNDS,
                        RigFixtures.PROFILE_BOUNDS,
                        RigFixtures.PROFILE_DEFAULTS, 1F, 1.0E-4F)),
                () -> assertThrows(IllegalArgumentException.class, () -> new RigLoadSettings(
                        RigFixtures.SOLVER, RigFixtures.RIG_BOUNDS, RigFixtures.SEAT_BOUNDS,
                        RigFixtures.PROFILE_BOUNDS,
                        RigFixtures.PROFILE_DEFAULTS, 0F, 1.0E-4F)),
                () -> assertThrows(IllegalArgumentException.class, () -> new RigLoadSettings(
                        RigFixtures.SOLVER, RigFixtures.RIG_BOUNDS, RigFixtures.SEAT_BOUNDS,
                        RigFixtures.PROFILE_BOUNDS,
                        RigFixtures.PROFILE_DEFAULTS, 0.99F, 0F)),
                () -> assertEquals(60, RigFixtures.SETTINGS.substepRateHz(),
                        "the rate is read off the solver, never carried as a copy that could diverge"));
    }

    // ------------------------------------------------------------------------------------------
    // kind
    // ------------------------------------------------------------------------------------------

    @Test
    void readsARigWithNoKindAsACreature() {
        // The compatibility case, and the reason creature is the default: every rig file written
        // before the key existed has to keep meaning what it meant, including the seven in this
        // repository's own rigs/ folder.
        final RigLoadResult result = load("""
                name: doll
                model: doll_model
                bones:
                  body: {}
                """);

        assertAll(
                () -> assertNoDiagnostics(result),
                () -> assertEquals(RigKind.CREATURE, result.rigOrThrow().kind()),
                () -> assertEquals(0, result.rigOrThrow().seatCount()),
                () -> assertTrue(result.rigOrThrow().clips().isEmpty()),
                () -> assertFalse(result.rigOrThrow().bone(0).animationOwned()));
    }

    @Test
    void acceptsAKindWhateverTheCase() {
        for (final String spelling : new String[]{"vehicle", "VEHICLE", "Vehicle"}) {
            final RigLoadResult result = load(
                    "name: cart\nmodel: cart_model\nkind: " + spelling + "\nbones:\n  chassis: {}\n");
            assertAll(
                    () -> assertNoDiagnostics(result),
                    () -> assertEquals(RigKind.VEHICLE, result.rigOrThrow().kind(),
                            "'" + spelling + "' should name the vehicle kind"));
        }
    }

    @Test
    void refusesARigWhoseKindNothingRecognises() {
        // Hard, like an unknown arm-swing-axis and unlike almost everything else here. The kind gates
        // whole sections, so falling back after a typo loads a file that means something other than
        // what it says - and the shape of that mistake is a vehicle whose seats were all discarded.
        final RigLoadResult result = load("""
                name: cart
                model: cart_model
                kind: vehicel
                bones:
                  chassis: {}
                """);

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertSays(result, Severity.ERROR, "vehicel", "creature, vehicle, decoration"));
    }

    // ------------------------------------------------------------------------------------------
    // seats
    // ------------------------------------------------------------------------------------------

    /** A vehicle rig with {@code seats} spliced in, which is the only kind that may have any. */
    private static RigLoadResult loadCart(String seatsBlock) {
        return load("""
                name: cart
                model: cart_model
                kind: vehicle
                bones:
                  chassis: {}
                seats:
                """ + seatsBlock.indent(2));
    }

    @Test
    void readsSeatsInFileOrderWithOneDriver() {
        final RigLoadResult result = loadCart("""
                - locator: seat_driver
                  driver: true
                - name: bench_left
                  locator: h_seat_bench_left
                  offset: [0.25, 0.5, -0.25]
                """);
        final Rig rig = result.rigOrThrow();
        final RigSeat driver = rig.seats().get(0);
        final RigSeat bench = rig.seats().get(1);

        assertAll(
                () -> assertNoDiagnostics(result),
                () -> assertEquals(2, rig.seatCount()),
                // File order is the order riders are offered seats in, so it is part of what the file
                // says rather than an implementation detail of whichever map read it.
                () -> assertEquals("seat_driver", driver.name(),
                        "a seat with no 'name' is named after its locator"),
                () -> assertTrue(driver.driver()),
                () -> assertTrue(driver.isOnLocator()),
                () -> assertEquals(driver, rig.driverSeat().orElseThrow()),
                () -> assertEquals("bench_left", bench.name()),
                // Normalised exactly like a bone name: lower-cased with renderer tag prefixes
                // stripped, because a locator reaches the bone tree with the same kind of identifier.
                () -> assertEquals("seat_bench_left", bench.locator()),
                () -> assertFalse(bench.driver()),
                () -> assertEquals(0.25F, bench.offsetX(), EPSILON),
                () -> assertEquals(0.5F, bench.offsetY(), EPSILON),
                () -> assertEquals(-0.25F, bench.offsetZ(), EPSILON),
                () -> assertEquals(bench, rig.findSeat("BENCH_LEFT").orElseThrow(),
                        "a seat name is ours to interpret, so it is matched case-insensitively"));
    }

    @Test
    void dropsTheSeatsOfARigThatIsNotAVehicle() {
        // Soft, and the seats go rather than the rig: the likely mistake is a forgotten
        // `kind: vehicle`, and reading a creature as a vehicle instead would make it rideable on the
        // strength of a key the file never wrote.
        final RigLoadResult result = load("""
                name: deer
                model: deer_model
                bones:
                  torso: {}
                seats:
                  - locator: seat_saddle
                """);

        assertAll(
                () -> assertTrue(result.isSuccess(), "the rig itself is still perfectly loadable"),
                () -> assertEquals(0, result.rigOrThrow().seatCount()),
                () -> assertSays(result, Severity.WARNING, "'seats'", "creature", "kind: vehicle"));
    }

    @Test
    void dropsASeatThatNamesABoneRatherThanALocator() {
        // The diagnostic this whole section exists for. A seat is a locator - the empty, cube-less
        // marker a rigger places - and never a bone, because a bone is geometry the model's own
        // animation pass moves every frame a clip plays. There is deliberately no fallback: a seat
        // that resolved to a bone would work badly and silently, which is the one outcome worse than
        // not working.
        final RigLoadResult result = loadCart("""
                - locator: chassis
                """);

        assertAll(
                () -> assertTrue(result.isSuccess()),
                () -> assertEquals(0, result.rigOrThrow().seatCount()),
                () -> assertSays(result, Severity.WARNING, "chassis", "as a bone", "locator"));
    }

    @Test
    void dropsASeatWithNoLocator() {
        final RigLoadResult result = loadCart("""
                - name: nowhere
                  driver: true
                """);

        assertAll(
                () -> assertEquals(0, result.rigOrThrow().seatCount()),
                () -> assertSays(result, Severity.WARNING, "no 'locator'"));
    }

    @Test
    void readsASecondDriversSeatAsAPassengerSeat() {
        // Demoted rather than dropped: a vehicle takes its input from one rider, which is no reason to
        // lose a place to sit. The warning names the seat that keeps the wheel.
        final RigLoadResult result = loadCart("""
                - locator: seat_front
                  driver: true
                - locator: seat_back
                  driver: 'true'
                """);
        final Rig rig = result.rigOrThrow();

        assertAll(
                () -> assertEquals(2, rig.seatCount()),
                () -> assertEquals("seat_front", rig.driverSeat().orElseThrow().name()),
                () -> assertFalse(rig.seats().get(1).driver()),
                () -> assertSays(result, Severity.WARNING, "seat_back", "seat_front", "one rider"));
    }

    @Test
    void dropsASecondSeatWithTheSameName() {
        final RigLoadResult result = loadCart("""
                - name: bench
                  locator: seat_left
                - name: BENCH
                  locator: seat_right
                """);

        assertAll(
                () -> assertEquals(1, result.rigOrThrow().seatCount()),
                () -> assertEquals("seat_left", result.rigOrThrow().seats().get(0).locator(),
                        "the first declaration is the one a consumer would have got"),
                () -> assertSays(result, Severity.WARNING, "'bench' twice"));
    }

    @Test
    void clampsARiderOffsetToTheConfiguredCeiling() {
        // Clamped like any coefficient, and against the operator's figure rather than a stock one: the
        // ceiling is small on purpose, because a seat IS its locator and the offset only trims it.
        final RigLoadResult result = load("""
                name: cart
                model: cart_model
                kind: vehicle
                bones:
                  chassis: {}
                seats:
                  - locator: seat_driver
                    offset: [0.0, 4.0, 0.0]
                """, RigFixtures.settings(new SeatBounds(4, 0.25F)));

        assertAll(
                () -> assertEquals(0.25F, result.rigOrThrow().seats().get(0).offsetY(), EPSILON),
                () -> assertSays(result, Severity.WARNING, "+/-0.25", "clamped to 0.25"));
    }

    @Test
    void seatsTheRiderOnTheLocatorWhenTheOffsetIsUnreadable() {
        final RigLoadResult result = loadCart("""
                - locator: seat_driver
                  offset: [0.0, 0.5]
                """);

        assertAll(
                () -> assertTrue(result.rigOrThrow().seats().get(0).isOnLocator()),
                () -> assertSays(result, Severity.WARNING, "'offset'", "[x, y, z]"));
    }

    @Test
    void dropsSeatsPastTheConfiguredCeiling() {
        // From the end, because the end is the last riders would have been offered.
        final RigLoadResult result = load("""
                name: cart
                model: cart_model
                kind: vehicle
                bones:
                  chassis: {}
                seats:
                  - locator: seat_a
                  - locator: seat_b
                """, RigFixtures.settings(new SeatBounds(1, 1F)));

        assertAll(
                () -> assertEquals(1, result.rigOrThrow().seatCount()),
                () -> assertEquals("seat_a", result.rigOrThrow().seats().get(0).locator()),
                () -> assertSays(result, Severity.WARNING, "seat_b", "1-seat ceiling"));
    }

    @Test
    void reportsASeatsSectionThatIsNotAList() {
        // A mapping would load and would order its seats by whatever the parser felt like, which is
        // exactly what the list form exists to stop.
        final RigLoadResult result = load("""
                name: cart
                model: cart_model
                kind: vehicle
                bones:
                  chassis: {}
                seats:
                  driver:
                    locator: seat_driver
                """);

        assertAll(
                () -> assertTrue(result.isSuccess()),
                () -> assertEquals(0, result.rigOrThrow().seatCount()),
                () -> assertSays(result, Severity.WARNING, "'seats'", "list", "file order"));
    }

    @Test
    void reportsAnUnknownKeyInASeatsEntry() {
        final RigLoadResult result = loadCart("""
                - locator: seat_driver
                  pasenger: true
                """);

        assertAll(
                () -> assertEquals(1, result.rigOrThrow().seatCount()),
                // The known-key list is quoted back in the author's own spelling, because a warning
                // that lists canonical forms helps nobody.
                () -> assertSays(result, Severity.WARNING, "'pasenger'",
                        "name, locator, driver, offset"));
    }

    // ------------------------------------------------------------------------------------------
    // clips, and the animation-owned flag
    // ------------------------------------------------------------------------------------------

    @Test
    void bindsTheModelsOwnClipsToLocomotionStatesAndKeepsTheirSpellingExactly() {
        // The one place in this format where a name is NOT lower-cased. A clip name is looked up
        // inside a .bbmodel this plugin does not own, and the models in this repository ship clips
        // called standUp and lookaround - normalising would name a clip the model does not have.
        final RigLoadResult result = load("""
                name: squirrel
                model: squirrel_model
                clips:
                  idle: idle
                  walk: walk
                  run: standUp
                bones:
                  body: {}
                """);
        final Rig rig = result.rigOrThrow();

        assertAll(
                () -> assertNoDiagnostics(result),
                () -> assertEquals("idle", rig.clips().clip(ClipRole.IDLE)),
                () -> assertEquals("walk", rig.clips().clip(ClipRole.WALK)),
                () -> assertEquals("standUp", rig.clips().clip(ClipRole.RUN),
                        "the rigger's capitals are the renderer's lookup key"),
                () -> assertEquals(3, rig.clips().size()));
    }

    @Test
    void bindsOnlyTheStatesTheFileNames() {
        final RigLoadResult result = load("""
                name: banner
                model: banner_model
                clips:
                  idle: sway
                bones:
                  pole: {}
                """);

        assertAll(
                () -> assertNoDiagnostics(result),
                () -> assertEquals("sway", result.rigOrThrow().clips().clip(ClipRole.IDLE)),
                () -> assertFalse(result.rigOrThrow().clips().declares(ClipRole.WALK),
                        "a model with no walk cycle binds no walk clip"),
                () -> assertFalse(result.rigOrThrow().clips().declares(ClipRole.RUN)));
    }

    @Test
    void reportsAClipRoleNothingCanBeIn() {
        // A state the pipeline cannot tell it is in is a clip that could never be chosen, so the key
        // is as inert as a misspelled coefficient - and as quiet, without this.
        final RigLoadResult result = load("""
                name: doll
                model: doll_model
                clips:
                  sprint: running
                bones:
                  body: {}
                """);

        assertAll(
                () -> assertTrue(result.isSuccess()),
                () -> assertTrue(result.rigOrThrow().clips().isEmpty()),
                () -> assertSays(result, Severity.WARNING, "'sprint'", "idle, walk, run"));
    }

    @Test
    void reportsAClipsBlockThatIsNotAMapping() {
        final RigLoadResult result = load("""
                name: doll
                model: doll_model
                clips: idle
                bones:
                  body: {}
                """);

        assertAll(
                () -> assertTrue(result.rigOrThrow().clips().isEmpty()),
                () -> assertSays(result, Severity.WARNING, "'clips'", "idle, walk, run"));
    }

    @Test
    void reportsABlankClipName() {
        final RigLoadResult result = load("""
                name: doll
                model: doll_model
                clips:
                  idle: '   '
                bones:
                  body: {}
                """);

        assertAll(
                () -> assertFalse(result.rigOrThrow().clips().declares(ClipRole.IDLE)),
                () -> assertSays(result, Severity.WARNING, "blank", "'idle'"));
    }

    @Test
    void readsTheAnimationOwnedFlagHoweverItIsSpelled() {
        for (final String spelling : new String[]{"animation-owned", "animation_owned", "animationOwned"}) {
            final RigLoadResult result = load("""
                    name: deer
                    model: deer_model
                    bones:
                      torso:
                    """ + ("  " + spelling + ": true").indent(4));
            final Rig rig = result.rigOrThrow();

            assertAll(
                    () -> assertNoDiagnostics(result),
                    () -> assertTrue(rig.bone(0).animationOwned(),
                            "'" + spelling + "' should set the flag"),
                    () -> assertArrayEquals(new int[]{0}, rig.animationOwnedBoneIndices()));
        }
    }

    @Test
    void warnsWhenABoneIsBothAnimationOwnedAndSimulated() {
        // The defect the flag exists to make visible: both layers write the same display, the
        // renderer sending bind-composed-with-animation and this plugin bind-composed-with-physics,
        // and the last packet of the frame wins. Legal, because a rigger may know the two move the
        // bone on different axes - but never silent, because the symptom is a flickering bone and
        // nothing about it points at the rig file.
        final RigLoadResult result = load("""
                name: deer
                model: deer_model
                bones:
                  torso: {}
                  tail:
                    parent: torso
                    animation-owned: true
                    physics:
                      kind: pendulum
                """);
        final Rig rig = result.rigOrThrow();

        assertAll(
                () -> assertTrue(result.isSuccess()),
                () -> assertTrue(rig.bone(1).animationOwned()),
                () -> assertNotNull(rig.bone(1).physics(), "the physics is kept, not quietly dropped"),
                () -> assertSays(result, Severity.WARNING, "bone 'tail'", "pendulum physics",
                        "animation-owned"));
    }

    @Test
    void readsAnUnreadableAnimationOwnedFlagAsFalse() {
        final RigLoadResult result = load("""
                name: deer
                model: deer_model
                bones:
                  torso:
                    animation-owned: maybe
                """);

        assertAll(
                () -> assertFalse(result.rigOrThrow().bone(0).animationOwned()),
                () -> assertSays(result, Severity.WARNING, "'animation-owned'", "true or false"));
    }

    @Test
    void loadsARigWrittenBeforeAnyOfTheseSectionsExisted() {
        // The regression guard for the rigs this repository already ships: the same shape as
        // rigs/deer.yml, which must keep loading with exactly as many diagnostics as it did before
        // kinds, seats and clips existed - none.
        final RigLoadResult result = load("""
                name: deer
                model: deer
                arm-swing-axis: pitch
                hand-swing-axis: pitch

                bones:
                  torso: {}
                  neck:
                    parent: torso
                  head:
                    parent: neck
                    physics:
                      kind: spring
                      stiffness: 0.14
                      damping: 0.05
                      mass: 1.8
                      wind: 0.25
                      limits:
                        pitch: [-12, 12]
                        yaw: [-16, 16]
                        roll: [-10, 10]
                  leftleg:
                    parent: torso
                  leftleg_low:
                    parent: leftleg
                    physics:
                      kind: drag
                      lag: 0.2
                      mass: 1.1
                      wind: 0.2
                """);
        final Rig rig = result.rigOrThrow();

        assertAll(
                () -> assertNoDiagnostics(result),
                () -> assertEquals(RigKind.CREATURE, rig.kind()),
                () -> assertEquals(0, rig.seatCount()),
                () -> assertTrue(rig.clips().isEmpty()),
                () -> assertEquals(0, rig.animationOwnedBoneCount()),
                () -> assertEquals(5, rig.boneCount()),
                () -> assertEquals(2, rig.physicsBoneCount()));
    }
}
