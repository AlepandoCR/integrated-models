package pandastic.studio.models.internal.ai;

import org.bukkit.entity.Mob;
import pandastic.studio.models.api.ai.AiContext;
import pandastic.studio.models.internal.nms.BrainInstallRequest;
import pandastic.studio.models.internal.nms.BrainInstallation;
import pandastic.studio.models.internal.nms.BrainBridge;
import pandastic.studio.models.internal.nms.MemoryStore;

import java.util.ArrayList;
import java.util.List;

/**
 * A brain bridge that records what it was asked to install and hands back a map.
 *
 * <h2>What this double is and is not evidence of</h2>
 * It proves the layer above the seam calls the seam once, on the right thread, with the right plan, and
 * tears it down exactly once. It proves nothing about the rebuild itself — there is no brain here, no
 * registry, no memory slot keys — because none of that can exist without a bootstrapped server. The
 * rebuild is in the list of things only a live server can confirm, and it is listed there rather than
 * faked here, because a fake brain that agreed with our idea of the real one would be evidence about our
 * idea.
 *
 * <h2>It calls the context factory, which is the one interesting thing it does</h2>
 * The real adapter builds the context from inside the brain's construction, with the store it has just
 * built. Doing the same here is what exercises the callback the seam exists for, and it is how a test
 * gets hold of the context a behaviour body would have been handed.
 */
final class FakeBrainBridge implements BrainBridge {

    /** Every request this bridge was handed, in order. */
    final List<BrainInstallRequest> requests = new ArrayList<>();

    /** Every installation it produced, in order, so a test can check which ones were uninstalled. */
    final List<FakeInstallation> installations = new ArrayList<>();

    /** The store every installation shares, which is also the one a test asserts against. */
    final FakeMemoryStore store = new FakeMemoryStore();

    /** The context the last install built from the request's factory. */
    AiContext lastContext;

    boolean supports = true;

    /** When set, {@link #install} throws it instead of installing. */
    RuntimeException installFailure;

    /** An installation that remembers whether it was uninstalled, and how often. */
    static final class FakeInstallation implements BrainInstallation {

        private final MemoryStore store;
        private boolean installed = true;
        int uninstalls;

        FakeInstallation(MemoryStore store) {
            this.store = store;
        }

        @Override
        public MemoryStore memories() {
            return this.store;
        }

        @Override
        public boolean isInstalled() {
            return this.installed;
        }

        @Override
        public void uninstall() {
            this.uninstalls++;
            this.installed = false;
        }

        @Override
        public String describe() {
            return "FakeInstallation";
        }
    }

    @Override
    public boolean supports(Mob mob) {
        return this.supports;
    }

    @Override
    public List<String> supportedSpecies() {
        return List.of("Villager", "Warden");
    }

    @Override
    public BrainInstallation install(BrainInstallRequest request) {
        if (this.installFailure != null) {
            throw this.installFailure;
        }
        this.requests.add(request);
        // The same callback the real adapter makes, from the same place in the sequence.
        this.lastContext = request.contexts().create(this.store);
        final FakeInstallation installation = new FakeInstallation(this.store);
        this.installations.add(installation);
        return installation;
    }
}
