package pandastic.studio.models.internal.command;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Whoever typed the command, as a list of the lines they were sent.
 *
 * <h2>Why an {@link Audience} and not a sender</h2>
 * Because that is the whole of what the command needs from the person who typed it, and the only part
 * worth asserting. {@code Audience} has no abstract method — every one of its {@code sendMessage}
 * overloads funnels into the single-component form — so overriding that one form captures every line
 * however it was sent, with no server, no connection and no proxy in the way.
 *
 * <p>It is also what makes the reporting half of this command testable at all: a {@code CommandSender}
 * cannot be constructed off a server, and standing one up would make a test of "which line was sent"
 * depend on the thing the reply path deliberately does not care about.
 *
 * <h2>Thread-safe on purpose</h2>
 * The command replies from the thread the reload ran on, which is not the thread that invoked it, so a
 * test that read an {@code ArrayList} written by another thread would be asserting against a value it
 * has no happens-before edge to. The copy-on-write list gives every reader the full picture.
 */
final class RecordingAudience implements Audience {

    private final @NotNull List<Component> received = new CopyOnWriteArrayList<>();

    @Override
    public void sendMessage(@NotNull Component message) {
        this.received.add(message);
    }

    /** Every line sent so far, flattened to plain text in the order it arrived. */
    @NotNull List<String> lines() {
        return this.received.stream()
                .map(component -> PlainTextComponentSerializer.plainText().serialize(component))
                .toList();
    }

    /** Every line as one string, for an assertion that does not care how the reply was split up. */
    @NotNull String joined() {
        return String.join("\n", lines());
    }

    /** How many lines were sent. */
    int count() {
        return this.received.size();
    }
}
