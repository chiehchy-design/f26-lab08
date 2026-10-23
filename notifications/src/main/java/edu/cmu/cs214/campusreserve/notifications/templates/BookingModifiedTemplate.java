package edu.cmu.cs214.campusreserve.notifications.templates;

import edu.cmu.cs214.campusreserve.notifications.NotificationKind;

/**
 * The message a person gets when they change a booking of theirs. It names the
 * booking both as it was and as it is now.
 *
 * <p>Wants {@link Placeholders#NAME}, {@link Placeholders#ROOM_ID},
 * {@link Placeholders#PREVIOUS_WHEN}, {@link Placeholders#PREVIOUS_ATTENDEES},
 * {@link Placeholders#WHEN}, {@link Placeholders#ATTENDEES}, and
 * {@link Placeholders#BOOKING_ID}.
 */
public final class BookingModifiedTemplate extends TextMessageTemplate {

    private static final String SUBJECT = "Booking for room {roomId} is now {when}";

    private static final String BODY = """
        Hi {name},

        Your booking of room {roomId} has changed.
        It was: {previousWhen}, for {previousAttendees} people.
        It is now: {when}, for {attendees} people.
        Your booking number is still {bookingId}.
        """;

    /** Builds the template. */
    public BookingModifiedTemplate() {
        super(NotificationKind.BOOKING_MODIFIED, SUBJECT, BODY);
    }
}
