package pandastic.studio.models.internal.nms;

import org.bukkit.entity.Mob;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A {@link BrainBridge} that supports nothing and installs nothing.
 *
 * <h2>Why a named class rather than an anonymous one</h2>
 * Because two different suites need a brain bridge for reasons that have nothing to do with brains:
 * {@link NmsBridgeLookupTest} needs an {@link NmsBridge} to hand back, and
 * {@link ServiceLoadedTestProvider} needs one to build. Neither wants to grow a method every time the
 * real seam does, and an anonymous implementation in each would mean two edits per seam change rather
 * than one.
 *
 * <h2>Why install throws instead of returning something</h2>
 * A test that reaches {@link #install} through this class has strayed out of its own subject — the
 * lookup does not install anything and never should. Returning a stub installation would let such a
 * test pass while proving nothing; throwing names the mistake.
 *
 * <p>The ai layer's own tests do not use this. They need a bridge that behaves, and they have one with
 * a brain-backed store standing in for the server's; see
 * {@code pandastic.studio.models.internal.ai.FakeBrainBridge}.
 */
final class StubBrainBridge implements BrainBridge {

    @Override
    public boolean supports(@NotNull Mob mob) {
        return false;
    }

    @Override
    public @NotNull List<String> supportedSpecies() {
        return List.of();
    }

    @Override
    public @NotNull BrainInstallation install(@NotNull BrainInstallRequest request) {
        throw new AssertionError("A lookup test must not install a plan: "
                + request.plan().getName() + " reached a stub bridge. Use the ai package's own double"
                + " if the subject is installation rather than adapter selection");
    }
}
