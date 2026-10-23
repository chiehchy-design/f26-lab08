package edu.cmu.cs214.campusreserve.notifications.templates;

import edu.cmu.cs214.campusreserve.notifications.NotificationKind;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The templates a deployment sends with, one per kind of message.
 *
 * <p>Registering a second template for a kind replaces the first, which is how
 * a deployment swaps in its own wording without touching the shipped classes.
 */
public final class TemplateRegistry {

    private final Map<NotificationKind, MessageTemplate> byKind = new EnumMap<>(NotificationKind.class);

    private TemplateRegistry() {
    }

    /** A registry with nothing in it. */
    public static TemplateRegistry empty() {
        return new TemplateRegistry();
    }

    /** A registry holding the shipped wording for every kind. */
    public static TemplateRegistry standard() {
        return empty()
            .register(new BookingConfirmedTemplate())
            .register(new BookingCancelledTemplate())
            .register(new BookingModifiedTemplate())
            .register(new WaitlistPromotedTemplate());
    }

    /** Adds a template, replacing any template already held for its kind. */
    public TemplateRegistry register(MessageTemplate template) {
        Objects.requireNonNull(template, "template");
        byKind.put(template.kind(), template);
        return this;
    }

    /** The template for a kind, or empty if none is registered. */
    public Optional<MessageTemplate> find(NotificationKind kind) {
        return Optional.ofNullable(byKind.get(kind));
    }

    /**
     * The template for a kind.
     *
     * @throws TemplateException naming the kind and listing what is registered,
     *     because a missing template means messages of that kind quietly stop
     *     going out
     */
    public MessageTemplate require(NotificationKind kind) {
        Objects.requireNonNull(kind, "kind");
        MessageTemplate template = byKind.get(kind);
        if (template == null) {
            throw new TemplateException(
                "No template registered for kind " + kind.code() + ". Registered kinds: " + codes());
        }
        return template;
    }

    /** The kinds this registry can write. */
    public Set<NotificationKind> kinds() {
        return byKind.isEmpty()
            ? Collections.emptySet()
            : Collections.unmodifiableSet(EnumSet.copyOf(byKind.keySet()));
    }

    /** Is there a template for every kind of message? */
    public boolean isComplete() {
        return byKind.keySet().containsAll(EnumSet.allOf(NotificationKind.class));
    }

    private String codes() {
        StringBuilder out = new StringBuilder("[");
        for (NotificationKind kind : byKind.keySet()) {
            if (out.length() > 1) {
                out.append(", ");
            }
            out.append(kind.code());
        }
        return out.append(']').toString();
    }
}
