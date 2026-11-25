package pandastic.studio.models.internal.vehicle;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A model's locators, declared by the test.
 *
 * <h2>Why all three outcomes are expressible</h2>
 * Because all three happen and two of them are the interesting ones. A name can be a locator, which is
 * the only usable answer; it can be an ordinary <em>bone</em>, which is the rigger having pointed at
 * geometry and the mistake the whole locator policy exists to catch; or it can be nothing at all, which
 * is the normal state of every model in this repository today because none of them contains a locator
 * yet. A double that could only express the first would make the layer look finished while the two cases
 * an operator will actually hit went untested.
 *
 * <p>Positions are offsets from the model's origin, exactly as the real seam answers them, and are
 * movable between steps: an animated locator is the reason a carrier has to be followed even when the
 * vehicle has not moved.
 */
final class FakeSeatLocators implements SeatLocators {

    private final @NotNull Map<String, SeatLocators.Kind> kinds = new HashMap<>();
    private final @NotNull Map<String, double[]> offsets = new HashMap<>();

    private boolean modelGone;
    private int resolutions;

    /** Declares {@code name} a locator at an offset from the model's origin. */
    FakeSeatLocators locator(@NotNull String name, double x, double y, double z) {
        final String key = key(name);
        this.kinds.put(key, SeatLocators.Kind.LOCATOR);
        this.offsets.put(key, new double[]{x, y, z});
        return this;
    }

    /** Declares {@code name} an ordinary bone, which is a seat nobody may sit on. */
    FakeSeatLocators bone(@NotNull String name) {
        this.kinds.put(key(name), SeatLocators.Kind.BONE);
        return this;
    }

    /** Moves a locator, as an animation does between two steps. */
    void moveLocator(@NotNull String name, double x, double y, double z) {
        this.offsets.put(key(name), new double[]{x, y, z});
    }

    /** Makes every already-resolved anchor report the model as gone, as closing a tracker does. */
    void modelGone() {
        this.modelGone = true;
    }

    /** How many names were resolved, which is asserted to be once per declared seat and no more. */
    int resolutions() {
        return this.resolutions;
    }

    @Override
    public @NotNull Resolution resolve(@NotNull String locatorName) {
        this.resolutions++;
        final String key = key(locatorName);
        final SeatLocators.Kind kind = this.kinds.get(key);
        if (kind == null) {
            return Resolution.UNRESOLVED;
        }
        if (kind == SeatLocators.Kind.BONE) {
            return Resolution.BONE;
        }
        return Resolution.locator(sample -> {
            if (this.modelGone) {
                return false;
            }
            final double[] offset = this.offsets.get(key);
            sample.set(offset[0], offset[1], offset[2]);
            return true;
        });
    }

    private static @NotNull String key(@NotNull String name) {
        // The real seam matches on the bare name case-insensitively, and a rig has already lower-cased
        // its seat's locator name. Doing the same here keeps a test from passing on a comparison the
        // renderer would not make.
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
