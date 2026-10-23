package edu.cmu.cs214.campusreserve.core.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One modification of a booking: what it was, what it became, and when.
 *
 * <p>A booking keeps these oldest first, so its history reads as the sequence of
 * changes that turned the original request into the booking as it stands.
 */
public record BookingChange(
    Instant changedAt,
    TimeInterval previousInterval,
    int previousAttendees,
    TimeInterval newInterval,
    int newAttendees) {

    public BookingChange {
        Objects.requireNonNull(changedAt, "changedAt");
        Objects.requireNonNull(previousInterval, "previousInterval");
        Objects.requireNonNull(newInterval, "newInterval");
        if (previousAttendees <= 0 || newAttendees <= 0) {
            throw new IllegalArgumentException(
                "Attendees must be positive: " + previousAttendees + " to " + newAttendees);
        }
    }

    /** Did this change move or resize the booked time? */
    public boolean changedInterval() {
        return !previousInterval.equals(newInterval);
    }

    /** Did this change alter the attendee count? */
    public boolean changedAttendees() {
        return previousAttendees != newAttendees;
    }
}
