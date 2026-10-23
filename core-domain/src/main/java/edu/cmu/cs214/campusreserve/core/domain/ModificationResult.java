package edu.cmu.cs214.campusreserve.core.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What came of a modification: the booking changed, the request changed
 * nothing, or the modification was refused.
 */
public sealed interface ModificationResult {

    /**
     * The booking now holds the new interval and attendee count. {@code promotions}
     * lists the waitlist entries that moved into time the change freed, and may be
     * empty.
     */
    record Modified(Booking before, Booking after, List<CancellationResult.Promotion> promotions)
        implements ModificationResult {
        public Modified {
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
            promotions = List.copyOf(promotions);
        }
    }

    /** The request asked for what the booking already is. Nothing was recorded. */
    record Unchanged(Booking booking) implements ModificationResult {
        public Unchanged {
            Objects.requireNonNull(booking, "booking");
        }
    }

    /** The modification was refused. The original booking stands unchanged. */
    record Rejected(RejectionReason reason, String detail) implements ModificationResult {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
            detail = detail == null ? reason.message() : detail;
        }
    }

    /** Builds a rejection carrying the reason's own message. */
    static ModificationResult rejected(RejectionReason reason) {
        return new Rejected(reason, reason.message());
    }

    /** Did the modification succeed, whether or not it changed anything? */
    default boolean isAccepted() {
        return !(this instanceof Rejected);
    }

    /** The booking as it stands after the call, when the modification succeeded. */
    default Optional<Booking> currentBooking() {
        return switch (this) {
            case Modified modified -> Optional.of(modified.after());
            case Unchanged unchanged -> Optional.of(unchanged.booking());
            case Rejected ignored -> Optional.empty();
        };
    }

    /** Promotions triggered by the modification, empty unless it moved time. */
    default List<CancellationResult.Promotion> promotions() {
        return this instanceof Modified modified ? modified.promotions() : List.of();
    }

    /** The reason, when the modification was refused. */
    default Optional<RejectionReason> rejectionReason() {
        return this instanceof Rejected rejected ? Optional.of(rejected.reason()) : Optional.empty();
    }
}
