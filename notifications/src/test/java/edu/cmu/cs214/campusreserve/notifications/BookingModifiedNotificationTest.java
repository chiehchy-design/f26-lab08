package edu.cmu.cs214.campusreserve.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cmu.cs214.campusreserve.core.domain.Booking;
import edu.cmu.cs214.campusreserve.core.domain.TimeInterval;
import edu.cmu.cs214.campusreserve.core.events.BookingModifiedEvent;
import edu.cmu.cs214.campusreserve.notifications.support.Fixtures;
import edu.cmu.cs214.campusreserve.notifications.support.TestClock;
import edu.cmu.cs214.campusreserve.notifications.templates.TemplateRegistry;
import org.junit.jupiter.api.Test;

class BookingModifiedNotificationTest {

    private final InMemoryOutbox outbox = new InMemoryOutbox();
    private final NotificationService service =
        new NotificationService(Fixtures.directory(), outbox, TestClock.at("2026-10-19T09:00:00Z"));

    private static BookingModifiedEvent modified() {
        Booking before = Fixtures.booking();
        Booking after = before.modified(
            TimeInterval.parse("2026-10-20T14:00:00Z", "2026-10-20T15:30:00Z"), 6, Fixtures.NOW);
        return BookingModifiedEvent.of(before, after, Fixtures.NOW);
    }

    @Test
    void aModifiedBookingTellsTheOwnerBothTheOldAndTheNewDetails() {
        service.publish(modified());

        Notification notification = outbox.peek().orElseThrow();
        assertEquals(NotificationKind.BOOKING_MODIFIED, notification.kind());
        assertEquals(Fixtures.USER_ID, notification.recipientUserId());
        assertEquals("Booking for room r-401 is now 2026-10-20 14:00 to 15:30 UTC", notification.subject());
        String body = notification.body();
        assertTrue(body.contains("It was: 2026-10-20 10:00 to 11:00 UTC, for 4 people."), body);
        assertTrue(body.contains("It is now: 2026-10-20 14:00 to 15:30 UTC, for 6 people."), body);
        assertTrue(body.contains(Fixtures.BOOKING_ID), body);
        assertEquals(0, service.skippedCount());
    }

    @Test
    void theEventAsksForTheModifiedKindOfMessage() {
        assertEquals(NotificationKind.BOOKING_MODIFIED, NotificationKind.forEvent(modified()));
        assertEquals("booking-modified", NotificationKind.BOOKING_MODIFIED.code());
    }

    @Test
    void theStandardRegistryCoversTheModifiedKind() {
        TemplateRegistry registry = TemplateRegistry.standard();
        assertTrue(registry.find(NotificationKind.BOOKING_MODIFIED).isPresent());
        assertTrue(registry.isComplete());
    }
}
