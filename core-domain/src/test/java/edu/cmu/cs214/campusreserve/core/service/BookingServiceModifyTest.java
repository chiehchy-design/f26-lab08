package edu.cmu.cs214.campusreserve.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cmu.cs214.campusreserve.core.domain.Booking;
import edu.cmu.cs214.campusreserve.core.domain.BookingChange;
import edu.cmu.cs214.campusreserve.core.domain.ModificationResult;
import edu.cmu.cs214.campusreserve.core.domain.RejectionReason;
import edu.cmu.cs214.campusreserve.core.domain.TimeInterval;
import edu.cmu.cs214.campusreserve.core.domain.WaitlistEntry;
import edu.cmu.cs214.campusreserve.core.events.BookingModifiedEvent;
import edu.cmu.cs214.campusreserve.core.support.Fixtures;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BookingServiceModifyTest extends BookingServiceTestBase {

    private static final TimeInterval TEN_TO_ELEVEN = Fixtures.interval("10:00", "11:00");

    private ModificationResult modify(String id, String requester, TimeInterval interval, int attendees) {
        return service.modifyBooking(id, requester, interval, attendees);
    }

    private Booking stored(String id) {
        return service.findBooking(id).orElseThrow();
    }

    @Nested
    class Conflicts {

        @Test
        void aOneSlotNudgeIntoItsOwnTimeIsAllowed() {
            String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);
            TimeInterval nudged = Fixtures.interval("10:15", "11:15");

            ModificationResult result = modify(id, "u-1", nudged, 4);

            assertInstanceOf(ModificationResult.Modified.class, result);
            assertEquals(nudged, stored(id).interval());
        }

        @Test
        void aModificationIntoAnotherBookingsSlotIsRefusedAndChangesNothing() {
            String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);
            bookAndGetId("r-1", "u-2", Fixtures.interval("11:00", "12:00"));
            Booking before = stored(id);
            int eventsBefore = publisher.size();

            ModificationResult result = modify(id, "u-1", Fixtures.interval("10:30", "11:30"), 4);

            assertEquals(Optional.of(RejectionReason.ROOM_UNAVAILABLE), result.rejectionReason());
            assertEquals(before, stored(id));
            assertEquals(2, bookings.count());
            assertTrue(service.waitlistForRoom("r-1").isEmpty());
            assertEquals(eventsBefore, publisher.size());
        }

        @Test
        void aBookerAlreadyAtTheCapCanStillModify() {
            int cap = service.policy().maxActiveBookingsPerUser();
            String first = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);
            for (int i = 1; i < cap; i++) {
                bookAndGetId("r-2", "u-1", Fixtures.interval((11 + i) + ":00", (12 + i) + ":00"));
            }
            assertEquals(cap, service.activeBookingsForUser("u-1").size());

            ModificationResult result = modify(first, "u-1", Fixtures.interval("10:00", "11:30"), 4);

            assertInstanceOf(ModificationResult.Modified.class, result);
        }

        @Test
        void anOverlapWithTheBookersOtherRoomIsADoubleBooking() {
            String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);
            bookAndGetId("r-2", "u-1", Fixtures.interval("12:00", "13:00"));

            ModificationResult result = modify(id, "u-1", Fixtures.interval("11:30", "12:30"), 4);

            assertEquals(Optional.of(RejectionReason.USER_DOUBLE_BOOKED), result.rejectionReason());
            assertEquals(TEN_TO_ELEVEN, stored(id).interval());
        }

        @Test
        void endingWhereAnotherBookingStartsIsNotACollision() {
            String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);
            bookAndGetId("r-1", "u-2", Fixtures.interval("12:00", "13:00"));

            ModificationResult result = modify(id, "u-1", Fixtures.interval("10:00", "12:00"), 4);

            assertInstanceOf(ModificationResult.Modified.class, result);
        }
    }

    @Nested
    class OwnershipAndTiming {

        @Test
        void onlyTheBookerMayModify() {
            String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);
            ModificationResult result = modify(id, "u-2", Fixtures.interval("10:15", "11:15"), 4);
            assertEquals(Optional.of(RejectionReason.NOT_BOOKING_OWNER), result.rejectionReason());
        }

        @Test
        void aCancelledBookingCannotBeModified() {
            String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);
            service.cancelBooking(id, "u-1");
            ModificationResult result = modify(id, "u-1", Fixtures.interval("10:15", "11:15"), 4);
            assertEquals(Optional.of(RejectionReason.BOOKING_ALREADY_CANCELLED), result.rejectionReason());
        }

        @Test
        void aStartedBookingCannotBeModified() {
            String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);
            clock.setTo(Fixtures.DAY + "T10:00:00Z");
            ModificationResult result = modify(id, "u-1", Fixtures.interval("10:00", "11:30"), 4);
            assertEquals(Optional.of(RejectionReason.BOOKING_ALREADY_STARTED), result.rejectionReason());
        }

        @Test
        void anUnknownBookingIsNotFound() {
            ModificationResult result = modify("bk-missing", "u-1", TEN_TO_ELEVEN, 4);
            assertEquals(Optional.of(RejectionReason.BOOKING_NOT_FOUND), result.rejectionReason());
        }
    }

    @Nested
    class Rules {

        @Test
        void theNewAttendeeCountMustFitTheRoom() {
            String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);
            ModificationResult result = modify(id, "u-1", TEN_TO_ELEVEN, 11);
            assertEquals(Optional.of(RejectionReason.ATTENDEES_EXCEED_CAPACITY), result.rejectionReason());
            assertEquals(4, stored(id).attendees());
        }

        @Test
        void theNewIntervalMustBeAlignedAndInsideOpeningHours() {
            String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);
            assertEquals(Optional.of(RejectionReason.INTERVAL_NOT_ALIGNED),
                modify(id, "u-1", Fixtures.interval("10:10", "11:10"), 4).rejectionReason());
            assertEquals(Optional.of(RejectionReason.OUTSIDE_OPENING_HOURS),
                modify(id, "u-1", Fixtures.interval("05:00", "06:00"), 4).rejectionReason());
            assertEquals(Optional.of(RejectionReason.INTERVAL_TOO_LONG),
                modify(id, "u-1", Fixtures.interval("10:00", "14:00"), 4).rejectionReason());
        }

        @Test
        void theNewIntervalMustStartInTheFuture() {
            String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);
            clock.setTo(Fixtures.DAY + "T08:00:00Z");
            ModificationResult result = modify(id, "u-1", Fixtures.interval("07:00", "08:00"), 4);
            assertEquals(Optional.of(RejectionReason.INTERVAL_IN_PAST), result.rejectionReason());
        }

        @Test
        void aRoomOutOfServiceTakesNoModification() {
            String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);
            rooms.save(Fixtures.studyRoom("r-1", 10).withActive(false));
            ModificationResult result = modify(id, "u-1", Fixtures.interval("10:15", "11:15"), 4);
            assertEquals(Optional.of(RejectionReason.ROOM_INACTIVE), result.rejectionReason());
        }
    }

    @Nested
    class Waitlist {

        @Test
        void shorteningABookingPromotesTheWaiterOnTheFreedTime() {
            String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "12:00"));
            assertTrue(book("r-1", "u-2", Fixtures.interval("11:00", "12:00")).isWaitlisted());

            ModificationResult result = modify(id, "u-1", TEN_TO_ELEVEN, 4);

            assertEquals(1, result.promotions().size());
            assertEquals("u-2", result.promotions().get(0).booking().userId());
            assertTrue(service.waitlistForRoom("r-1").isEmpty());
            assertEquals(List.of("booking-created", "booking-modified", "waitlist-promoted"),
                publisher.types());
        }

        @Test
        void anAttendeeOnlyChangeRunsNoPromotionPass() {
            WaitlistEntry stale = staleEntryAndLaterBooking();
            String later = service.activeBookingsForUser("u-1").get(1).id();

            ModificationResult result = modify(later, "u-1", Fixtures.intervalOn("2026-10-21", "10:00", "11:00"), 6);

            assertInstanceOf(ModificationResult.Modified.class, result);
            assertTrue(result.promotions().isEmpty());
            assertEquals(List.of(stale), service.waitlistForRoom("r-1"));
        }

        @Test
        void lengtheningRunsThePassAndPromotesNobody() {
            staleEntryAndLaterBooking();
            String later = service.activeBookingsForUser("u-1").get(1).id();

            ModificationResult result = modify(later, "u-1", Fixtures.intervalOn("2026-10-21", "10:00", "12:00"), 4);

            assertInstanceOf(ModificationResult.Modified.class, result);
            assertTrue(result.promotions().isEmpty());
            // The pass ran: it dropped the entry whose slot has passed, as it does after a cancellation.
            assertTrue(service.waitlistForRoom("r-1").isEmpty());
        }

        /**
         * u-2 waits behind u-1's 10:00 booking, and the clock then moves past that
         * slot, so a promotion pass would drop the entry. u-1 also holds a booking
         * on the next day that can still be modified. Whether the entry survives
         * says whether a pass ran.
         */
        private WaitlistEntry staleEntryAndLaterBooking() {
            bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);
            bookAndGetId("r-1", "u-1", Fixtures.intervalOn("2026-10-21", "10:00", "11:00"));
            WaitlistEntry entry = book("r-1", "u-2", TEN_TO_ELEVEN).waitlistEntry().orElseThrow();
            clock.setTo(Fixtures.DAY + "T10:30:00Z");
            return entry;
        }
    }

    @Nested
    class HistoryAndEvents {

        @Test
        void aChangeIsRecordedWithWhatTheBookingUsedToBe() {
            String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);
            clock.advanceMinutes(5);
            TimeInterval moved = Fixtures.interval("14:00", "15:30");

            modify(id, "u-1", moved, 6);

            List<BookingChange> history = stored(id).history();
            assertEquals(1, history.size());
            BookingChange change = history.get(0);
            assertEquals(Instant.parse("2026-10-19T09:05:00Z"), change.changedAt());
            assertEquals(TEN_TO_ELEVEN, change.previousInterval());
            assertEquals(4, change.previousAttendees());
            assertEquals(moved, change.newInterval());
            assertEquals(6, change.newAttendees());
            assertEquals(Instant.parse(Fixtures.NOW), stored(id).createdAt());
        }

        @Test
        void historyKeepsEveryChangeOldestFirst() {
            String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);
            modify(id, "u-1", Fixtures.interval("10:15", "11:15"), 4);
            modify(id, "u-1", Fixtures.interval("10:15", "11:15"), 2);

            List<BookingChange> history = stored(id).history();
            assertEquals(2, history.size());
            assertEquals(TEN_TO_ELEVEN, history.get(0).previousInterval());
            assertEquals(4, history.get(1).previousAttendees());
            assertEquals(2, history.get(1).newAttendees());
        }

        @Test
        void aModificationThatChangesNothingRecordsNothing() {
            String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);
            int eventsBefore = publisher.size();

            ModificationResult result = modify(id, "u-1", TEN_TO_ELEVEN, 4);

            assertInstanceOf(ModificationResult.Unchanged.class, result);
            assertTrue(result.isAccepted());
            assertTrue(stored(id).history().isEmpty());
            assertEquals(eventsBefore, publisher.size());
        }

        @Test
        void theEventCarriesTheNewAndThePreviousDetails() {
            String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);
            TimeInterval moved = Fixtures.interval("13:00", "14:00");

            modify(id, "u-1", moved, 6);

            BookingModifiedEvent event = publisher.ofType(BookingModifiedEvent.class).get(0);
            assertEquals("booking-modified", event.type());
            assertEquals(id, event.bookingId());
            assertEquals(moved, event.interval());
            assertEquals(6, event.attendees());
            assertEquals(TEN_TO_ELEVEN, event.previousInterval());
            assertEquals(4, event.previousAttendees());
        }

        @Test
        void aFailingSubscriberDoesNotUndoTheModification() {
            String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);
            publisher.failWith(new IllegalStateException("outbox down"));

            ModificationResult result = modify(id, "u-1", Fixtures.interval("10:15", "11:15"), 4);

            assertInstanceOf(ModificationResult.Modified.class, result);
            assertFalse(stored(id).history().isEmpty());
        }

        @Test
        void cancellingAModifiedBookingKeepsItsHistory() {
            String id = bookAndGetId("r-1", "u-1", TEN_TO_ELEVEN);
            modify(id, "u-1", Fixtures.interval("10:15", "11:15"), 4);
            service.cancelBooking(id, "u-1");
            assertEquals(1, stored(id).history().size());
        }
    }
}
