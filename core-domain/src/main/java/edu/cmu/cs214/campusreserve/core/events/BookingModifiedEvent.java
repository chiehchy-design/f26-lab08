package edu.cmu.cs214.campusreserve.core.events;

import edu.cmu.cs214.campusreserve.core.domain.Booking;
import edu.cmu.cs214.campusreserve.core.domain.TimeInterval;
import java.time.Instant;
import java.util.Objects;

/**
 * A booking changed its interval, its attendee count, or both.
 *
 * <p>The core fields describe the booking as it is now, so a reader that only
 * looks at those sees its current shape. The previous interval and attendee
 * count ride along for readers that want to know what changed.
 */
public record BookingModifiedEvent(
    Instant occurredAt,
    String bookingId,
    String roomId,
    String userId,
    TimeInterval interval,
    int attendees,
    TimeInterval previousInterval,
    int previousAttendees) implements DomainEvent {

    /** The wire name of this event. */
    public static final String TYPE = "booking-modified";

    public BookingModifiedEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(previousInterval, "previousInterval");
    }

    /** Builds the event from the booking before and after the change. */
    public static BookingModifiedEvent of(Booking before, Booking after, Instant occurredAt) {
        return new BookingModifiedEvent(
            occurredAt,
            after.id(),
            after.roomId(),
            after.userId(),
            after.interval(),
            after.attendees(),
            before.interval(),
            before.attendees());
    }

    @Override
    public String type() {
        return TYPE;
    }
}
