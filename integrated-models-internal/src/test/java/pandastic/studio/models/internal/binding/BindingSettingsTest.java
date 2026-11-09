package pandastic.studio.models.internal.binding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BindingSettingsTest {

    @Test
    void acceptsEveryPeriodOfAtLeastOneTick() {
        assertAll(
                () -> assertEquals(1L, new BindingSettings(1L).samplePeriodTicks()),
                () -> assertEquals(20L, new BindingSettings(20L).samplePeriodTicks()));
    }

    @Test
    void refusesAPeriodBelowOneTick() {
        // The platform silently promotes anything below a tick to a tick, so accepting zero would mean
        // the configured value and the scheduled value were different numbers with nothing saying so.
        final IllegalArgumentException zero =
                assertThrows(IllegalArgumentException.class, () -> new BindingSettings(0L));

        assertAll(
                () -> assertTrue(zero.getMessage().contains("at least 1"),
                        () -> "must name the bound, got: " + zero.getMessage()),
                () -> assertThrows(IllegalArgumentException.class, () -> new BindingSettings(-1L)));
    }

    @Test
    void aFixedSourceAlwaysAnswersTheSameSnapshot() {
        final BindingSettings settings = new BindingSettings(3L);
        final BindingSettingsSource source = BindingSettingsSource.fixed(settings);

        assertAll(
                () -> assertSame(settings, source.current()),
                () -> assertSame(settings, source.current()));
    }
}
