package pandastic.studio.models.internal.rig;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.physics.PhysicsDriver;
import pandastic.studio.models.api.physics.PhysicsState;
import pandastic.studio.models.api.pose.Pose;
import pandastic.studio.models.api.rig.PhysicsKind;
import pandastic.studio.models.api.rig.PhysicsProfile;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.render.RigBoneResolution;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the factory over a fake {@link ModelBoneSource}, which is the only way it can be driven at all:
 * BetterModel is {@code compileOnly} for main and {@code testCompileOnly} for tests, so there is no
 * renderer here and no {@code ModelRenderer} to construct one from. That is the whole reason the
 * derivation is written against a seam — {@link ModelBoneSource#renderer()} is the thirty lines this
 * suite cannot reach, and it contains no decision.
 *
 * <p>Every derivation case ends by asking {@link RigBoneResolution} whether the rig it produced would
 * resolve against the same bone list it was derived from. A derived rig that validated and then bound to
 * nothing is the failure this feature exists to avoid, and it is invisible in a test that only asserts
 * the bone count.
 */
class RigFactoryTest {

    private static final String MODEL = "wolf";

    /** A bone the renderer will give a display, which is the only kind a rig may name. */
    private static ModelBoneLink drivable(String name, int parentIndex) {
        return new ModelBoneLink(name, parentIndex, true);
    }

    /** A group with no cubes in it: a pivot or an empty root, which gets no display. */
    private static ModelBoneLink pivot(String name, int parentIndex) {
        return new ModelBoneLink(name, parentIndex, false);
    }

    /** A source holding one model, counting how many times its bones are read. */
    private static final class CountingSource implements ModelBoneSource {

        private final AtomicInteger reads = new AtomicInteger();
        private String modelId;
        private List<ModelBoneLink> bones;

        CountingSource(String modelId, List<ModelBoneLink> bones) {
            this.modelId = modelId;
            this.bones = bones;
        }

        @Override
        public Optional<List<ModelBoneLink>> bonesOf(String modelId) {
            this.reads.incrementAndGet();
            return this.modelId.equals(modelId) ? Optional.of(this.bones) : Optional.empty();
        }

        int reads() {
            return this.reads.get();
        }

        void install(String newModelId, List<ModelBoneLink> newBones) {
            this.modelId = newModelId;
            this.bones = newBones;
        }
    }

    private static RigFactory factory(List<Rig> authored, ModelBoneSource models) {
        final List<Rig> rigs = new ArrayList<>(authored);
        return RigFactory.of(() -> rigs, models, () -> RigFixtures.SETTINGS);
    }

    private static RigFactory factory(ModelBoneSource models) {
        return factory(List.of(), models);
    }

    /** An authored rig with one physics bone, so it is distinguishable from any derivation. */
    private static Rig authored(String name, String modelId) {
        return Rig.builder(name, modelId, RigFixtures.RIG_BOUNDS, RigFixtures.PROFILE_BOUNDS)
                .root("body")
                .bone("tail", "body", PhysicsProfile
                        .builder(PhysicsKind.PENDULUM, RigFixtures.PROFILE_BOUNDS,
                                RigFixtures.PROFILE_DEFAULTS)
                        .build())
                .build();
    }

    private static String render(RigLoadResult result) {
        final StringBuilder rendered = new StringBuilder("diagnostics:");
        for (final RigDiagnostic diagnostic : result.diagnostics()) {
            rendered.append("\n  ").append(diagnostic.format());
        }
        return rendered.toString();
    }

    private static void assertMentions(RigLoadResult result, RigDiagnostic.Severity severity,
                                       String... needles) {
        for (final RigDiagnostic diagnostic : result.diagnostics()) {
            if (diagnostic.severity() != severity) {
                continue;
            }
            boolean all = true;
            for (final String needle : needles) {
                all &= diagnostic.message().contains(needle);
            }
            if (all) {
                return;
            }
        }
        throw new AssertionError("no " + severity + " mentioning " + List.of(needles) + "\n"
                + render(result));
    }

    // ------------------------------------------------------------------ precedence

    @Test
    void anAuthoredRigWinsAndTheModelIsNeverRead() {
        final Rig rig = authored("wolf_tail", MODEL);
        final CountingSource source = new CountingSource(MODEL, List.of(drivable("body", -1)));

        final RigLoadResult result = factory(List.of(rig), source).rigFor(MODEL);

        assertAll(
                () -> assertSame(rig, result.rigOrThrow(), render(result)),
                () -> assertEquals(List.of(), result.diagnostics()),
                // Not an optimisation: a derivation is only ever a fallback, so reading the model at all
                // on this path would mean the fallback could fail and take the authored rig with it.
                () -> assertEquals(0, source.reads()));
    }

    @Test
    void anAuthoredRigIsMatchedCaseInsensitively() {
        final Rig rig = authored("wolf_tail", "Wolf");
        final RigLoadResult result =
                factory(List.of(rig), new CountingSource(MODEL, List.of(drivable("body", -1))))
                        .rigFor("wOLF");

        assertSame(rig, result.rigOrThrow(), render(result));
    }

    @Test
    void anAuthoredRigForAnotherModelDoesNotWin() {
        final RigLoadResult result = factory(
                List.of(authored("sheep_wool", "sheep")),
                new CountingSource(MODEL, List.of(drivable("body", -1)))).rigFor(MODEL);

        assertAll(
                () -> assertEquals(1, result.rigOrThrow().boneCount(), render(result)),
                () -> assertEquals("passthrough:wolf", result.rigOrThrow().getName()));
    }

    @Test
    void twoAuthoredRigsForOneModelPickTheFirstNameAndWarn() {
        final Rig first = authored("a_wolf", MODEL);
        final Rig second = authored("b_wolf", MODEL);
        final CountingSource source = new CountingSource(MODEL, List.of(drivable("body", -1)));

        // Handed over in the order a concurrent map would not promise, which is the point: the answer is
        // the lexicographically first rig name either way.
        final RigLoadResult result = factory(List.of(second, first), source).rigFor(MODEL);

        assertAll(
                () -> assertSame(first, result.rigOrThrow(), render(result)),
                () -> assertTrue(result.hasWarnings(), render(result)),
                () -> assertMentions(result, RigDiagnostic.Severity.WARNING, "a_wolf", "b_wolf"));
    }

    // ------------------------------------------------------------------ derivation

    @Test
    void aDerivedRigNamesOneDrivableBoneAndCarriesNoPhysics() {
        final List<ModelBoneLink> bones = List.of(
                drivable("Body", -1),
                drivable("Tail", 0),
                drivable("Head", 0));
        final RigLoadResult result = factory(new CountingSource(MODEL, bones)).rigFor(MODEL);
        final Rig rig = result.rigOrThrow();

        assertAll(
                () -> assertEquals(1, rig.boneCount(), render(result)),
                () -> assertEquals(0, rig.physicsBoneCount()),
                () -> assertEquals(MODEL, rig.getModelId()),
                // Lower-cased, which is what Rig matches on: a rig keyed on 'Body' would validate here
                // and then resolve against nothing.
                () -> assertEquals("body", rig.bone(0).name()),
                () -> assertTrue(rig.bone(0).isRoot()),
                () -> assertEquals(List.of(), result.diagnostics()),
                () -> assertTrue(resolves(rig, bones), "the derived rig does not resolve against the"
                        + " very model it was derived from"));
    }

    @Test
    void aDerivedRigSkipsPivotsAndAmbiguousNamesInHierarchyOrder() {
        final List<ModelBoneLink> bones = List.of(
                pivot("root", -1),            // an empty root: no cubes, so no display
                drivable("leg", 0),           // drivable, but there are two of them
                drivable("torso", 0),         // the answer
                drivable("leg", 0));
        final RigLoadResult result = factory(new CountingSource(MODEL, bones)).rigFor(MODEL);

        assertAll(
                () -> assertEquals("torso", result.rigOrThrow().bone(0).name(), render(result)),
                () -> assertTrue(resolves(result.rigOrThrow(), bones)));
    }

    @Test
    void aDerivedRigPrefersTheMostRootWardBone() {
        final List<ModelBoneLink> bones = List.of(
                drivable("body", -1),
                drivable("tail", 0),
                drivable("tail_tip", 1));

        assertEquals("body",
                factory(new CountingSource(MODEL, bones)).rigFor(MODEL).rigOrThrow().bone(0).name());
    }

    @Test
    void aModelWithNothingDrivableIsRefusedWithBothCounts() {
        final List<ModelBoneLink> bones = List.of(
                pivot("root", -1),
                drivable("leg", 0),
                drivable("leg", 0));
        final RigLoadResult result = factory(new CountingSource(MODEL, bones)).rigFor(MODEL);

        assertAll(
                () -> assertFalse(result.isSuccess(), render(result)),
                () -> assertThrows(IllegalStateException.class, result::rigOrThrow),
                () -> assertMentions(result, RigDiagnostic.Severity.ERROR,
                        "3 bone(s)", "1 have no display", "2 share a bare name"));
    }

    @Test
    void aModelWithNoBonesIsRefused() {
        final RigLoadResult result = factory(new CountingSource(MODEL, List.of())).rigFor(MODEL);

        assertAll(
                () -> assertFalse(result.isSuccess(), render(result)),
                () -> assertMentions(result, RigDiagnostic.Severity.ERROR, "no bones at all"));
    }

    @Test
    void aDerivedRigIsAcceptedByTheSolverAndSolvesToNothing() {
        final RigLoadResult result =
                factory(new CountingSource(MODEL, List.of(drivable("body", -1)))).rigFor(MODEL);
        final Rig rig = result.rigOrThrow();

        // The claim the whole design rests on: a rig with no physics is legal and free. createState is
        // where a rig the current calibration cannot integrate is refused, and solve returns on an empty
        // state - so a pass-through rig has to pass both without a bone to simulate.
        final PhysicsState state = assertDoesNotThrow(() -> RigFixtures.SOLVER.createState(rig));
        final Pose pose = new Pose(rig.boneCount());
        assertAll(
                () -> assertDoesNotThrow(() -> RigFixtures.SOLVER.solve(
                        rig, state, pose, 1F / 20F, PhysicsDriver.STILL)),
                () -> assertEquals(0F, pose.pitch(0)),
                () -> assertEquals(0F, pose.yaw(0)),
                () -> assertEquals(0F, pose.roll(0)));
    }

    // ------------------------------------------------------------------ unknown models

    @Test
    void anUnknownModelIsReportedRatherThanThrown() {
        final RigLoadResult result =
                factory(new CountingSource("sheep", List.of(drivable("body", -1)))).rigFor(MODEL);

        assertAll(
                () -> assertFalse(result.isSuccess(), render(result)),
                () -> assertMentions(result, RigDiagnostic.Severity.ERROR, "renderer has loaded"),
                () -> assertTrue(result.diagnostics().getFirst().source().contains(MODEL),
                        "the diagnostic has to name the model, since there is no file to name"));
    }

    @Test
    void anUnknownModelIsNotCachedSoInstallingItLaterWorks() {
        final CountingSource source = new CountingSource("sheep", List.of(drivable("body", -1)));
        final RigFactory factory = factory(source);

        assertFalse(factory.rigFor(MODEL).isSuccess());
        source.install(MODEL, List.of(drivable("body", -1)));

        assertAll(
                () -> assertTrue(factory.rigFor(MODEL).isSuccess()),
                () -> assertEquals(1, factory.cachedCount()));
    }

    @Test
    void aBlankModelIdIsACallerDefect() {
        final RigFactory factory = factory(new CountingSource(MODEL, List.of(drivable("body", -1))));

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> factory.rigFor("   ")),
                () -> assertThrows(IllegalArgumentException.class, () -> factory.rigFor("")));
    }

    // ------------------------------------------------------------------ the cache

    @Test
    void aSecondRequestIsAnsweredFromTheCache() {
        final CountingSource source = new CountingSource(MODEL, List.of(drivable("body", -1)));
        final RigFactory factory = factory(source);

        final Rig first = factory.rigFor(MODEL).rigOrThrow();
        final Rig second = factory.rigFor(MODEL).rigOrThrow();

        assertAll(
                () -> assertSame(first, second),
                () -> assertEquals(1, source.reads()),
                () -> assertEquals(1, factory.cachedCount()));
    }

    @Test
    void invalidatingReReadsTheModel() {
        final CountingSource source = new CountingSource(MODEL, List.of(drivable("body", -1)));
        final RigFactory factory = factory(source);
        final Rig first = factory.rigFor(MODEL).rigOrThrow();

        assertTrue(factory.invalidate(MODEL));
        assertFalse(factory.invalidate(MODEL), "a second invalidation has nothing to forget");

        // What a renderer reload looks like from here: the same model id, different bones.
        source.install(MODEL, List.of(pivot("root", -1), drivable("chest", 0)));
        final Rig second = factory.rigFor(MODEL).rigOrThrow();

        assertAll(
                () -> assertEquals("body", first.bone(0).name()),
                () -> assertEquals("chest", second.bone(0).name()),
                () -> assertEquals(2, source.reads()));
    }

    @Test
    void invalidatingAllForgetsEveryDerivation() {
        final CountingSource source = new CountingSource(MODEL, List.of(drivable("body", -1)));
        final RigFactory factory = factory(source);
        factory.rigFor(MODEL);

        assertAll(
                () -> assertEquals(1, factory.invalidateAll()),
                () -> assertEquals(0, factory.cachedCount()),
                () -> assertEquals(0, factory.invalidateAll()));
    }

    @Test
    void theCacheIsKeyedExactlySoARigNeverPointsAtAnIdTheRendererCannotResolve() {
        final CountingSource source = new CountingSource(MODEL, List.of(drivable("body", -1)));
        final RigFactory factory = factory(source);

        assertTrue(factory.rigFor(MODEL).isSuccess());
        // 'Wolf' is a different id to the renderer, so answering it from the 'wolf' entry would hand back
        // a rig whose model id the renderer's own lookup may not resolve.
        assertFalse(factory.rigFor("Wolf").isSuccess());
    }

    @Test
    void anAuthoredRigIsNeverCachedSoAReloadedRigSetIsVisibleImmediately() {
        final CountingSource source = new CountingSource(MODEL, List.of(drivable("body", -1)));
        final List<Rig> rigs = new ArrayList<>();
        final RigFactory factory = RigFactory.of(() -> rigs, source, () -> RigFixtures.SETTINGS);

        assertEquals("passthrough:wolf", factory.rigFor(MODEL).rigOrThrow().getName());

        final Rig rig = authored("wolf_tail", MODEL);
        rigs.add(rig);

        // The derivation is still cached, and still loses: precedence is decided per request, so a rig
        // file added by a reload takes effect without anybody invalidating anything.
        assertSame(rig, factory.rigFor(MODEL).rigOrThrow());
    }

    // ------------------------------------------------------------------ the seam's contract

    @Test
    void aSourceThatPutsAChildBeforeItsParentIsADefect() {
        final ModelBoneSource broken = modelId -> Optional.of(List.of(
                drivable("tail", 1),
                drivable("body", -1)));
        final RigFactory factory = factory(broken);

        final IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> factory.rigFor(MODEL));
        assertTrue(thrown.getMessage().contains("precede"), thrown.getMessage());
    }

    @Test
    void aModelBoneNeedsANameAndAValidParent() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> drivable(" ", -1)),
                () -> assertThrows(IllegalArgumentException.class, () -> drivable("body", -2)));
    }

    /** Whether {@code rig} would bind: every bone found, exactly once, in the model it was derived from. */
    private static boolean resolves(Rig rig, List<ModelBoneLink> bones) {
        final List<String> names = new ArrayList<>(bones.size());
        for (final ModelBoneLink bone : bones) {
            names.add(bone.name());
        }
        final RigBoneResolution resolution = RigBoneResolution.resolve(rig, names);
        assertNotNull(resolution);
        return resolution.isBindable();
    }
}
