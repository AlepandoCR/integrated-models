package pandastic.studio.models.internal.binding;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.binding.BindingOptions;
import pandastic.studio.models.api.physics.DriverBounds;
import pandastic.studio.models.internal.sim.SimulationSettings;
import pandastic.studio.models.internal.sim.SimulationSettingsSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The seam a per-binding teleport filter arrives through.
 *
 * <p>Both of its values disagree with the shipped 1.5/6 and they disagree in opposite directions, so a
 * substitution that silently used the configured value instead would be visible.
 */
class DriverBoundsOverrideTest {

    /** Tighter than anything shipped. */
    private static final DriverBounds SERVER_WIDE = new DriverBounds(0.37F, 2.9F);

    /** Looser than anything shipped, and about what a projectile needs. */
    private static final DriverBounds PROJECTILE = new DriverBounds(4.25F, 7.5F);

    @Test
    void nothingStatedHandsBackTheServerSourceItself() {
        final SimulationSettingsSource serverWide = BindingFixtures.simulationSource();

        final SimulationSettingsSource forBinding =
                DriverBoundsOverride.sourceFor(serverWide, BindingOptions.defaults());

        // Not merely equivalent — the same object. The default path has to be wired exactly as it was
        // before per-binding bounds existed, so a server where nothing uses the feature pays nothing
        // for it: no wrapper, no second indirection, no memo.
        assertSame(serverWide, forBinding);
    }

    @Test
    void anOverrideSubstitutesTheBoundsAndNothingElse() {
        final SimulationSettings live = BindingFixtures.simulationSettings(SERVER_WIDE);
        final SimulationSettingsSource forBinding = DriverBoundsOverride.sourceFor(
                SimulationSettingsSource.fixed(live), BindingOptions.ofDriverBounds(PROJECTILE));

        final SimulationSettings derived = forBinding.current();

        assertAll(
                () -> assertSame(PROJECTILE, derived.driverBounds()),
                // Everything else is the server's business and must survive unchanged, or a binding
                // that wanted its own teleport filter would quietly get its own emission rate too.
                () -> assertSame(live.solver(), derived.solver()),
                () -> assertSame(live.cadence(), derived.cadence()),
                () -> assertEquals(live.simulationHz(), derived.simulationHz()),
                () -> assertEquals(live.errorLogIntervalNanos(), derived.errorLogIntervalNanos()));
    }

    @Test
    void theDerivedSnapshotIsOneInstanceForAsLongAsConfigurationStands() {
        final SimulationSettingsSource forBinding = DriverBoundsOverride.sourceFor(
                BindingFixtures.simulationSource(), BindingOptions.ofDriverBounds(PROJECTILE));

        // The sampling path runs once per tick per bound model on the region threads, so a derivation
        // per call would be an allocation per model per tick. It is also the allocation pattern that
        // makes a simulation's solver-identity guard fire every step and freeze the model.
        assertSame(forBinding.current(), forBinding.current());
    }

    @Test
    void theDelegateIsReadExactlyOncePerCall() {
        final BindingFixtures.MutableSimulationSource serverWide =
                new BindingFixtures.MutableSimulationSource(
                        BindingFixtures.simulationSettings(SERVER_WIDE));
        final SimulationSettingsSource forBinding =
                DriverBoundsOverride.sourceFor(serverWide, BindingOptions.ofDriverBounds(PROJECTILE));

        forBinding.current();
        forBinding.current();

        // Two reads inside one call could straddle a reload and derive a snapshot whose halves come
        // from different generations — the hazard the read-once rule exists for.
        assertEquals(2, serverWide.reads());
    }

    @Test
    void aReloadReachesAnOverridingBindingWithoutOverwritingItsOverride() {
        final BindingFixtures.MutableSimulationSource serverWide =
                new BindingFixtures.MutableSimulationSource(
                        BindingFixtures.simulationSettings(SERVER_WIDE));
        final SimulationSettingsSource forBinding =
                DriverBoundsOverride.sourceFor(serverWide, BindingOptions.ofDriverBounds(PROJECTILE));
        final SimulationSettings before = forBinding.current();

        // A reload: a fresh snapshot, a fresh solver, and an operator who moved the server-wide filter.
        final SimulationSettings reloaded =
                BindingFixtures.simulationSettings(new DriverBounds(1.25F, 4F));
        serverWide.publish(reloaded);
        final SimulationSettings after = forBinding.current();

        assertAll(
                () -> assertNotSame(before, after, "the memo must not outlive the snapshot it keyed on"),
                () -> assertSame(reloaded.solver(), after.solver(), "the new calibration must arrive"),
                // And the one thing the binding claimed for itself stays claimed. Moving the
                // server-wide number is exactly what a binding overrides it to be immune to.
                () -> assertSame(PROJECTILE, after.driverBounds()));
    }

    @Test
    void overridingWithTheConfigurationsOwnBoundsDerivesNothing() {
        final SimulationSettings live = BindingFixtures.simulationSettings(SERVER_WIDE);
        final SimulationSettingsSource forBinding = DriverBoundsOverride.sourceFor(
                SimulationSettingsSource.fixed(live), BindingOptions.ofDriverBounds(SERVER_WIDE));

        // A caller restating the value the server already uses asks for no substitution, so it gets the
        // live snapshot back rather than a copy of it.
        assertSame(live, forBinding.current());
    }

    @Test
    void theOptionsAreKeptForWhoeverAsks() {
        final BindingOptions options = BindingOptions.ofDriverBounds(PROJECTILE);

        final SimulationSettingsSource forBinding =
                DriverBoundsOverride.sourceFor(BindingFixtures.simulationSource(), options);

        assertSame(options, ((DriverBoundsOverride) forBinding).options());
    }
}
