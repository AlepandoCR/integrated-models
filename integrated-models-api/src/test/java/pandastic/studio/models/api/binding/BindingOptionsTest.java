package pandastic.studio.models.api.binding;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.physics.DriverBounds;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-binding options, and in particular the two states they may be in.
 *
 * <p>Every value here deliberately disagrees with the shipped defaults, and in both directions: the
 * server-wide stand-in is <em>tighter</em> than the shipped 1.5/6 and the override is <em>looser</em>.
 * A suite built from the defaults could not tell "reads what it was given" from "ignores it and happens
 * to agree".
 */
class BindingOptionsTest {

    /** Tighter than anything shipped, so a value that leaked from configuration is visible. */
    private static final DriverBounds SERVER_WIDE = new DriverBounds(0.37F, 2.9F);

    /** Looser than anything shipped, and roughly a projectile: an arrow at full draw is about 3. */
    private static final DriverBounds PROJECTILE = new DriverBounds(4.25F, 7.5F);

    // ------------------------------------------------------------------ absent

    @Test
    void defaultsStateNothingAndAreShared() {
        assertAll(
                () -> assertTrue(BindingOptions.defaults().driverBounds().isEmpty()),
                // Nothing worth copying, and the overwhelming majority of bindings ask for it.
                () -> assertSame(BindingOptions.defaults(), BindingOptions.defaults()));
    }

    @Test
    void nothingStatedResolvesToTheServersOwnBoundsByIdentity() {
        final DriverBounds resolved = BindingOptions.defaults().resolveDriverBounds(SERVER_WIDE);

        // By identity, not by value: that is what lets a caller see no substitution is needed and keep
        // the sampling path free of a derived snapshot per sample.
        assertSame(SERVER_WIDE, resolved);
    }

    @Test
    void theFallbackIsNotOptional() {
        // The other half of "a binding cannot end up unfiltered". Absent means the server's bounds, so
        // there has to *be* a server-wide value; a null here would be the bound-less driver the whole
        // filter exists to rule out.
        assertThrows(NullPointerException.class,
                () -> BindingOptions.defaults().resolveDriverBounds(null));
    }

    // ----------------------------------------------------------------- present

    @Test
    void statedBoundsAreWhatTheBindingResolvesTo() {
        final BindingOptions options = BindingOptions.ofDriverBounds(PROJECTILE);

        assertAll(
                () -> assertEquals(PROJECTILE, options.driverBounds().orElseThrow()),
                // The server's value is not consulted at all, in either direction.
                () -> assertSame(PROJECTILE, options.resolveDriverBounds(SERVER_WIDE)));
    }

    @Test
    void theWithersCopyRatherThanMutate() {
        final BindingOptions base = BindingOptions.defaults();

        final BindingOptions overriding = base.withDriverBounds(PROJECTILE);

        assertAll(
                () -> assertTrue(base.driverBounds().isEmpty(), "the original must be untouched"),
                () -> assertEquals(PROJECTILE, overriding.driverBounds().orElseThrow()),
                () -> assertNotEquals(base, overriding));
    }

    @Test
    void anOverrideCanBeTakenBackOffAgain() {
        final BindingOptions cleared =
                BindingOptions.ofDriverBounds(PROJECTILE).withDriverBounds(null);

        assertAll(
                () -> assertTrue(cleared.driverBounds().isEmpty()),
                () -> assertSame(SERVER_WIDE, cleared.resolveDriverBounds(SERVER_WIDE)),
                () -> assertEquals(BindingOptions.defaults(), cleared));
    }

    @Test
    void theTwoCeilingsAreCarriedIndependently() {
        // Horizontally loose, vertically tight — a combination neither the defaults nor a single scalar
        // could express, and the shape a projectile that must not react to gravity wants.
        final BindingOptions options =
                BindingOptions.ofDriverBounds(new DriverBounds(4.25F, 0.09F));

        final DriverBounds resolved = options.resolveDriverBounds(SERVER_WIDE);
        assertAll(
                () -> assertEquals(4.25F, resolved.maxStepBlocks()),
                () -> assertEquals(0.09F, resolved.maxVerticalStepBlocks()));
    }

    // -------------------------------------------------------------- validation

    @Test
    void theProtocolLimitIsTheRelativeMovePacketsOwnCeiling() {
        // A short in 4096ths of a block. Pinned because the whole argument for refusing anything past
        // it is that it is the protocol's number rather than a chosen one.
        assertEquals(Short.MAX_VALUE / 4096F, BindingOptions.RELATIVE_MOVE_LIMIT_BLOCKS);
    }

    @Test
    void aCeilingAtTheProtocolLimitIsAccepted() {
        final DriverBounds atTheLine = new DriverBounds(
                BindingOptions.RELATIVE_MOVE_LIMIT_BLOCKS,
                BindingOptions.RELATIVE_MOVE_LIMIT_BLOCKS);

        // Coherent rather than absurd: it says "a teleport is whatever the client also snaps", which is
        // the furthest a filter can go and still be one.
        assertEquals(atTheLine, BindingOptions.ofDriverBounds(atTheLine).driverBounds().orElseThrow());
    }

    @Test
    void aHorizontalCeilingPastTheProtocolLimitIsRefused() {
        final DriverBounds past = new DriverBounds(
                Math.nextUp(BindingOptions.RELATIVE_MOVE_LIMIT_BLOCKS), 2.9F);

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> BindingOptions.ofDriverBounds(past));

        assertAll(
                () -> assertTrue(thrown.getMessage().contains("maxStepBlocks"),
                        () -> "must name the ceiling, got: " + thrown.getMessage()),
                // The message has to explain itself: a caller who picked a big number to "make the
                // physics work" needs to be told what the number actually does.
                () -> assertTrue(thrown.getMessage().contains("relative move packet"),
                        () -> "must say why, got: " + thrown.getMessage()));
    }

    @Test
    void aVerticalCeilingPastTheProtocolLimitIsRefusedIndependently() {
        // Horizontally fine, vertically absurd. The two are checked separately or one of them is not
        // checked at all.
        final DriverBounds past = new DriverBounds(4.25F, 50F);

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> BindingOptions.defaults().withDriverBounds(past));

        assertTrue(thrown.getMessage().contains("maxVerticalStepBlocks"),
                () -> "must name the ceiling that failed, got: " + thrown.getMessage());
    }

    @Test
    void aCeilingFarTighterThanTheServersIsNotRefused() {
        // Only the upper end is a bug. Asking for a model that barely reacts to its owner's movement is
        // a real thing to want — a decoration that should stay still — and a tiny ceiling is how you
        // ask for it.
        final DriverBounds motionless = new DriverBounds(1.0E-4F, 1.0E-4F);

        assertEquals(motionless,
                BindingOptions.ofDriverBounds(motionless).resolveDriverBounds(SERVER_WIDE));
    }

    // ------------------------------------------------------------------ equality

    @Test
    void equalityIsByWhatWasStated() {
        assertAll(
                () -> assertEquals(BindingOptions.ofDriverBounds(PROJECTILE),
                        BindingOptions.ofDriverBounds(new DriverBounds(4.25F, 7.5F))),
                () -> assertEquals(BindingOptions.ofDriverBounds(PROJECTILE).hashCode(),
                        BindingOptions.ofDriverBounds(new DriverBounds(4.25F, 7.5F)).hashCode()),
                () -> assertNotEquals(BindingOptions.ofDriverBounds(PROJECTILE),
                        BindingOptions.ofDriverBounds(SERVER_WIDE)));
    }

    @Test
    void toStringSaysWhichOfTheTwoStatesItIsIn() {
        assertAll(
                () -> assertTrue(BindingOptions.defaults().toString().contains("server-wide"),
                        () -> "absent must not read as a value: " + BindingOptions.defaults()),
                () -> assertTrue(BindingOptions.ofDriverBounds(PROJECTILE).toString().contains("4.25")));
    }
}
