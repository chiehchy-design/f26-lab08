# PLAN.md

Baseline before planning: `mvn -B test` BUILD SUCCESS (418), `pytest` 137 passed.

---

## Milestone 1: The plan

Written before any task was run.

**The request.** `CHANGE-REQUEST.md` asks for one feature: let people modify a booking they already hold, by changing the time or the number of attendees. Today they have to cancel and rebook. The feature reaches into most of the codebase:

| What the feature needs | Where it lives |
|---|---|
| The rules: who can modify, conflict checks, waitlist | `core-domain` |
| An email to the owner listing old and new details | `notifications` |
| A `booking-modified` line in the event log | `storage` |
| The utilization report counting the new length | `analytics` (Python) |
| A `PATCH /bookings/{id}` endpoint | `http-api` |

**The tasks at a glance**
- **T1, the core logic** (`core-domain` + `notifications`). Adds `modifyBooking`:
  - only the owner can modify, and only before the booking starts
  - every booking rule is re-checked against the new time and attendee count
  - a booking doesn't collide with itself
  - waitlisted people get any freed time
  - the owner gets an email
- Notifications is bundled into T1 because the two modules are tied at compile time. The event types form a "sealed" list, and notifications has code that must handle every type on that list. Adding a new event without updating notifications stops that module compiling.
- **T2, the log line** (`storage`). Writes `booking-modified` to the event log.
- **T3, the report** (`analytics`). The Python report updates a modified booking's length instead of counting it twice.
- **T4, the HTTP endpoint** (`http-api`). Adds `PATCH`, and `GET` shows the change history.

**Decisions the change request leaves to us**
- **History:** each booking keeps its own list of changes, each recording when it happened and the old and new values. `GET /bookings/{id}` shows it, so no new endpoint is needed.
- **Errors:** no new error codes. A refused modification reuses the existing ones: not the owner, already cancelled or started, a broken booking rule, double-booked, or room taken. A refused modification never puts anyone on the waitlist.
- **Event:** `booking-modified` carries the new time and attendee count, plus the previous ones.

**T1. Modify a booking in the domain, with history and the owner's notification.** Modules: `core-domain`, `notifications`. Depends on: none.
- `BookingService.modifyBooking` covers CR 1–6:
  - owner only, and only an upcoming booking
  - every `BookingRules` check on the new shape
  - room and own-calendar checks exclude the booking itself, and the cap is waived
  - a rejection writes nothing
  - an interval change runs the existing promotion pass
  - a no-op records nothing
- The owner gets a notification naming the old and new details.
- Notifications is in this task because `DomainEvent` is sealed and notifications switches over it exhaustively. A new event alone breaks that compile.
- **Checkpoint:** `mvn -B -pl notifications -am test`. I want core-domain Tests run > 167 and notifications > 41, 0 failures. The new tests must include:
  - a 15-minute self-nudge is allowed
  - a collision leaves the store, waitlist, and events unchanged
  - a booker at the cap can still modify
  - own-calendar overlap → `USER_DOUBLE_BOOKED`
  - shortening promotes the waiter; an attendee-only change promotes nobody
  - a no-op records nothing

  The diff must touch only these two modules.
- **Green but wrong:** reusing `checkAskerLimits`. That brings back the cap and doesn't exclude the booking itself, and it passes any test whose user holds fewer than 5 bookings.

**T2. Write the `booking-modified` log line.** Module: `storage`. Depends on: T1.
- `EventLogFormat` writes the new line, and `eventTypes()` lists it.
- I update the event-type lists in `AGENTS.md` and `ARCHITECTURE.md` myself (CR 9).
- **Checkpoint:** `mvn -B -pl storage -am test`, with storage > 63 and a test asserting the exact line (new interval in `start`/`end`, old in `previous*`).
- **Green but wrong:** `toLine` uses `instanceof`, so a forgotten branch still compiles and writes only the core fields.

**T3. Fold `booking-modified` into the analytics ledger.** Module: `analytics`. Depends on: T2.
- `build_ledger` updates the existing record in place, so utilization counts the current length. `revenue.py` is untouched.
- **Checkpoint:** `pytest` > 137 passed, with these tests:
  - 10:00–12:00 modified to 10:00–11:00 reports 60 minutes and 1 booking
  - a promoted-then-modified booking keeps `promoted_count == 1`
- **Green but wrong:** treating the event as a new holding record. Minutes come out right, but `from_waitlist` resets.

**T4. `PATCH /bookings/{id}` and history in the booking view.** Module: `http-api`. Depends on: T1.
- Owner-only `PATCH`, `history` in `JsonViews.booking`, and the HTTP table row in `ARCHITECTURE.md`.
- **Checkpoint:** `mvn -B -pl http-api -am test`, with http-api > 78. Real-socket tests must show:
  - owner → 200
  - other member → 403
  - **admin → 403**
  - taken slot → 409, with the original intact
  - GET shows the history
- **Green but wrong:** copying `show`'s owner-or-admin check, which lets an admin modify anyone's booking.

**Rules 3 and 4:**
- Rule 3: `TimeInterval.overlaps` is the only code that decides whether two time ranges collide. No second overlap check anywhere.
- Rule 4: only `BookingService` may add or remove waitlist entries.

T1's checkpoint catches both, by diff grep:
- `git diff -U0 -- '*.java' | grep -nE '(start|end)\(\)\.(isBefore|isAfter|compareTo)'` must print nothing outside `TimeInterval`.
- `grep -rnE 'waitlist\.(save|remove)' --include='*.java' */src/main` must list only `BookingService`.

**Out of scope**

| Not touched | Reason |
|---|---|
| `billing/` | CR: "The price does not change … Billing is not part of this change request." |
| `cli/` | CR: "Nothing in the CLI." |
| `accounts/` | CR: "Nothing in account management." |
| `analytics/.../revenue.py` | CR: "The revenue report is not part of this change … both reports go on reading the same fold." |
| every `pom.xml` | AGENTS.md rule 1: no new dependency edges. |
| shipped tests | AGENTS.md rule 7: never edit a shipped test. |

---

## Milestone 2: The guardrail

**Recipe C (filesystem lockdown).** `protected-trees.txt` = `billing`, `cli`, `accounts`. That locks each module's `src/` and `pom.xml` read-only. The OS enforces it for the Edit tool and for Bash alike. `revenue.py` and the in-scope poms aren't lockable this way, so the M3 diff audit covers them.

**What it cannot catch:** a bad edit inside the unlocked modules, or an agent running `chmod -R u+w` first.

**Captured block** (Claude Code, Opus 5.5, after `lockdown.sh` printed `3 tree(s) are now read-only`):

> **Asked:** use the Edit tool to add a comment at the top of `billing/.../billing/Money.java`.
>
> **Tool call (Edit):** `file_path: .../billing/src/main/java/edu/cmu/cs214/campusreserve/billing/Money.java`, inserting `// guardrail probe: this edit should be refused`
>
> **Returned:**
> ```
> <error>EACCES: permission denied, open '.../billing/src/main/java/edu/cmu/cs214/campusreserve/billing/Money.java.tmp.42843.c8d739458adf'</error>
> ```
> **Follow-up (Bash):**
> ```
> $ echo "// guardrail probe" >> billing/.../Money.java
> (eval):1: permission denied: billing/src/main/java/edu/cmu/cs214/campusreserve/billing/Money.java
> $ sed -i '' '1s/^/\/\/ probe\n/' cli/.../Format.java
> sed: cli/src/main/java/edu/cmu/cs214/campusreserve/cli/Format.java: Permission denied
> $ git status --short
>  M PLAN.md
>  M guardrails/protected-trees.txt
> ```

Neither file changed. The session was in auto mode, not Manual. That doesn't matter for recipe C, because the OS blocks both the Edit tool and Bash.

---

## Milestone 3: The run

**Task:** T1, run under the lock.

**Takeover point** (written before the run). I stop the agent and write the code myself if any of these happen:
1. The diff touches anything outside `core-domain/` and `notifications/`, or any shipped test. Then I `git checkout` that path.
2. Either rule 3/4 grep prints a hit. Then I replace that code with a `ConflictChecker` call.
3. The gate fails on the same test after two fix attempts.

None of these fired.

**Gate** `mvn -B -pl notifications -am test`:
```
[INFO] Tests run: 189, Failures: 0, Errors: 0, Skipped: 0     (core-domain, was 167)
[INFO] Tests run: 44, Failures: 0, Errors: 0, Skipped: 0      (notifications, was 41)
[INFO] BUILD SUCCESS
```

**Closing** `mvn -B clean test`:
```
[INFO] Tests run: 189 / 63 / 44 / 19 / 19 / 78 / 31, all Failures: 0, Errors: 0
[INFO] BUILD SUCCESS        (443 Java tests = 418 + 25; pytest still 137 passed)
```

**Diff** (`git add -N . && git diff --stat`). Everything is in T1's scope.

| File | Maps to |
|---|---|
| `core-domain/.../domain/Booking.java`: `history` field, `modified(...)`, 7-arg constructor kept | CR 5 |
| `core-domain/.../domain/BookingChange.java` (new) | CR 5 |
| `core-domain/.../domain/ModificationResult.java` (new) | result type |
| `core-domain/.../events/BookingModifiedEvent.java` (new), `DomainEvent.java` (permits) | CR 8 event |
| `core-domain/.../service/BookingService.java`: `modifyBooking` | CR 1–4 |
| `notifications/.../NotificationKind.java`, `NotificationService.java`, `templates/BookingModifiedTemplate.java` (new), `Placeholders.java`, `TemplateRegistry.java` | CR 6 |
| `BookingServiceModifyTest.java` (new, 22 tests), `BookingModifiedNotificationTest.java` (new, 3 tests) | rule 7 |

No shipped test changed (checked with `git cat-file -e 3a9923d:<path>`). No `storage/`, `http-api/`, or pom changes. One unexpected change: the `Booking` record gained a constructor overload so that existing 7-argument callers still compile.

**Line I read hardest:**
```java
List<Booking> ownOthers = excluding(bookings.findActiveByUser(booking.userId()), bookingId);
```
I was checking that the own-calendar check goes through `ConflictChecker` and not `checkAskerLimits`, which would bring back the cap, and that it excludes the booking by id, not by comparing intervals, which would be a second overlap predicate. `aBookerAlreadyAtTheCapCanStillModify` and `aOneSlotNudgeIntoItsOwnTimeIsAllowed` pin it.

**Refuter brief.** Fresh subagent; given only `CHANGE-REQUEST.md`, the T1 diff, and the scope line:

> You are a refuter. Your job is to break a change, not to approve it. Scope: "T1 — BookingService.modifyBooking with modification history on Booking, a BookingModifiedEvent, and the owner's booking-modified notification; core-domain and notifications only (storage, analytics, HTTP PATCH are later tasks)." Find inputs or call sequences where it does the wrong thing against CR clauses 1–6. Start where a gate could stay green while wrong: self-exclusion in the room and own-calendar checks, the waived cap, whether a rejection truly changes nothing, when the promotion pass runs, the no-op path, every clause-3 rule. Back each claim with a JUnit probe in `RefuterProbeTest.java` and run it. Edit nothing else, fix nothing. Report clause, call sequence, probe, RED/GREEN, confidence.

**Result:** 33 probes, all GREEN.

**Strongest claim:** the refuter found lengthening a booking can still promote someone, although CR 4 says lengthening "frees nothing". The setup:
1. u-2 waits on r-1 11:00–12:00.
2. When that slot frees, u-2 is skipped because of their own r-2 booking.
3. u-2 then drops r-2, but that pass only checks the r-2 waitlist, so the r-1 entry is left waiting.
4. u-1 lengthens an unrelated r-1 booking.
5. u-2 is promoted.

**Dismissed with evidence.** CR 4 requires "the same … promotion that a cancellation triggers today". I wrote a control probe with the same backlog:
- `shippedCancellationOfAnUnrelatedBookingAlsoPromotesTheBackloggedWaiter`
- `lengtheningPromotesTheSameWaiterTheSameWay`

Result: `Tests run: 2, Failures: 0, Errors: 0`. Today's `cancelBooking` promotes the same waiter into the same 11:00–12:00 slot. So the behavior comes from the pass the CR tells us to reuse, not from T1. A waiter left behind after a cancellation in another room is a gap in the shipped promotion pass. All probe files were deleted, and `unlock.sh` was run.
