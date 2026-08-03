# HSTS — change log

Running record of every meaningful change: **what** changed, **why**, and **how**.
Kept so the project can be explained and defended at the demo.

---

## Milestone 1 — walking skeleton · 2026-07-29

**Goal:** prove that JavaFX + OCSF + MySQL + fat-jar packaging all work together
on JDK 26 *before* writing a single feature. No HSTS functionality is included
on purpose.

### What was built

| Item | Notes |
|---|---|
| Four Maven modules | `hsts-ocsf`, `hsts-common`, `hsts-server`, `hsts-client` |
| `G1_Server.jar` (14.3 MB), `G1_Client.jar` (9.7 MB) | Names required by the Assignment 3 instructions |
| Server startup window + console screen | FXML; מתווה item 15 |
| Client startup window + skeleton check screen | FXML; מתווה item 15 |
| `Request` / `Response` / `Credentials` protocol envelope | Shared by both jars |
| `PasswordHasher` | Salted SHA-256 |
| `DBController` (Singleton) | Creates the database if absent |
| `HSTSServer` (Singleton, extends OCSF `AbstractServer`) | One dispatch switch |
| `HSTSClient` + `ClientController` | Observer wiring, `Platform.runLater` handled once |

### Decisions made, and why

**OCSF given its own module.** The system description §11 asks for external
reuse. A separate module makes the borrowed framework visible at a glance and
guarantees we never edit it by accident.

**Only three OCSF classes copied in.** `AbstractClient`, `AbstractServer`,
`ConnectionToClient` — the three the submitted class diagram uses. The
`Adaptable*` / `Observable*` variants and the SimpleChat example were left out.

**OCSF converted from CR-only to LF, and protected by `.gitattributes`.** Verified
no content was lost: LF counts after conversion (352 / 460 / 291) exactly match
the CR counts before it.

**A launcher class that does not extend `Application`.** This is the single most
important line in either build. Naming an `Application` subclass as a jar's
Main-Class makes the JVM demand JavaFX on the *module* path; a fat jar has none,
so it refuses to start with *"JavaFX runtime components are missing"* — even
though JavaFX is inside that very jar. `ClientLauncher` / `ServerLauncher` exist
solely to sidestep that check.

**The repository root is `HSTS`, not `SE`.** The course staff's Hebrew documents
live in `SE` and must not be published to a public repository.

**Client has no database dependency.** Verified in the built artifact: the server
jar contains 1111 `com.mysql` classes, the client contains **zero**. The
three-tier separation is real, not just drawn.

### Problems hit, and how they were solved

**1. Maven could not find the JavaFX versions.**
*Symptom:* `'dependencies.dependency.version' for org.openjfx:javafx-base:jar is missing`.
*Cause:* Maven matches a dependency to its managed version by
groupId **+ artifactId + type + classifier** — all four. The parent declared the
JavaFX artifacts with `<classifier>win</classifier>`, the modules requested them
without one, so nothing matched.
*Fix:* repeat the classifier in each module's dependency block.

**2. MySQL refused to connect with a time-zone error.**
*Symptom:* `The server time zone value 'Jerusalem Daylight Time' is not recognized
or represents more than one time zone`.
*Cause:* `connectionTimeZone=SERVER` makes the driver ask MySQL which zone it is
in. On Windows, MySQL answers with a Windows zone name; Connector/J only
understands IANA names such as `Asia/Jerusalem`.
*Worth knowing:* this failure happens **after** the password has already been
accepted, so it looks like a login problem when it is not.
*Fix:* `connectionTimeZone=LOCAL` — use the JVM's own zone and never ask the
server. Correct here because MySQL always runs on the same machine as the server
program.

*(Both of these are candidates for the "describe a design or coding problem you
met" question the Assignment 3 Word document requires.)*

### Verified — by running it, not by assuming

| Check | Result |
|---|---|
| Four modules build | 0 errors |
| Jar names, manifests | `G1_Client.jar` → `hsts.client.ClientLauncher`; `G1_Server.jar` → `hsts.server.ServerLauncher` |
| JavaFX 26 fat jar launches on JDK 26 | yes — both jars |
| FXML loads from inside the packaged jar | yes |
| JavaFX native DLLs survive shading | 54 in each jar |
| `module-info.class` collisions | none |
| Database smoke test | **8 / 8 passed** — MySQL 8.0.46 |
| End-to-end client↔server↔database test | **14 / 14 passed** |
| OCSF survives a full git round trip | fresh clone byte-identical, and it builds |
| GUI round trip, clicked through by hand | **passed** — server startup window → console showing MySQL 8.0.46; client startup → Connect → PING → login as `teacher1` |

### Known minor issue

Server log prints `Client disconnected: null` on a clean disconnect. OCSF's
`ConnectionToClient.toString()` reads the socket address, which is already gone
by the time the hook runs. Cosmetic only — logged here so it is not mistaken for
a fault later.

### Still outstanding for milestone 1

- **Two-laptop test over the LAN**, including the Windows Firewall rule. Everything
  so far has run on one machine, where the network path is real TCP but never
  leaves the box. Until this is done, מתווה item 15 is not fully proven.

### Verdict

Milestone 1 achieved its purpose. The stack is proven on JDK 26 and no feature
work is blocked on unknown technology. The one remaining check needs a second
machine.

---

## Milestone 2 — login, roles and menus · 2026-07-29

**Covers:** SUC-1, מתווה scenario 1, requirements 2, 4, 5, 11, 12, 13.

### What was built

| Item | Notes |
|---|---|
| `SchemaManager` | The **full** 19-table schema from the plan, not just what milestone 2 needs |
| `SeedRunner` | 4 subjects, 8 courses, 53 users, ~160 enrolments |
| `UserDAO` + `IDAO` | DAO pattern; all user SQL in one class |
| `IUserManagementSystem` + `LocalUserManagementAdapter` | The boundary, and its local implementation |
| `SessionRegistry` | Single-session enforcement, and the foundation for server push |
| `LoginController` | SUC-1 |
| Entities | `User`, `Teacher`, `SubjectCoordinator`, `Student`, `Principal`, `Subject`, `Course`, `UserRole` |
| `IsraeliId` | Check-digit validation and generation |
| Client screens | `Login.fxml`, `MainMenu.fxml` and their controllers |

The milestone 1 scaffolding (`SkeletonCheck` screen, `m1_skeleton*` tables) was
deleted, not left lying around.

### Decisions made, and why

**The whole schema was created now, not just the users part.** Later milestones
add rows, not tables. Creating it once keeps the design in a single readable
file and means no milestone has to migrate anything.

**`ExamStatus` has no `IN_DRAWER` value.** As agreed: an exam is
`PENDING_APPROVAL`, `APPROVED` or `REJECTED`, and "in the drawer" is answered by
asking whether it has an open execution. The old enum could not describe an
approved-but-not-yet-released exam at all.

**Execution counts are not stored.** Requirement 48's started / finished /
timed-out numbers will be a `COUNT` over `student_exam`. A stored counter that
someone forgets to increment is a bug you discover at the demo; a `COUNT` is
right by construction.

**Login sessions live in memory, not in a database column.** A `logged_in` column
survives a crash or a closed laptop lid, and then that user can never log in
again without hand-editing the database. `SessionRegistry` is cleared by OCSF's
disconnect hook, and a server restart logs everyone out - which is the truth.

**The `User` object sent to the client has no password, hash or salt field.**
It crosses the network on every login, so anything on it is handed to the client.
The hash and salt are read inside `UserDAO`, compared there, and dropped. There
is a test that fails if a field with such a name is ever added.

**Failed logins say "Incorrect username or password" for both causes.** Naming
which one was wrong would let anyone discover valid usernames by trying them.
`UserDAO.verifyCredentials` also performs a hash computation when the username
does not exist, so a missing user takes about as long to reject as a wrong
password - otherwise the timing alone gives it away.

**The seeder is Java, not a `.sql` file.** Two reasons a script cannot meet.
Every user ID must pass the Israeli check-digit test now that a student types
hers before an exam - `123456789` is not a valid ID. And later milestones need an
exam open *at the moment of the demo*, so dates must be computed relative to when
the seeder runs, not hard-coded. Its random generator has a fixed seed, so the
data is identical on both laptops and on every run.

**Menu entries for unbuilt features are shown but disabled, labelled with their
milestone.** Hiding them would make the menu look finished when it is not.

### Problem found during GUI testing, and how it was fixed

**Long messages were cut off at the bottom of the window.** Logging in twice as
the same user produced *"This user is already logged in on another computer. Log
out the..."* — and the rest was invisible.

*Cause:* a window is sized once, when it is first shown, to fit the text it had
at that moment. The status labels wrap, so a longer message becomes two or three
lines, but the window did not grow to match. An error the user cannot finish
reading is worse than no error, because it looks like the program is broken.

*Fix, and where it was put:* this was not patched on the login screen alone. The
submitted class diagram has an abstract **`GUIScreen`** base for every screen with
a `showMessage` method, which had not been created yet — so it was created, and
the fix lives there. Every screen now inherits one way of showing messages, and
each one resizes the window to fit. `ClientStartupController`, `LoginScreenController`
and `MainMenuController` were rewritten to extend it, and the server's startup
window got the same treatment through `ServerApp.fitToContent()`.

The resize is three steps, and the order matters: `applyCss()` (styles change font
size, font size changes wrapping), then `layout()` (force the wrap to be
recalculated *now*), then `sizeToScene()`. Without the middle step the window
measures the *previous* message and stays one step behind.

*Worth noting for the report:* this is a good example of a bug that no automated
test would have caught. The 48 checks all passed while the message was
unreadable, because they assert what the server sends, not what the window shows.
It was found by a person clicking buttons.

### Verified — 48 automated checks, all passing

| Group | Result |
|---|---|
| All 19 tables created; milestone 1 scaffolding dropped | 20/20 |
| Seeding: 4 subjects, 8 courses, 53 users, enrolments | 4/4 |
| Re-seeding is a no-op (no duplicates) | 1/1 |
| **All 53 generated IDs pass the Israeli check digit** | 3/3 |
| 7 courses have two teachers (מתווה 13.3 needs at least one) | 1/1 |
| Each role logs in and arrives as the right Java subclass | 4/4 |
| **The `User` sent to the client carries no secrets** | 1/1 |
| Course and subject associations really loaded from the database | 5/5 |
| Wrong password and unknown user both refused, with identical wording | 3/3 |
| **Requirement 4: the same user cannot log in twice** | 3/3 |
| Logging out frees the account | 2/2 |
| A dropped connection frees the account too | 2/2 |

### Still outstanding

- The two-laptop LAN test, carried over from milestone 1.
- GUI click-through of the login and menu screens.

---

## Milestone 3 — the question bank · 2026-07-29

**Covers:** SUC-2, מתווה scenario 2 (all four items), requirements 14–18.

### What was built

| Item | Notes |
|---|---|
| `Question`, `Answer`, `DifficultyLevel` | Entities, with `version` / `isCurrent` / `isDeleted` / `topic` |
| `QuestionDAO` | Versioning, soft delete, 5-digit id generation, topic lookup |
| `CourseDAO` | Read-only — courses come from an external system (requirement 11) |
| `QuestionController` | SUC-2, with all validation |
| `QuestionMgmt.fxml` + controller | Add, edit, browse, delete, version history, picture upload |
| `QuestionRef` | Points at a question *and* a version — "which question" is two facts now |
| Menu wiring | "Question bank" is the first live entry |

### Decisions made, and why

**Editing never updates a row.** `QuestionDAO.update()` deliberately throws
`UnsupportedOperationException`; edits go through `createNewVersion()`, which
clears `is_current` on every existing row and inserts a new one. All three steps
run in a single transaction — halfway through, the question has *no* current
version at all, and a failure there would make it vanish from the bank while
still sitting in the database.

**A question number is never reused.** The next id is `MAX(...) + 1` across *all*
versions, including deleted ones. Reusing a freed number would make two different
questions share an identifier, and every exam that referenced the old one would
silently change meaning. Verified by test 11.

**Deleting is soft, and marks every version.** The question leaves the bank and
future exam building, but stays in the database, because a student's marked paper
from last month must keep showing the question she actually answered. A real
`DELETE` would either fail on the foreign key or destroy that history.

**The list view does not carry pictures.** A picture can be hundreds of kilobytes;
a 25-question list would drag every one across the network to render a table of
text. Images load only when a single question is opened. NFR 18 asks for the
computing to be done as efficiently as possible, and this is one of the places it
actually costs something.

**The topic combo box is editable and pre-filled from the course.** Automatic exam
building selects by exact topic, so "Fractions", "fractions" and "Fraction" would
quietly become three separate topics and break it. Offering what already exists
makes the consistent choice the easy one.

**Exactly one correct answer is enforced three times.** The radio buttons make two
impossible in the GUI; the client re-checks before sending; the server checks
again. Only the third one counts — a client is a program on someone else's
computer and can send anything.

### Problem found by the test suite

**Every edit was rejected with "The question must belong to a course".**
`editQuestion` validated the incoming question *before* filling in the course code
from the stored one. On an edit the course is not the client's to supply — it is
baked into the 5-digit id and comes from the database — so validation always saw
an empty field.

The GUI would have hidden this, because the screen happens to send the course code
it already has. The server must not depend on that, so the fix was to look up the
existing question, copy its course code, and only then validate.

*Also fixed:* the test harness hung for four minutes instead of failing. When the
body threw, the main thread died but OCSF's listening thread is not a daemon, so
the JVM stayed alive with no output. The harness now always exits through a
`finally` block.

### Verified — 34 automated checks, all passing

| Group | Result |
|---|---|
| Requests refused before signing in | 1/1 |
| 5-digit id format: 3-digit number + 2-digit course code | 4/4 |
| Server-side validation: empty text, empty topic, 3 answers, 0 correct, 2 correct | 5/5 |
| **Edit creates version 2 and keeps version 1** | 4/4 |
| **Version 1 still holds its original text, topic and difficulty** | 3/3 |
| The bank shows each question once, at its current version | 2/2 |
| Topic list picks up new topics | 1/1 |
| **Pictures survive the round trip byte-for-byte; list view omits them** | 3/3 |
| **Soft delete: gone from the bank, rows still in the database** | 4/4 |
| A deleted question number is never handed out again | 1/1 |
| Requirement 14: another teacher is refused both read and write | 2/2 |
| A student has no question bank at all | 1/1 |

---

## GUI overhaul and milestone 3 corrections · 2026-07-29

Four problems reported after clicking through milestone 3, and a general pass
over the interface. NFR 21 asks for interface quality and friendliness, and NFR
19 for a design that absorbs change efficiently — both are easier to satisfy
once the look lives in one place.

### 1. Version history did not show what had changed

*Reported:* "I don't see the old version and the difference between the 2."

The old implementation was a dialog listing each version's fields one after
another. It proved the versions existed but not what was different between them —
which is the only reason to open it. Fair criticism.

Replaced with a proper **`VersionHistory`** window: every stored version on the
left, and the selected one laid out **beside the current one, field by field**,
with each difference highlighted and counted. Text, instructions, topic,
difficulty, all four answers, which answer is correct, and whether a picture is
attached are all compared. It opens the newest *older* version by default,
because that is the comparison you came for.

It is a window rather than a dialog so it can be left open while looking at the
question underneath.

### 2. The Save button needed scrolling to reach

The editor lived inside a `ScrollPane` and Save was the last thing in it, so on a
short window you had to scroll to find it — and the confirmation message then
appeared somewhere you were not looking.

Save, Discard and the status message are now **pinned outside the scroll area**.
Only the form scrolls.

### 3. "Answer 2 is empty" replaced with a general message

Naming the offending answer added nothing — the empty box is visible on screen —
and it read as nagging. Both the client and the server now say **"All four answers
must be filled in."**

### 4. One stylesheet for the whole system

Everything was inline `-fx-` styles scattered through the FXML, so each screen
carried its own idea of what a heading or an error looked like. They would have
drifted further apart with every screen added.

Added **`css/hsts.css`** in `hsts-common`, which is packaged into *both* jars, so
the server's windows match the client's. It defines a palette, type scale, a 4px
spacing grid, and classes for cards, headers, buttons, inputs, lists, tables,
status messages and the comparison view. Every screen was rewritten to use those
classes; `GUIScreen` now swaps a style class rather than setting colours inline.

Screens restyled: client startup, login, main menu, question bank, version
history, server startup, server console.

### 5. JavaFX native-access warnings silenced

Starting either jar printed five warnings, ending with *"Restricted methods will
be blocked in a future release"*. JavaFX loads its native libraries with
`System.load()`, which JDK 24 made a restricted method. Nothing was broken, but it
looks alarming during a demo and a later JDK would refuse outright.

Fixed properly with `Enable-Native-Access: ALL-UNNAMED` in both jar manifests,
rather than by suppressing the message. Both jars now start silently.

### Verified

| Check | Result |
|---|---|
| Milestone 3 suite | **34 / 34** |
| Milestone 2 suite, for regressions | **48 / 48** |
| Stylesheet present in both jars | yes |
| Both jars start with no warnings | yes |

### Deferred by decision

The **two-laptop LAN test** is set aside for now at the user's direction. It is
still required before submission — מתווה item 15 is not fully proven until the
client runs on a second machine — and is recorded here so it is not forgotten.

### Bug: two screens would not open at all

*Reported:* clicking **Question bank** gave "That screen failed to open: ...".

**Cause: `--` is illegal inside an XML comment.** The two new screens used
decorative separators written as

```
<!-- ---------- left: the bank ---------- -->
```

which makes the file invalid XML, so `FXMLLoader` refused it. `QuestionMgmt.fxml`
and `VersionHistory.fxml` were both affected; the other five screens used `=====`
and were fine.

The habit came from Java and CSS, where `// ----------` and `/* ---------- */`
are perfectly legal. The rule is specific to XML, and nothing in the build warns
about it — the compiler cannot see inside an FXML file, so it compiled cleanly and
all 82 automated checks still passed.

**Fixed** by replacing the separators, and by two changes to stop it recurring:

1. **`tools/FxmlLoadCheck.java`** — loads every screen and reports any that fail.
   It would have caught this before the button was ever clicked. Run it after
   `mvn package`; the command is in its header comment.
2. **`GUIScreen.switchTo` now reports the root cause.** The outer `LoadException`
   has an empty message, which is why the error read "That screen failed to open:"
   with nothing after it. It now unwraps to the real cause, names the file, and
   prints the full trace to the console.

*Worth keeping for the report:* a whole category of failure that no amount of
server-side testing can find, because it lives in a file the compiler never reads.

| Check | Result |
|---|---|
| All 7 FXML screens load | **7 / 7** |
| Milestone 3 suite | **34 / 34** |
| Milestone 2 suite | **48 / 48** |

---

## Milestone 4 — building exams · 2026-07-29

**Covers:** SUC-3, SUC-4, מתווה scenario 3 (all five items), requirements 20, 22–29.

### What was built

| Item | Notes |
|---|---|
| `Exam`, `ExamQuestion`, `ExamStatus` | Entities, versioned like questions |
| `ExamBuildStrategy` + `Manual` / `Automatic` | **Strategy pattern** — the two selection algorithms |
| `InsufficientQuestionsException` | Requirement 29, as a refusal that cannot be ignored |
| `ExamDAO` | Versioning, 6-digit id generation, version pinning |
| `ExamBuilderController` | SUC-3 and SUC-4, and all validation |
| `ExamBuilder.fxml` + controller | Both build modes, live points total, quota lines |
| `ExamBuildCriteria`, `QuestionQuota`, `ExamRef` | Protocol |

### Decisions made, and why

**Building returns a draft that is not saved.** The teacher gets it back, adjusts
points, duration and instructions, and only then saves. That is what the submitted
SUC-3 sequence diagram shows, and it is what makes requirement 29 honourable: an
impossible automatic request is refused *before* anything exists to clean up.

**Where the Strategy pattern earns its place.** `buildDraft` picks a strategy once
and then calls it. There is no `if (automatic)` anywhere else in the controller —
the id, the points, the duration, the validation and the saving are identical
whichever way the questions were chosen. A third way of building would be one new
class and no edits to what already works.

**Tighter quotas are filled first.** A line fixing both topic *and* difficulty has
the fewest candidates, so it gets first pick. Filling loose lines first could
consume the only questions a specific line could have used and fail a request that
was actually satisfiable.

**A question is never used twice in one exam.** Quotas can overlap — "3 easy
Fractions" and "5 of anything" may match the same question — so each is removed
from consideration as it is taken. Without that, an exam could ask the same
question twice and the points would still add to 100.

**Points are shared evenly, remainder included.** Most question counts do not
divide 100. Three questions become 34/33/33 rather than 33/33/33 — the remainder
is handed out a point at a time instead of being dropped, so the total is exactly
100 whatever the count.

**Editing an exam sends it back for approval.** A new version always returns to
`PENDING_APPROVAL`, whatever the old one was. An approved exam that is then edited
is no longer the exam the coordinator approved — otherwise editing would be a way
to slip changes past approval entirely.

**The 100-point rule is enforced although no requirement states it.** It appears
in מתווה scenario 3 note 3 and acceptance test 1.5, and in no numbered requirement
— a gap found in phase 0. The מתווה is the acceptance bar, so it is enforced.

### Bug found by the test suite

**`ArrayList.subList()` is not serializable.** Passing one into a build request
made the whole message fail at `sendToServer` with
`NotSerializableException: java.util.ArrayList$SubList` — an error naming the
network layer and saying nothing about the real cause.

The test hit it, but the weakness was in the protocol class: it stored whatever
list it was handed, so any caller could build an unsendable message by accident.
`ExamBuildCriteria`, `Exam.setQuestions` and `Question.setAnswers` now **copy**
into a plain `ArrayList`. `List.of(...)` has the same trap, and it is exactly the
kind of ordinary Java that would otherwise fail only at run time, on the wire.

### Verified — 43 automated checks, all passing

| Group | Result |
|---|---|
| Manual build, versions pinned at build time | 5/5 |
| Points shared evenly with the remainder (3 → 34/33/33) | 2/2 |
| 6-digit id: 2 exam + 2 course + 2 subject | 5/5 |
| **100-point rule enforced server-side, message names the real total** | 2/2 |
| Duration and empty-exam rules (tests 1.4, 1.8) | 3/3 |
| **Automatic build honours topic and difficulty quotas exactly** | 5/5 |
| No duplicate questions even with overlapping quotas | 2/2 |
| **Requirement 29: refused, and no exam row created** | 5/5 |
| Edit creates v2, v1 survives with its original duration | 5/5 |
| **Version pinning: rewriting a question does not change an old exam** | 3/3 |
| Hidden teacher notes stored separately from student instructions | 2/2 |
| Requirement 20: another teacher refused both build and edit | 2/2 |

Regression: milestone 2 **48/48**, milestone 3 **34/34**, all 8 screens load.

---

## Interface: modern neutral theme · 2026-07-29

The school-stationery direction (warm paper, serif headings, brass) was tried and
rejected — it read as dated rather than institutional. Replaced with a clean
modern one. As before this was **one file**, `css/hsts.css`; no FXML and no Java
changed, which is the whole reason the styling was centralised.

**What the theme does now**

- A near-neutral surface palette, so the only colour on screen is colour that
  carries meaning.
- Primary actions are **near-black**, not blue. That leaves blue free to mean
  "focused" everywhere else, and stops the screen having two competing
  attention colours.
- **6px corner radius** throughout — soft enough not to look harsh, tight enough
  not to look like a toy.
- Hierarchy through size, weight and whitespace rather than decoration. Headings
  are not coloured.
- One very light shadow on raised panels. No gradients, no glow, no translucency.
- Sans throughout (Segoe UI Variable where present); the serif headings are gone.

**Git history**

At the author's request, the `Co-Authored-By` trailer was removed from all nine
existing commits with `git filter-branch --msg-filter`, and will not be added to
future ones. Verified before force-pushing: file contents byte-identical to the
pre-rewrite tree, all nine commits preserved, zero trailers remaining on the
remote. Every commit is authored solely by the project author.

All 8 screens load.

---

## Milestone 5 — approval, and server push · 2026-07-29

**Covers:** SUC-5, מתווה scenario 4, requirements 30, 31, 33, and **NFR 18**.

### What was built

| Item | Notes |
|---|---|
| `PushType`, `PushEvent` | Server-to-client messages nobody asked for |
| `PushService` | Best-effort delivery through `SessionRegistry` |
| `ExamApprovalController` | SUC-5 |
| `ExamApproval.fxml` + controller | The coordinator's queue and decision screen |
| `ExamDecision` | Names the exam *and the version* being decided |
| `GUIScreen.onPush` | Every screen becomes the push listener automatically |
| `SeedRunner.resetAndSeed` + console button | Wipe and re-seed, for after tests and before the demo |

### Server push — the point of the milestone

NFR 18 forbids a manual refresh. A client that only speaks when spoken to would
need a Refresh button, or would have to poll — too slow and the screen is stale,
too fast and every idle client hammers the server for nothing. So the server
keeps the connection and speaks first. That is the **Observer** pattern from the
submitted class diagram, finally doing real work.

**`PushEvent` is a separate class from `Response`, deliberately.** A response
answers a question this client asked and carries its `requestId`; a push answers
nothing and can arrive at any moment — including while a screen is waiting for
something else. One shared class would let an announcement be mistaken for the
reply a screen was waiting for, which is exactly the sort of intermittent fault
that only appears under load.

**Every screen becomes the push listener as it opens**, wired inside
`bindStatusLabel` so no screen can forget. A screen that forgot would silently
swallow the announcements NFR 18 exists to deliver.

**Delivery is best-effort and never throws.** A rejection is committed to the
database before anyone is told; if the teacher is offline the event is dropped
and she sees the decision next time she looks. A push must never be able to undo
the operation that caused it — tested explicitly.

### Decisions made, and why

**Requirement 33 says the reason is *sent*, not just stored.** "סיבת הדחייה תישלח
למורה ותישמר במערכת" — both halves. Storing alone would leave the teacher to go
and look, which combined with NFR 18 is precisely what is forbidden. So a
rejection is pushed to the author with the reason in it.

**A rejection without a reason is refused.** A refusal the teacher cannot act on
is not a rejection. Checked on the client for speed and on the server for real.

**An already-decided exam cannot be decided again.** Two coordinators, or one
stale screen, could otherwise overwrite each other's decision with neither
noticing.

**Editing a rejected exam clears the reason and returns it to pending.** Version 1
keeps its REJECTED status and its reason forever; version 2 starts clean. The
history of *why* it was rejected survives, which is the point of versioning.

**The coordinator is told when a new exam arrives**, from `HSTSServer` rather than
from inside `ExamBuilderController` — so the builder does not need to know that
approval exists at all.

### Verified — 31 automated checks, all passing

| Group | Result |
|---|---|
| A teacher cannot approve, or even see the queue | 2/2 |
| The coordinator sees her own subject's queue | 3/3 |
| **Requirement 31: another subject's coordinator is refused, and sees nothing** | 3/3 |
| **Requirement 33: a rejection without a reason is refused** | 2/2 |
| **PUSH: the teacher is told unprompted, and the message carries the reason** | 4/4 |
| The reason is also stored verbatim | 2/2 |
| Approval pushes too | 3/3 |
| A decided exam cannot be decided twice | 1/1 |
| Decided exams leave the queue | 1/1 |
| **PUSH the other way: a new exam reaches the coordinator unprompted** | 3/3 |
| Editing a rejected exam returns it to pending; v1 keeps its reason | 5/5 |
| **A push to an offline user is harmless — the decision still succeeds** | 2/2 |

Regression: M2 **48/48**, M3 **34/34**, M4 **43/43**. All 9 screens load.

### Two bugs found by clicking through milestone 5

**1. The coordinator could see an exam but not open it.**

Selecting an exam in the approval queue left the detail pane empty, and pressing
Approve then said "Select an exam from the list first."

*Cause:* `EXAM_GET` checked "do you teach this course". A subject coordinator
approves every exam in her **subject**, and does not necessarily teach the course
it belongs to — coordinator 1 coordinates Mathematics and teaches Algebra, while
the exam was for Plane Geometry. So the queue listed it correctly and the fetch
refused it.

*Fix:* a separate `refuseIfCannotViewExam` that accepts either the teacher of the
course **or** the coordinator of the subject. The coordinator test has to come
first, because `SubjectCoordinator` extends `Teacher` and the teacher branch would
otherwise catch her and reject her on a course she does not teach.

*Worth noting:* the automated suite passed throughout, because every test drove
approval through `EXAM_PENDING_FOR_COORDINATOR` and the decision endpoints — none
of them opened an exam **as the coordinator**, which is the one thing a human does
first. A test now covers exactly that.

**2. A list holding one item looked like a dozen blank ruled rows.**

`.list-cell` was styled unconditionally, so the divider line was drawn under
every empty row as well. Now only `:filled` cells get a divider.

Also fixed "1 questions" in two places.

---

## Milestone 6 — releasing an exam from the drawer · 2026-07-29

**Covers:** SUC-6, מתווה scenario 5, requirements 34, 35, 36, 37.

### What was built

| Item | Notes |
|---|---|
| `ExecutionCode` | The 4-character code: validation, normalising, generation |
| `ExamExecution` | One sitting — code, window, minutes, attempts |
| `ExecutionDAO` | Insert, lookup by code, "is this exam out of the drawer" |
| `ExamExecutionController` | SUC-6 and all its validation |
| `ExamReleaseRequest` | Protocol |
| `ExamRelease.fxml` + controller | Approved exams · the form · what is already out |

### Decisions made, and why

**The teacher releases, not the coordinator.** The submitted class diagram put
`releaseFromDrawer` and `setExamDates` on `ExamApprovalController`. מתווה scenario
5 says plainly "**המורה** מגדירה מועד... **המורה** מגדירה קוד ביצוע". The
coordinator approves; the teacher decides when her class sits it.

**Any teacher of the course may release, not only the author.** Decision 4 from
planning, and it follows from decision 5: whoever releases it marks it. Tested —
`teacher2` can release `teacher1`'s approved exam.

**Only approved versions are even offered.** Requirement 35 forbids dates on an
unapproved version, so `listReleasable` filters on `APPROVED`. Offering an exam
and then refusing it would be worse than not offering it. Both are enforced.

**An approved version stays releasable after it is superseded.** If v1 is approved
and the teacher then edits it into a pending v2, v1 remains releasable and v2 does
not. What matters is the status of the *version*, not whether it is current.

**Codes are unique globally, case-insensitive, and avoid lookalikes.** A student
types four characters and nothing else, so the code must identify one sitting on
its own; 36⁴ is over 1.6 million, so global uniqueness costs nothing. Case is
ignored because the teacher says the code out loud. Generated codes leave out
`O`/`0` and `I`/`1`, which are indistinguishable when spoken — though a teacher
may still type one deliberately.

**A window that has already closed is refused.** Nobody could ever start, so it is
always a mistake — usually a month typed wrong.

**Times are truncated to whole seconds before storing.** A `LocalDateTime` carries
nanoseconds; a MySQL `DATETIME` does not and rounds them away. Left alone, the
object returned to the client would not match the row, and the confirmation
message would name a moment a fraction of a second off from the one saved.

**The unique constraint is the last line of defence.** Two teachers releasing the
same code in the same instant would both pass the `isCodeTaken` check; the insert
then fails and the message tells her to press Generate.

### Verified — 76 automated checks, all passing

| Group | Result |
|---|---|
| Only approved versions are offered; pending and rejected are not | 3/3 |
| **Requirement 35: releasing an unapproved version is refused** | 3/3 |
| Code format: 3 chars, 5 chars, empty, null, punctuation, embedded space | 6/6 |
| Window: close before open, close equal to open, window already past | 3/3 |
| Duration and attempts, including the 600 / 10 boundaries | 6/6 |
| A successful release: upper-cased code, ids, durations, zeroed counts, pinned version | 9/9 |
| **Codes unique whatever the case; lookup is case- and space-insensitive** | 6/6 |
| **Requirement 36: the same exam released twice gives two sittings** | 4/4 |
| **"In the drawer" = no execution open right now** | 4/4 |
| Who may release: wrong course, student, and a colleague who may | 6/6 |
| A teacher's own list excludes a colleague's releases | 3/3 |
| Suggested codes are valid, unused and varied | 2/2 |
| `ExecutionCode` itself, including both documents' wordings | 9/9 |
| **An approved version stays releasable after a newer draft exists** | 4/4 |
| **Window boundaries, and stored time matches the returned object exactly** | 8/8 |

Regression: M2 **48/48**, M3 **34/34**, M4 **43/43**, M5 **32/32** — **233 checks**
across the project. All 10 screens load.

---

## Interface: no truncated text anywhere · 2026-07-29

*Reported:* text cut off inside windows, with "..." in the middle of it.

Two separate causes, both fixed at the source rather than screen by screen.

**1. Three places deliberately cut text and appended "...".**
`Question.getSummary` at 70 characters, `ExamQuestion.toString` at 60, and the
exam-builder row label at 80. That made sense when list cells could not wrap —
but the cells wrap now, so the shortening only put an ellipsis in the middle of
the very question the teacher was reading in order to choose. All three removed;
the text is shown in full.

**2. A JavaFX `Label` truncates by default.** Unlike a wrapped cell, a plain
`Label` narrower than its text shows an ellipsis and nothing else — and widening
the window does not help once the layout has stopped growing. **80 labels across
all 10 screens** now have `wrapText="true"`.

**3. Wrapping alone was not enough inside a header bar.** A label can only wrap if
it has somewhere to wrap *into*. The title and subtitle sit in a `VBox` inside an
`HBox`, and without `hgrow` that `VBox` kept its preferred width, got squeezed
when the window narrowed, and clipped instead of re-wrapping. The header `VBox` on
all 7 screens that have one now grows into the leftover space.

Combined with `GUIScreen.fitToContent`, which already resizes a window when a
message changes, every screen now shows its text in full and re-flows as it is
resized.

Verified: 10/10 screens load, and M2 **48/48**, M3 **34/34**, M4 **43/43**,
M5 **32/32**, M6 **76/76** — 233 checks, no regressions.

---

## Milestone 7 — sitting an exam · 2026-07-29

**Covers:** SUC-7, מתווה scenario 6, requirements 21, 38, 40, 41, 44, 45, 46, 48.

### What was built

| Item | Notes |
|---|---|
| `StudentExam`, `StudentAnswer`, `SubmissionStatus` | Entities |
| `SubmissionDAO` | Attempts, answers, finishing, deadline moves |
| `TakeExamController` | Code → identity → paper → hand in |
| **`ExamClockService`** | The one clock: ticks every second, closes expired exams |
| `TakeExam.fxml` + controller | Three panes in one window |

### The clock is on the server, and that is the whole point

A countdown running on the student's computer would decide when her exam ends.
That is the wrong place three times over: the two laptops' clocks need not agree,
a client that freezes quietly awards extra time, and anyone who cared to could
stop it. So the server ticks once a second, sends the seconds remaining, and makes
every judgement against the deadline **stored in the database**. Acceptance test
2.11 already required the timer to be synchronised from the server.

Because the deadline is a column rather than a countdown in memory, a student can
close the client and reopen it — or the server can restart — and her remaining
time is still exactly right.

### Decisions worth defending

**Decision 8 is now real.** Her deadline is written as *start + minutes allowed*.
The sitting's closing moment governs only whether she may **begin**. Tested
directly: a student starting shortly before the window shuts has a deadline
**after** it, keeps her full time, and can still save answers once it has closed.

**The answer key never reaches the student.** The exam object carries `isCorrect`
on every option and would otherwise be serialised to her computer, where a
modified client or anyone reading the traffic would have it. The paper is copied
with every flag cleared before it leaves the server. Likewise the teacher's
private notes are simply never put on the object that travels to a student.

**Answers are saved as she chooses them**, not on submit. Requirement 45 keeps
whatever she had entered when the time runs out, and a client that dies mid-exam
must not take her work with it.

**Blank answer rows are written up front**, so a question she never touches exists
and is marked wrong rather than silently missing (acceptance test 2.12).

**Submit and time-out cannot both win.** `finish` updates only where the status is
still `IN_PROGRESS`, so whichever arrives second changes no rows. If her time ran
out a moment before she pressed Submit, it is recorded as `TIMED_OUT` — the stored
deadline decides, not the arrival time.

**The recorded end time is her deadline, not the moment the tick noticed.**
Otherwise her duration would include however long the tick took, and two students
who ran out at the same instant would be recorded differently.

### Found by the validation loop

**1. A test that passed for the wrong reason.** Section 13 asserted that starting
after the window shut is refused — and it was, but the message read *"You are not
signed in"*. The helper had picked a student already logged in elsewhere, so
requirement 4 blocked the login. The assertion now checks *why* it was refused.

**2. The reply's shape depended on which branch produced it.** `submitExam`
returned the attempt without its answers loaded. Worse, the early return for an
already-closed exam did the same — so a student whose time ran out saw a screen
saying her answers were saved, on an object that appeared to contain none. Both
paths now load them.

**3. The schema caught a bad update.** An attempt to push `close_time` into the
past was rejected by `CHECK (close_time > open_time)` — correctly, since it would
have described a window ending before it started.

### Verified — 54 automated checks, all passing

| Group | Result |
|---|---|
| Only a student may sit an exam | 1/1 |
| The code: unknown, malformed, correct in lower case, no paper handed over yet | 4/4 |
| **Requirement 21: a student not enrolled is refused** | 1/1 |
| Identity: too short, letters, **wrong check digit**, valid but not hers | 4/4 |
| Starting: paper, status, attempt number, **deadline = start + allowed**, blank rows | 5/5 |
| **The answer key and the teacher's notes never reach the student** | 2/2 |
| Answering, and out-of-range refused | 3/3 |
| Resuming returns the same attempt with its answers | 4/4 |
| **Another student cannot save, hand in, or even reload her paper** | 3/3 |
| Handing in: status, duration, end time, no answering after, twice is harmless | 6/6 |
| Acceptance test 2.8: one attempt means one attempt | 1/1 |
| **Decision 8: her deadline outlives the window; full time; refused after it shuts** | 6/6 |
| **The clock closes her exam by itself, and the answer survives in the database** | 4/4 |
| The countdown is pushed unprompted | 2/2 |
| Requirement 48: started / finished / timed-out counts | 3/3 |

Regression: **287 checks** across the project, 11/11 screens load.

---

## Milestone 8 — managing a sitting while it runs · 2026-07-29

**Covers:** SUC-8, מתווה scenario 7, requirements 47 and 48, **acceptance test 2.7**.

### What was built

| Item | Notes |
|---|---|
| `LiveExamController` | Watch a sitting; change its time mid-exam |
| `TimeChangeRequest` | A *delta*, not a new total |
| `PushType.EXAM_TIME_CHANGED` | The student's countdown moves by itself |
| `PushType.EXAM_LIVE_STATUS` | The teacher's view updates by itself |
| `TeacherLiveExam.fxml` + controller | Running sittings · time controls · who is inside |
| `ExamClockService.setOnExamClosed` | An automatic close also updates the teacher |

### Acceptance test 2.7, working

> *"מורה מגדירה תוספת של 15 דקות... תצוגת הטיימר אצל התלמידה קופצת אוטומטית"*

Two machines, nobody pressing anything on the student's, and her countdown jumps.
The test asserts the push arrives, that it carries her **new** remaining seconds,
and that her deadline really moved by exactly 15 minutes in the database.

### Decisions worth defending

**The change is a delta, not a new total.** A teacher thinks "give them another
quarter of an hour", not "make it 105 minutes". More importantly, students start
at different moments — moving each one's *own* deadline by the same amount is the
only change that treats them alike. A new total would silently give a late starter
more than an early one, or less.

**Requirement 47 is honoured exactly: the exam itself is untouched.** The change
lands on `exam_execution.allocated_duration` and on each running student's
deadline. `exam.duration_minutes` is unchanged, so the next class to sit the same
paper is unaffected — "השינוי הוא זמני ותקף רק לביצוע הנוכחי". There is a test
that reads the exam row back and asserts it still says 60.

**Taking time away is allowed, but not enough to end somebody instantly.**
Requirement 47 says *change*, not only *extend*. But a reduction that pushes a
student's deadline into the past would close her exam on the very next tick, with
no warning and no chance to hand in. That is refused, and the message names the
student it would have affected.

**A sitting stays on the teacher's screen while anyone is still inside**, even
after its window has closed. That follows directly from decision 8: a student who
started near the end keeps her full time, so the teacher must keep seeing her —
and must still be able to give her more.

**The teacher's view has no Refresh button either.** NFR 18 applies to her screen
just as much as to a student's, so starting and handing in are pushed to her — as
is an automatic close, via a callback from the clock service.

**Her selection survives a reload.** The list refreshes whenever any student acts;
losing the selected sitting each time would make the screen unusable in a room of
thirty.

### Verified — 33 automated checks, all passing

| Group | Result |
|---|---|
| Permissions: student, and a teacher who did not release it | 4/4 |
| The running list and the student list | 3/3 |
| Rejected changes: zero, absurd, and more than she has left | 3/3 |
| **Acceptance test 2.7: the push arrives with her new seconds** | 4/4 |
| **Her deadline moved by exactly 15 minutes in the database** | 1/1 |
| **Requirement 47: the allowance changed, the exam did not** | 4/4 |
| A student starting afterwards gets the new allowance | 1/1 |
| Taking time back, also pushed | 4/4 |
| **The teacher is told when a student starts and hands in** | 2/2 |
| Counts: started / still working / finished | 3/3 |
| A closed window stays visible while somebody is inside | 2/2 |

Regression: **320 checks** across the project, 12/12 screens load.

---

## Milestones 9 and 10 — marking, and the student's own results · 2026-07-29

**Covers:** SUC-9, SUC-10, מתווה scenarios 8 and 9, requirements 49–58 and 77.

### What was built

| Item | Notes |
|---|---|
| `Grade`, `QuestionFeedback`, `ExamStatistics` | Entities |
| `GradeDAO` | Automatic marking, manual changes, approval, factor, statistics |
| `GradingController` | SUC-9 |
| `ResultsViewController` | SUC-10 |
| `Grading.fxml`, `StudentResults.fxml` | The two screens |
| `MarkedExam`, `GradeChange`, `CommentRequest` | Protocol |
| `PushType.GRADE_APPROVED` | She is told when her mark is ready |

### Decisions worth defending

**Two grades are kept, not one.** `autoGrade` is what the system worked out;
`finalGrade` is what the teacher settled on. A manual change therefore stays
*visible as a change* rather than replacing the truth — which matters because
requirement 52 makes an explanation compulsory and somebody may later ask what was
altered. Tested: after changing 50 to 60, the automatic mark is still 50.

**Marking is computed in Java, not in one large join.** "Add up the points of the
questions she got right" is exactly what the course staff will ask about at the
demo, and it is far easier to read in a loop than buried in SQL. One exam at a
time, so nothing is lost.

**Marking is idempotent and lazy as well as eager.** A paper is marked at hand-in,
and the teacher's screen marks anything still unmarked — which covers a paper the
clock closed while the server happened to be restarting. Re-marking an already
marked paper does nothing, so it can never wipe a manual change.

**Statistics count approved marks only.** SUC-9 computes them after approval
(step 7 follows step 6) and acceptance test 3.7 counts approved exams. Including
drafts would make the average wander about while the teacher works.

**Statistics are recomputed, never cached.** That makes acceptance test 3.15 —
the average reflecting a manual change immediately — true by construction rather
than by remembering to invalidate something.

**The decile bucket for 100.** A plain `grade / 10` puts 100 in an eleventh bucket
that does not exist. `bucketFor` clamps it into 91-100, and there are tests for
0, 10, 11 and 100 specifically.

**"That exam does not exist" is the wording for another student's paper.** The
same message as for a genuinely missing one, so trying submission numbers reveals
nothing. This is acceptance test 4.6 rewritten for a desktop client — the original
assumed a browser and a URL to tamper with.

**Unapproved marks are blanked before the list is sent.** Requirement 53 says she
sees nothing until approval; the list still shows that the exam exists and is
waiting (acceptance test 4.2), but the numbers are stripped on the server rather
than merely hidden by the screen.

### Found by the validation loop

Two faults, both in the tests rather than the product, and both worth recording
because they are the kind that hide real ones.

**1. An assertion that depended on run order.** `RESULTS_MINE` was asserted to
return exactly one exam. Run after milestones 7 and 8, the same student had sat
others — so the *correct* behaviour failed the test. Now it looks for the exam it
cares about instead of counting.

**2. A test that could only ever run once.** It released with a hard-coded code,
and codes are unique for ever, so a second run failed at setup with an unhelpful
`NullPointerException`. It now asks the server for a free code — which also
exercises that endpoint. Verified by running it twice in a row without a reset.

### Verified — 65 automated checks, all passing

| Group | Result |
|---|---|
| **Automatic marking: 2 of 4 right gives 50; all right gives 100** | 5/5 |
| Blanks count as wrong; the marker sees the right answers | 2/2 |
| **Acceptance test 3.11: a paper still being sat cannot be marked** | 2/2 |
| Permissions, including **requirement 55 — no statistics for a student** | 5/5 |
| **Tests 3.4 and 3.6: no reason refused, 105 and −5 refused** | 4/4 |
| A proper change keeps the automatic mark and the reason | 4/4 |
| Comments, per question and overall | 2/2 |
| **Test 4.2: nothing reaches her before approval** | 3/3 |
| Approval, and the push that tells her | 2/2 |
| **Tests 4.3, 4.4, 4.10, 4.11: right answers, comments, no private notes, her time** | 6/6 |
| **Test 4.6 rewritten: another student's paper is refused, and says nothing** | 2/2 |
| **Tests 3.7, 3.8, 3.14: average, median, decile buckets including 100** | 10/10 |
| **Test 3.15: statistics follow a change at once** | 1/1 |
| **Test 3.12: changing after approval tells her again** | 2/2 |
| **Requirement 77: a factor, and 100 stays 100** | 4/4 |
| **Test 3.10: approve everything at once** | 3/3 |
| Test 4.12: a student who has sat nothing | 2/2 |

Regression: **385 checks** across the project, 14/14 screens load.

---

## Fix — a sitting with a student still inside looked empty

**Reported from the screen:** *"even an exam that 1 student sat — when I press it
as a teacher there is no student there."*

### What was wrong

Two lists were counting two different things.

- The **sittings** list showed `numStarted` — everybody who *started*, derived in
  `ExecutionDAO` by counting rows in `student_exam`.
- The **students** list came from `GradeDAO.findByExecution`, which reads
  `FROM grade g JOIN student_exam s`. A student who is **still sitting** has no
  row in `grade` yet, so the inner join silently dropped her.

A sitting with one student still working therefore announced "1 sat it" and then
opened onto an empty list, with nothing on screen to explain the gap.

### Why the tests did not catch it

They asserted it. `M9Test` contained:

```java
check("the student still sitting has no mark", marks.size() == 2);
```

Three students had started; the check expected two rows and got two. The
expectation itself was wrong, so 65/65 passed while the defect was live. This is
the fourth defect this project has found by clicking rather than by testing, and
the first one where the test actively defended the bug.

### The fix

`GradeDAO.findByExecutionIncludingUnmarked` drives from `student_exam` with a
**left** join onto `grade`, so everybody who started is returned. `Grade` gained a
`marked` flag, false for a student with no mark yet. The teacher's list shows her
as *"still sitting · nothing to mark yet"*, and clicking her says so — which is
the message acceptance test 3.11 asks for. `findByExecution` is unchanged and
still returns marks only; `approveAll` still uses it.

The sitting label now reads **"3 sat it · 2 handed in · 1 still sitting"**, so the
two numbers can never silently disagree again. `M9Test` asserts that
`numStarted` equals the length of the student list.

### Not a bug: one exam number, several codes

Also reported: *"two exams with the same number (080101) but with different
codes"*. That is correct and required. An exam is written once and handed out
many times — a different class, a re-sit — and **each hand-out is its own sitting
with its own code, its own clock and its own students** (system description
§2.1–2.2, מתווה scenario 5). The code is what tells them apart, so it now leads
the row and the course name sits on its own line.

The particular sittings on screen (`M7AA`, `M7BB`, `M7CC`, `M8AA`) are leftovers
from automated test runs, not teaching data.

### Test harnesses made re-runnable

`M6Test`, `M7Test` and `M8Test` hard-coded their execution codes. Codes are unique
for ever, so each suite ran exactly once per database and every later run died at
setup with *"that code is already in use"* — failures that looked alarming and
meant nothing. Each run now picks a two-character prefix no sitting is using and
builds its codes from it.

One code was hidden in a single-quoted SQL string (`WHERE execution_code =
'M7BB'`) and was missed by the first sweep. The `UPDATE` then matched no rows, the
exam window was never moved into the past, and two checks failed — correctly. The
statement is now parameterised and **asserts that it changed one row**, so a
silent no-op cannot masquerade as a passing test again.

### Verified

| Suite | Result |
|---|---|
| M2 | 48/48 |
| M3 | 34/34 |
| M4 | 43/43 |
| M5 | 32/32 |
| M6 | 76/76 |
| M7 | 55/55 |
| M8 | 33/33 |
| M9 | 74/74 (was 65; 9 new checks on the marking list) |
| **Total** | **395 checks** |
| Screens | 14/14 load |

Run **twice back to back with no database reset**, identical both times.

---

## Change — one button on the marking screen, not four

**Asked for:** *"there are the save mark, save comment, and publish buttons: do we
need all of them? I think it is best if we just have the publish button and it
will publish the mark and the comment (if there is one). is it according to the
directions?"*

### The honest answer to "is it according to the directions?"

**Partly.** The מתווה is loose — scenario 8 lists only three things (the system
marks, the teacher approves, the teacher may change with a reason) and never
mentions buttons. It does not mention comments at all.

The **acceptance tests are specific, and they do name a Save button**:

- **3.3** — *"...presses **שמור**"*, and the expected end state is only that the
  mark and reason are stored. No approval.
- **3.4** — same flow with the reason box empty, again pressing **שמור**.
- **3.2** — separately *"אשר ציון סופי"*.
- **3.5** — *"שומרת ומאשרת"* — saves **and** approves, two verbs.

So the submitted document does describe Save as a step distinct from Approve.
This was put to the customer with that cost stated, and the decision was **one
button**. Acceptance tests 3.3, 3.4 and 3.5 are redlined in
`docs/03_document_updates.md`.

### What was wrong with four buttons

A ten-question paper carried **thirteen** buttons: one per question, plus save the
mark, save the comment, and approve. Worse than clutter — a teacher could press
Approve while a comment she had typed sat unsaved on screen, and the student would
be told her mark without it.

### What replaced them

One `PublishRequest` carrying the mark, the reason, the overall comment and a
comment per question. `GradingController.publish` **checks everything before
writing anything**, so a refused publish leaves the paper exactly as it was.

That property is the reason the merge is safe, and it is what `M10Test` §2 exists
to prove: after a mark is moved with no reason, the mark is still 50, it is not
published, and the comment that arrived with the bad request was **not** stored.

Requirement 52 is untouched — the explanation is still compulsory when the mark
moves, just checked at the moment of publishing. `GRADING_CHANGE`,
`GRADING_GENERAL_COMMENT` and `GRADING_QUESTION_COMMENT` remain on the server as
single-step operations; the screen no longer uses them.

The button reads **"Approve and publish"**, or **"Update and publish"** on a paper
already published — which is acceptance test 3.12.

### A check of mine that asserted nothing

`M10Test` §5 originally read:

```java
check("she is NOT shown the reason for the change",
        x.getManualChangeExplanation() == null || !x.getManualChangeExplanation().isEmpty());
```

`null || not-empty` is true for very nearly every value, so it passed regardless.
Its label was also wrong: the student **does** receive the reason once the mark is
approved — her screen simply does not print it. No requirement says to hide it,
and requirement 52 keeps it precisely so a change can be accounted for. The check
now asserts what is actually true rather than inventing a rule.

### Verified — 43 new checks

| Group | Result |
|---|---|
| **Test 3.2: publish as marked, no reason needed, none invented** | 4/4 |
| **Test 3.4: mark moved with no reason refused, and NOTHING written** | 6/6 |
| **Test 3.6: 105, −5, 101 refused; mark still 50 and unpublished** | 5/5 |
| **One press stores mark + reason + overall comment + all 4 question comments** | 7/7 |
| The student sees all of it from that one press | 4/4 |
| **Test 3.12: publishing again says "updated" and tells her** | 5/5 |
| Blanking a comment clears it; the mark and reason survive | 4/4 |
| Permissions: another teacher, and the student herself | 3/3 |
| **Test 3.11: a paper still being sat cannot be published** | 2/2 |
| A paper that does not exist; an empty request | 2/2 |

Regression: **438 checks** across the project, run **twice back to back with no
database reset**, identical both times. 14/14 screens load.

---

## Milestones 11 and 12 — the teacher's histogram, and the principal's browse

### First: there are no acceptance tests for either

The submitted `סעיף 4 - בדיקות קבלה.docx` covers **SUC-3, SUC-7, SUC-9 and SUC-10
only** — tests 1.x to 4.x. SUC-11 and SUC-12 have none. So unlike every milestone
before it, there was nothing to satisfy except the מתווה and the numbered
requirements, and those are what each check in `M11Test` is written against:

| Source | What it says |
|---|---|
| מתווה 10 | *"מורה יכולה לצפות בתוצאות בחינות **שכתבה**... בטבלה וכן בצורת היסטוגרמה"* |
| Requirement 59 | *"כל הבחינות שכתבה (**גם אם בוצעו על-ידי מורות אחרות**)"* |
| Requirement 54 | average, median, decile distribution |
| מתווה 11 | *"מנהלת יכולה לראות את מאגר השאלות, המבחנים ותוצאות המבחנים"* |
| Requirement 62 | all data — questions, exams, results — **בקריאה בלבד** (read-only) |

### Demo data first, because neither milestone could otherwise be shown

`SeedRunner` creates people and courses and stops; an empty question bank is the
honest starting point for a school. But a histogram of no marks is a blank box.

`DemoContentSeeder` adds 80 real questions across all eight courses, 9 exams, 7
sittings and 78 marked papers. **The marks are computed by the real
`GradeDAO.autoGrade` from actual written answers**, not inserted — a hand-written
mark would drift from the paper it claims to describe, and the first person to
open one at the demo would see ticks that disagree with the number.

The mid-term spread fills **nine of the ten deciles** (average 64.4, median 67.5,
range 15–100), so the histogram is worth looking at rather than one tall bar.

### Requirement 59 made demonstrable, not just implemented

Noa Levi writes two Plane Geometry exams. She releases one; **Maya Cohen releases
the other**. So:

- Noa's report list is `010101, 020101, 040101` — all three hers.
- Maya's is `020201, 030101, 050101` — no overlap.
- Exam `020101` appears under Noa with *"released by Maya Cohen"* against it.

That is the requirement in one line on screen, and `M11Test` asserts it rather
than assuming the data happens to be shaped that way.

### A different permission rule from marking, deliberately

`GradingController` lets the teacher who **released** a sitting mark it — those are
her students. `TeacherReportController` lets the **author** look — it is her paper.
The two do not conflict because this path is entirely read-only. A teacher who
neither wrote nor ran an exam is refused, and told which it is.

### Read-only that does not depend on hiding buttons

`PrincipalController` has no method that writes. `M11Test` §8 checks the principal
is refused when she tries to add a question, save an exam, approve one, release
one, list papers to mark, or publish a mark — six different ways in, all shut.
It also checks a **student** cannot use the principal's requests to reach class
statistics, which would otherwise be a way round requirement 55.

### The histogram is drawn by hand

JavaFX has a `BarChart`, but it brings axes, a legend, animation and its own
stylesheet, and fighting all four into the look of the application costs more than
ten rectangles are worth. An empty bucket keeps a hairline so ten columns are
always visible — *"nobody scored 0–10"* must not look like a broken chart.

### Three faults the validation loop caught — all in tests, one hiding a real bug

**1. A tautological check that hid a real defect.** This passed either way:

```java
check("with the answers, so she can read the paper properly",
        bank.stream().anyMatch(q -> !q.getAnswers().isEmpty())
     || bank.get(0).getAnswers().isEmpty());
```

Chasing it found the defect: `QuestionDAO.findAll` deliberately omits answers, and
the principal's detail pane drew that empty list — so **she saw a question with no
options under it at all**. Fixed with `PRINCIPAL_QUESTION_GET`, which fetches one
question complete, matching how the exams tab already worked. The check now
asserts both halves: the list is light, and one question comes back with four
answers of which exactly one is correct.

**2. A padded literal the code sweep could not see.** `dao.findByCode("  k7m2  ")`
— the earlier sweep that made execution codes per-run matched `"K7M2"` but not
`"  k7m2  "`. It kept passing only while `K7M2` still happened to exist from an
older run. Once the database was reset it failed correctly.

**3. "Not enrolled here" is not "has sat nothing".** `M9Test` asked for a student
not enrolled in its course and assumed she had no results. True on an empty
database; false once the demo data gave her exams in her own courses. It now asks
for a student with no attempt at any exam anywhere.

### Verified

| Suite | Result |
|---|---|
| M2 | 48/48 |
| M3 | 34/34 |
| M4 | 43/43 |
| M5 | 32/32 |
| M6 | 76/76 |
| M7 | 55/55 |
| M8 | 33/33 |
| M9 | 75/75 |
| M10 | 43/43 |
| **M11 (new)** | **57/57** |
| **Total** | **496 checks** |
| Screens | 16/16 load |

Run **twice back to back with no database reset**, identical both times.

---

## Milestone 13 — the statistical reports, via Factory and Strategy

מתווה scenario 12: *"ניתן לראות ממוצע, חציון והתפלגות עשרונית של בחינות ולהשוות
בין: בחינות שונות של אותה מורה, בחינות שונות של אותו קורס, בחינות שונות של אותה
תלמידה"* — the same three figures, compared across a teacher's exams, a course's
exams, or one student's exams. Requirement 63 says the same.

### The two patterns the class diagram promised

This is where **Factory** and **Strategy** were committed to in Assignment 2, and
requirement 64 — *"הפקת דו"חות חדשים תדרוש עבודת פיתוח מינימלית"* — is the reason
they are here rather than a `switch`.

| Piece | What it does |
|---|---|
| `ReportStrategy` | one interface; three implementations, one per comparison |
| `TeacherReportStrategy` | exams by author — requirements 59 **and** 63 |
| `CourseReportStrategy` | exams by course |
| `StudentReportStrategy` | one student's marks against the classes she sat with |
| `ReportFactory` | type → strategy, held in an `EnumMap` |
| `ReportController` | holds a strategy and calls it — **no `if` chain anywhere** |

**A fourth report costs one enum value and one class.** Not the controller, not
the screen, not the other strategies. The screen in particular knows nothing about
what the reports are: it asks the server which ones this user may run, asks the
chosen report what it can be run *about*, and draws what comes back. That is why
`listSubjects()` sits on the strategy rather than the controller — a new report
arrives carrying its own chooser.

### The arithmetic moved

`ExamStatistics.over(marks)` now holds the average/median/decile calculation, and
`GradeDAO` delegates to it. It moved when the reports became a fourth caller — one
copy of the maths means the class average, the exam average and the report average
cannot disagree.

### Who may run what

- **Principal** — all three, about anyone (requirement 63).
- **Teacher** — one, about herself (requirement 59: *"כל הבחינות שכתבה"*).
- **Student** — none (requirement 55).

The teacher's restriction is enforced by **overwriting the key she sends** with her
own id, not by refusing her. Asking for a colleague's report returns her own, so
there is nothing to probe for. `M13Test` §5 asserts exactly that: it asks for Maya
Cohen's report as Noa Levi and gets Noa Levi's back.

### The by-student report is shaped differently, on purpose

The other two compare classes with classes. Here every row is a single mark, and
deciles over one mark would be a chart with one bar. So each row carries the
**class** figures for the sitting she was in with **her** mark highlighted against
them — because 70 means one thing in a class averaging 55 and another in a class
averaging 85.

Even on the client this is data-driven, not report-driven: a `ReportLine` either
carries a highlight or it does not, and `ReportsController` never asks which report
produced it.

### Two things the validation loop caught

**1. The demo data made the report say nothing.** The seeder handed marks out by
position in an alphabetical list, so the same girl was bottom of every class she
sat in — and the "compare one student's exams" report was a flat line 25 to 39
points below average every time. It proves the query runs; it demonstrates
nothing. Marks are now rotated by sitting, and Avigail Barak reads **−9.4, −5.5,
+6.3, +11.3** in date order — a student improving, which is what the report is for.

**2. A suite whose total drifted.** `M13Test` reported **104 checks one run and 114
the next**. Not a failure — `checkFigures` called `check()` once per row, and the
by-course report legitimately picks up more exams as the other suites create them.
But a total that moves on its own is useless for spotting a regression, which is
the one thing running the suite twice is meant to detect. The per-row loops are now
single `allMatch` checks over all rows, and the count is 82 every time.

### Verified

| Suite | Result |
|---|---|
| M2 | 48/48 |
| M3 | 34/34 |
| M4 | 43/43 |
| M5 | 32/32 |
| M6 | 76/76 |
| M7 | 55/55 |
| M8 | 33/33 |
| M9 | 75/75 |
| M10 | 43/43 |
| M11 | 57/57 |
| **M13 (new)** | **82/82** |
| **Total** | **578 checks** |
| Screens | 17/17 load |

Run **twice back to back with no database reset**, identical both times. The
factory is tested directly as well as through the screen — every `ReportType` has a
strategy, and each strategy is checked to claim the type it is registered under, so
a crossed wiring cannot pass as merely "not null".

---

## Milestone 14 — the course study bot

SUC-13, SUC-14 and SUC-15 / מתווה scenarios 13 and 14. Three use cases, because
they are one feature: a teacher builds the bot, a student uses it, and both look at
what was asked.

### The API key is not in this repository, and cannot be

Read from `%USERPROFILE%\.hsts\config.properties` — outside the project folder, so
no `git add -A` can publish it and no `.gitignore` mistake can expose it. The key is
never logged, never put in an exception message, and never sent to a client. The
failure messages deliberately name the endpoint **without** its query string,
because that is what carries the key.

**The key is not set yet.** `gemini.api.key` is missing from the file, so the bot
answers with *"The study bot is not set up on the server yet"* until it is added.
That message is deliberately distinct from *"the bot had no answer"* — one is the
school's problem and the other is the question's.

### Requirement 69 as a boundary, which is what makes it testable

*"הבוט יממש API חיצוני קיים... אין צורך לפתח בוט חדש"* — use an existing external
API. So the whole of the outside world sits behind `IStudyBotService`, exactly as
`IUserManagementSystem` does for the user directory.

That is not architecture for its own sake. **All 81 checks run with a stub in place
of Gemini**, so every rule — who may ask, when the bot is unavailable, what is
stored, what a teacher may see — is verified without the network, without a key, and
without spending anything. A suite that calls a paid API is a suite nobody runs
twice, and running twice is how this project catches order-dependence.

The stub can also be told to *fail*, which is the only honest way to test
requirement 72's "no suitable answer" message.

The real adapter is not left untested either: its JSON escaping, request shape and
reply reading are checked directly, because those are the parts that break silently
— a subtly malformed body comes back as a 400 that looks exactly like a bad key.

### Word is read properly. PDF is best-effort and says so.

Requirement 68 allows PDF, Word or typed text. A bot is given **text**, so a file
has to become text — and it happens when the file is *added*, so an unreadable one
is refused while the teacher is still looking at the screen.

- **`.docx`** is a ZIP with the text in `word/document.xml`, so it is read exactly
  with nothing but `java.util.zip`. A `.doc` is refused by name with advice, because
  it is not a ZIP and guessing would produce nonsense.
- **PDF** text is normally Flate-compressed with its own font encodings, and reading
  that properly needs a library such as PDFBox. This project has three dependencies
  and a fourth was judged not worth it for one requirement. So the uncompressed text
  operators are read, and when that yields nothing the file is **refused** with
  *"paste the text in as free text, or upload a .docx version instead"*.

A PDF accepted and stored as gibberish would make the bot worse while looking like
it worked. The limit is written down rather than hidden.

### Requirement 71, and why it is scoped to the course

*"בזמן ביצוע בחינה פעילה, הבוט לא יהיה זמין לתלמידות הנבחנות באותו קורס"* — so a
girl halfway through a geometry paper cannot ask the geometry bot. The poetry bot is
no help to her and no threat, and the requirement says *"באותו קורס"*.

Derived from the live `student_exam` rows rather than from a flag somebody has to
remember to clear, so it **lifts by itself** the moment she hands in — and also if
the clock closes her exam, or the server restarts. Tested both ways: refused while
inside, allowed immediately after submitting.

### Requirement 75: anonymised on the server, not on the screen

The teacher sees how many questions, how many students *as a count*, which wordings
repeat, and the recent questions and answers. `BotConversation.anonymise()` is
called in the **DAO**, before the object goes onto the wire. A screen that simply
does not draw the name would leave it sitting in the client's memory, which is not
the same as the teacher not having it. The test asserts every row comes back with a
null name **and** a null id.

### Small decisions worth defending

- A bot **starts switched off** and **cannot be switched on with nothing to read** —
  requirement 70 would otherwise let a student interrogate a bot that knows nothing.
- Removing the last piece of material **switches the bot off by itself**, for the
  same reason.
- One bot per course, enforced by a UNIQUE key on `bot.course_code`. That is
  requirement 67 in the data model: a second teacher must add to the existing bot,
  so there must not be a second bot for her to add to instead. The refusal names the
  existing bot and who made it.
- The material list shows **who added each piece**, which is how requirement 67
  becomes visible rather than merely permitted.
- Context sent to the model is capped at 30,000 characters. Without the cap a large
  upload would push the question itself out of the request, and the failure would
  look like the bot being stupid rather than the request being too big.

### The validation loop caught one fault — in the test, and the product was right

`M14Test` passed 81/81 alone and failed three checks when run after the other
suites. The cause: M7 and M8 deliberately leave students **mid-exam in course 01** to
test the still-sitting state, and this suite picked "the first few enrolled
students" — which was one of them. Requirement 71 then blocked her from the bot,
**correctly**, and three checks failed for a reason that had nothing to do with what
they were testing.

The product behaved exactly as specified. The helper now asks for students who are
enrolled *and not currently inside one of that course's exams*, and throws a clear
error rather than a `NullPointerException` if it cannot find enough.

This is the third time this project has hit the same shape of mistake: a test
assuming a clean database. The first was M6/M7/M8's hard-coded execution codes, the
second was M9's "not enrolled here means has sat nothing".

### Verified

| Suite | Result |
|---|---|
| M2 | 48/48 |
| M3 | 34/34 |
| M4 | 43/43 |
| M5 | 32/32 |
| M6 | 76/76 |
| M7 | 55/55 |
| M8 | 33/33 |
| M9 | 75/75 |
| M10 | 43/43 |
| M11 | 57/57 |
| M13 | 82/82 |
| **M14 (new)** | **81/81** |
| **Total** | **659 checks** |
| Screens | 19/19 load |

Run **twice back to back with no database reset**, identical both times.

**Still to do before this milestone can be demonstrated live:** the Gemini API key
must be added to `%USERPROFILE%\.hsts\config.properties` as `gemini.api.key=...`.
Everything else works now.

---

## Milestone 14, follow-up — testing the real API key

The key was added to `%USERPROFILE%\.hsts\config.properties`. Testing it found three
faults, none of them the key.

### The key was fine. The model was wrong — twice.

`GeminiProbe` made one real call and got **HTTP 429**, which
`GeminiStudyBotService` reports to a student as *"asked too many questions just
now"*. That wording is right for her and useless for diagnosis, so `GeminiDiag`
printed the raw reply. (Safe to print: the key travels in the URL's query string,
never in the body.)

| Model | Result |
|---|---|
| `gemini-2.0-flash` | **429** — `"limit: 0"`, no free-tier quota for a new key |
| `gemini-2.5-flash` | **404** — *"no longer available to new users"* |
| `gemini-flash-latest` | **200** — answered, resolving to `gemini-3.6-flash` |

The `models` listing returned **200**, which is what proved the key itself was
valid and authenticated.

**Fixed:** the default is now `gemini-flash-latest` — an alias, not a pinned
version. Named versions age out, and a demo that stops working because a model was
retired fails for no reason of ours. Still overridable with `gemini.model`.

**Also fixed:** a 429 carrying `limit: 0` is a *structural* zero, not a busy
moment. Telling a student to "wait a moment" would leave her waiting for ever, so
that case now names the model and points at the config file.

### A latent bug the live reply exposed

The real response carried a `thoughtSignature` beside the answer — harmless here,
but it showed that the current flash models are **thinking models**. The same family
can return a separate part marked `"thought": true` whose `text` is the model's
private reasoning, and `extractText` took the *first* `"text"` it found. On such a
reply a pupil would have been shown the reasoning instead of the answer.

`extractText` now skips thought parts. Four checks cover it, including the
`thoughtSignature` shape that actually arrived. **No static example would have
caught this** — it took a real call.

### The answer was unreadable on a school screen

The first working answer came back as:

> To solve a linear equation like `$5x + 3 = 23$` … **`$x = 4$`**

LaTeX and markdown. A JavaFX label draws that literally — dollar signs, asterisks
and all — so a pupil would see punctuation where the maths should be. Formatting
the client cannot render is worse than no formatting.

The instructions now forbid markdown and LaTeX and ask for plain numbered steps.
Verified by a second real call: plain text, correct working, and it still said
*"This specific equation is not in the course material, but I can give you general
guidance using the linear equation rule from the course"* — which is the prompt
staying inside the teacher's material, as intended.

### Twelve people shared a name

Not a bot fault, found while reading the end-to-end output: it announced *"signed in
as Noa Levi"* — a **student**, when `teacher1` is also Noa Levi.

`SeedRunner.personName` computed `first[i % 20] + last[(i/20 + i) % 14]`, which
collided for twelve of the 53 users, each time putting a member of staff and a pupil
under one name. Harmless to the code and thoroughly confusing on screen: somebody
reading a class list could not tell pupils from colleagues. Names are now handed out
from a set, so nobody shares. Still reproducible — the same run makes the same
people.

### Two probes kept out of the regression, deliberately

`GeminiProbe` and `BotE2E` make **real** API calls, so they are run by hand and are
not part of the suite. `M14Test` still runs entirely against a stub: the regression
must not need the network, a key, or any spend.

`BotE2E` proves the wiring the stub cannot: teacher creates a bot, feeds it the
question bank and her own notes, switches it on; a student on a client connection
asks and gets a genuine answer that **follows her teacher's notes** — *"do the same
thing to both sides… add or subtract first, then divide… check by putting the answer
back in"*. Then requirement 74 reads it back, and requirement 75 shows the teacher
the usage with no identity attached.

### One more test fault, same family as before

`M14Test` began failing on its exam step after being run a few times: `max_attempts`
is 1, so a student who sat that sitting in an earlier run cannot start it again. The
sitting is now worked out **before** the students are chosen, and the query excludes
anybody who has already sat it — with a message saying so rather than a
`NullPointerException`.

That is the **fourth** time this project has hit a test assuming a clean database.
The pattern is worth stating in the report: hard-coded execution codes, "not
enrolled here means has sat nothing", "the first enrolled student is free to ask the
bot", and now "she has an attempt left".

### Verified

| Suite | Result |
|---|---|
| M2–M11, M13 | unchanged, all passing |
| **M14** | **85/85** (was 81; 4 new on thought parts) |
| **Total** | **663 checks** |
| Screens | 19/19 load |

Run **twice back to back with no database reset**, identical both times. The live
API was exercised separately: `GeminiProbe` and `BotE2E` both succeed.

---

## Milestone 14, follow-up 2 — several bots per course, deleting, and reading an exchange

Four changes asked for by the customer.

### 1. A teacher with more than one course, visibly

Three teachers already taught two courses each; nothing on screen made that
apparent because no course had a bot. The seeder now gives **Yael Peretz** a bot on
Mechanics *and* one on Electricity, so her list proves it is per **teacher**, not
per course. The bot list is keyed on the course name rather than the bot name, so
two courses group at a glance.

### 2. Several bots per course, only one active

**Schema.** `bot.course_code` was `UNIQUE`. It is now a plain index, and
`SchemaManager.migrate` performs the change on databases that already exist —
`CREATE TABLE IF NOT EXISTS` does nothing to a table already there, so a changed
definition would otherwise only reach a fresh database.

The migration creates the replacement index **before** dropping the unique one.
The first attempt did it the other way round and MySQL refused: *"Cannot drop index
'course_code': needed in a foreign key constraint"* — `fk_bot_course` needs an index
on the column and will not give up its last one.

**The "only one active" half is in code, not the schema.** It is a condition on a
*subset* of rows, which MySQL cannot express as an index. Putting it in
`BotController` also lets it explain itself: switching one bot on switches the
course's others off and **says which one it turned off**.

`BotDAO.deactivateOthers` does it in a single `UPDATE`, not a read-then-write loop,
so two teachers pressing "Turn on" at the same moment cannot leave two of them on.

**The student is unaffected.** `findActiveByCourse` gives her the one that is
switched on, and she sees one row per course — she has no business choosing between
a teacher's drafts. With none switched on she is told how many the course has.

**Requirement 67 still holds**, and this is written up in
`docs/03_document_updates.md` §8: the requirement grants a colleague the ability to
add to an *existing* bot; it does not forbid a second one. Two bots on one course
may not share a name, or the teacher's own list becomes unreadable.

### 3. Deleting a bot

A `Delete this bot...` button, styled as the destructive action it is. Two steps:
the server is asked **how many stored questions would be destroyed**, and the
confirmation names the number.

**This breaks requirement 73, knowingly.** *"המערכת תשמור את השאלות שנשלחו לבוט ואת
התשובות שהתקבלו"* — the system keeps the questions and answers, and deleting a bot
throws its away. The customer asked for a plain delete; the alternative reading
(refuse to delete anything ever used) would leave a teacher permanently stuck with a
bot created by mistake, and no requirement asks for that either.

Narrowed rather than ignored, and written up in `docs/03_document_updates.md` §7:
the count is real and comes from the server, deactivating remains the lossless way
to take a bot out of service, and nothing else in the system deletes a conversation.

`bot_conversation` has no `ON DELETE CASCADE` — its foreign key would refuse the
delete — so the three tables are cleared in order inside one transaction. A failure
leaves the bot whole rather than half-deleted.

### 4. The teacher can read a whole exchange

The usage list showed the question **and** a 300-character slice of the answer on
every row. Answers run past twenty lines, so the list could not be scanned — and
she is looking for *what was asked*.

Rows now carry the question and the time only. **Clicking one opens the full
exchange underneath**, collapsed until then. The full text was always being sent;
what changed is that the list stopped trying to show all of it at once.

Still no name anywhere: the server strips it before the object reaches the wire
(requirement 75), so there is nothing for the panel to reveal.

### Four order-dependent checks in M14Test, three of which passed by luck

Adding these tests exposed the suite's own assumptions. All four are the same
family this project keeps meeting — **a test that assumes a clean database** — and
one was worse than a failure:

**Non-deterministic.** *"a failed question is NOT stored"* and *"each sees only her
own"* compared **totals** — `before == 1`, `size() == 2`. A student's bot history
survives between runs, so whether the total is 1 depended on which student got
picked and what she did last time. They failed one run and passed the next. Now
they measure the *change* and check by *content*.

**Stale precondition.** Section 11 expected the first bot to be ON so it could watch
it being displaced — but section 10 had already stripped its material, which
switches it off automatically. It would have passed while proving nothing; it now
restores material and switches it on first.

**Set up on a deleted bot.** Section 15 configured the bot that section 12 deletes,
so both requests failed silently and the refusal came from "no bot is switched on"
instead of "the service is not configured". It now uses a bot that still exists.

**Section 2 asserted the rule that was being changed.** It checked that a second bot
per course is *refused*. That was correct until this change and had to be rewritten
rather than deleted: what survives is that two bots cannot share a name.

### Verified

| Suite | Result |
|---|---|
| M2–M11, M13 | unchanged, all passing |
| **M14** | **133/133** (was 85) |
| **Total** | **711 checks** |
| Screens | 19/19 load |

Run **twice back to back with no database reset**, identical both times, and M14
alone three times running to confirm the non-determinism is gone.

---

## Milestone 14, follow-up 3 — a readable exchange, and nothing waits for a click

### 1. The question and the answer were running together

The teacher's exchange panel put the question in a heading and the answer straight
underneath in plain text. With answers running past twenty lines, the reader could
not see where the question ended and the answer began — it read as one continuing
block.

Both halves are still in **one section**, because she is reading a single exchange.
Each half now has its own labelled, tinted block with a coloured left rule and a
divider between them: **THE STUDENT ASKED** on a grey block, **THE BOT ANSWERED** on
a blue one. The boundary is visible without splitting them into two cards.

### 2. Nothing waits for a click any more

NFR 18 forbids manual refresh. Five screens had no push handling at all, so a
change made elsewhere sat invisible until the user left the screen and came back.

Three new push types, and the fan-out is by **course membership**:

| Event | Who is told | Why |
|---|---|---|
| `BOT_CHANGED` | the course's **teachers** | requirement 67 lets a colleague edit the bot, so two teachers can have it open at once |
| `BOT_AVAILABILITY_CHANGED` | the course's **students** | requirement 60 flips requirement 70 under them |
| `RESULTS_CHANGED` | the exam's **author**, the **releasing** teacher, the **principal** | requirements 59 and 62 give all three the figures |

Every bot change pushes: created, material added or removed, switched on or off,
deleted — **and a student asking a question**, because that moves the usage figures
her teacher is looking at.

**That last one never names her.** Requirement 75 reaches into the push itself: the
message is *"A new question was asked of 'Mechanics helper'."* and the test asserts
the student's name and id appear in neither.

Screens that now follow by themselves: bot management, ask-the-bot, marking,
teacher results, principal browse. Each keeps the user's place across a reload —
the selected bot, the open exchange, the chosen sitting — so a colleague's edit does
not throw away what she was reading.

**No polling anywhere.** The server speaks when something changes; the only wait in
the whole system is the exam clock, which has to tick.

### Faults found, and every one was the test

Five, and the product was right each time. That is worth saying plainly: the system
refused things it was supposed to refuse, and the checks were written against
assumptions that had quietly stopped holding.

**1. Two accounts, one session.** The push test opened a second connection for
`teacher1` and a second for `principal` — both already signed in by the suite. NFR
16 forbids the same user being connected twice, so both logins were refused, the
publish failed as "not signed in", and no push was ever sent. The check failed while
the push worked perfectly. It now reuses the connection it already holds.

**2. The password suffix follows the role.** The same test guessed `"!T"` for
whoever released a sitting, and the Algebra sitting was released by
**coordinator1**, whose password ends `"!C"`.

**3. Section 2 asserted the rule that had just been changed** — one bot per course.
Rewritten rather than deleted: what survives is that two bots cannot share a name.

**4. Course 01 hit 99 exams.** `M10Test` died with a bare `NullPointerException`.
The cause was the documented ceiling of the 6-digit exam id format working exactly
as designed — `generateExamId` throws past 99, `saveExam` returned null, and the
test dereferenced it. Both `M9Test` and `M10Test` now say so in words and name the
remedy. **The suites accumulate; the demo data needs resetting periodically, and
that is what `resetAndSeed` is for.**

**5. One unreproduced flake.** `M7Test` failed once, in one sequence run, and could
not be reproduced in nine further attempts. The only genuinely time-dependent wait
in the project is the twelve seconds it allows for the exam clock to close an exam
by itself. It is reported honestly rather than dismissed: the wait is now thirty
seconds, and — more usefully — a miss no longer takes the rest of the suite with it.
It used to cast a null payload straight into a `StudentExam`, so one late push hid
the seventeen checks behind it behind a single "harness threw".

### A real product weakness the accumulation exposed

Chasing the M14 failure found something worth fixing on its own account.

`buildContext` filled the 30,000-character budget **in order and stopped**. Course
01's bank had grown to 328 questions — about 33,000 characters by itself — so the
teacher's own notes, her colleague's notes and her uploaded document reached the bot
as **nothing at all**, silently. The answers would simply have been worse, with
nothing on any screen to say why.

Every source now gets a share: if the material does not fit, each is allotted an
equal portion and whatever the small ones do not use is handed back to the large
ones. Nothing is starved out, and each shortened source says so in the text.

This would happen on a real course, not only on a debris-filled test database — a
school with a few hundred banked questions is entirely ordinary.

### Verified

| Suite | Result |
|---|---|
| M2–M11, M13 | unchanged, all passing |
| **M14** | **153/153** (was 133; 20 new on pushes and context sharing) |
| **Total** | **731 checks** |
| Screens | 19/19 load |

Run **three times back to back with no database reset**, identical each time.

---

## Milestone 15 — the six derived requirements

Left until last on purpose, as the plan set out. Five were built here; the sixth
was built with milestone 9 and is **re-checked rather than assumed** — "it was done
earlier" is not evidence.

| # | What it asks | State before | Now |
|---|---|---|---|
| **19** | a coordinator edits her subject's questions | explicitly deferred, with a comment saying so | built |
| **39** | three wrong codes, then ten minutes out | table existed, nothing used it | built |
| **43** | a popup at nine tenths of the time | nothing | built |
| **61** | the teacher opens an extra attempt | attempts capped, no way to grant one | built |
| **76** | signed out after inactivity | last-activity recorded, nothing swept | built |
| **77** | a factor after approval | done in milestone 9 | re-verified |

### 19 — the coordinator's reach is wider than her teaching

*"רכזת המקצוע תוכל לערוך שאלות של אותו המקצוע שמרכזת"* — **every course in her
subject**, not only the ones she happens to teach. Noa Katz coordinates Mathematics
and teaches Algebra; she may now correct a Plane Geometry question.

Two halves, and the second is easy to forget: she also had to be **offered** those
courses. Permission to edit a question she has no way to reach is not a feature -
the screen shows the course list and nothing else.

Checked **coordinator-first**, because `SubjectCoordinator` extends `Teacher` and
testing the narrower role first refuses her before the wider rule is reached. That
exact mistake was made once before on this project, on the exam-viewing path.

### 39 — the lock is in the database, and it beats a correct code

Three consecutive wrong codes, then ten minutes. Stored in `code_attempt`, not in
memory: a lockout a student can end by waiting for a server restart is not a
lockout, and it has to survive her closing the client.

Three decisions worth defending:

- **A malformed code is not a guess.** Typing "AB" is a slip; it does not count
  against her three. Only a properly-formed code that is wrong does.
- **A correct code wipes the slate.** The requirement says three *consecutive*
  failures, so a mistake this morning cannot combine with two this afternoon.
- **The lock beats a correct code.** Checked before anything else, or a lucky guess
  would carry her straight through it.

She is told what each mistake costs — *"You have 2 tries left before a 10-minute
wait"*. A deterrent nobody can see does not deter.

### 43 — measured from her own clock, and sent once

The warning is computed from **her** start and **her** deadline, not from the
sitting's allotted minutes. The teacher may extend the time mid-exam (requirement
42), and a warning computed from the original length would fire at the wrong moment
— or have fired already and never fire again.

Sent **once per attempt**. The clock ticks every second and the condition stays true
for the whole last tenth; a popup every second would be worse than no popup.

On screen it is a real modal, because the requirement says *popup* and a girl deep
in question 14 will not notice the status bar change colour. Non-blocking, so her
countdown keeps running behind it — a modal wait would freeze the very clock she is
being warned about.

### 61 — recorded as a row, not a counter

Her allowance is the sitting's `max_attempts` **plus** whatever her teacher has
granted, computed in one place so the code step and the start step cannot drift
apart.

A row per grant rather than a number, because "she was given two more" should also
answer *who* and *when* — both get asked if a result is ever disputed. The student
is pushed a message rather than left to discover it by trying the code again.

### 76 — a student inside an exam is never signed out

This is the part that needed thinking about. "Activity" means *a message from her
client*, and a girl reading a hard question sends nothing while she reads it.
Signing her out mid-exam would be the worst thing this system could do to anybody.

So an attempt in progress makes her exempt — she is not idle, she is working, and
the server knows because there is a row saying so. Her exam still ends on time; the
clock closes it at her deadline, and once closed she is eligible like anyone else.
All three states are tested.

She is **told, then disconnected**, in that order. Dropping the socket first would
leave her reading "the connection was lost", which invites her to blame the network.

If the "is she in an exam?" query itself fails, she is assumed to **be** in one.
Leaving somebody signed in too long is a nuisance; throwing her out of an exam is a
disaster.

Thirty minutes by default, settable in the config file — the requirement says "a
defined period" without fixing one.

### Faults found, and four of the five were the test

**43's window was wrong.** The test moved the deadline to seven minutes away but
left the start time alone, so her exam *became* seven minutes long and she had a
hundred per cent of it left, not ten. Both ends now move.

**The sweep signed out the suite's own connections** — teacher1 and coordinator1
were idle by the time it ran, so it signed them out, which is exactly what it is
supposed to do. Everything after it then failed. Requirement 77 now runs first and
the sweep is last.

**A student was signed in twice.** The exam-exemption test picked a student the
suite already held a session for, and NFR 16 correctly refused the second login.
Third time this project has hit that rule.

**Two conditional checks made the total drift** — 58 one run, 59 the next. The
suite now picks a student enrolled in the demo course deliberately, so both
conditions always hold and the count is 60 every time.

### Verified

| Suite | Result |
|---|---|
| M2–M11, M13, M14 | unchanged, all passing |
| **M15 (new)** | **60/60** |
| **Total** | **791 checks** |
| Screens | 19/19 load |

Run **twice back to back with no database reset**, identical both times, and M15
three times alone to confirm the count is stable.

### What is left

Milestone 16: run the Assignment 1 acceptance tests by hand and fill in the results
table, finish the redlines in `docs/03_document_updates.md`, the Word document, and
the ZIP. **The two-laptop LAN test is still outstanding**, deferred at the
customer's direction and still required before submission.

---

## Milestone 15, follow-up — a login lock, a whole-room grant, and better wording

### 1. Five wrong sign-ins, then ten minutes — for every kind of user

Asked for by the customer. `LoginAttemptDAO` mirrors `CodeAttemptDAO` (requirement
39) but the two are deliberately separate: they count different things and have
different limits, and a student who mistyped an exam code three times should not
also be unable to sign in.

**Keyed by the typed username, not a user id.** A wrong password arrives with a real
username; a wrong *username* arrives with nothing at all. Counting what was typed
catches both, and it stops somebody working through a list of names five tries at a
time. The table deliberately has **no foreign key** — the whole point is to record
attempts on names that do not exist.

Checked **before** the password, or somebody could keep guessing while locked. A
correct sign-in clears the count, so the five are consecutive. And it **fails
closed**: if the lock cannot be read, nobody signs in — the alternative is a
database fault silently removing a lock.

### The regression I introduced, and the suite that caught it

The refusal first read *"Incorrect username or password. You have 4 tries left
before a 10-minute lock."*

That **undid a deliberate security decision**. The count is kept against the typed
username, so a real account that has already accumulated failures shows a different
number from a name nobody has ever used — and comparing the two tells an attacker
which usernames are real. One shared refusal message exists precisely to prevent
that, and a helpful countdown quietly broke it.

`M2Test` had been checking it since milestone 2: *"same message for both, so
usernames cannot be probed"*. It failed. The countdown is gone; the refusal is now
byte-identical for a wrong password and an unknown user, and `M15Test` asserts that
directly by comparing the two strings.

The cost is that somebody who has forgotten her password gets no warning before the
ten minutes. That is the smaller of the two harms, and the lock message explains
itself when it arrives.

**A second fault, in the test.** `M2Test` probes the username `nobody`, which never
signs in successfully and therefore never clears its count — so across repeated runs
it reached five, locked, and started replying with the lockout message. The product
was right: a name nobody owns cannot clear its own counter. The suite now clears the
state it depends on.

### 2. One button for the whole room

`AttemptGrantRequest.forEveryone` grants one extra attempt to **every student who
sat** the sitting. The case it is for is a power cut, not a person, and granting
twenty by hand invites missing one — which is the same problem repeating itself.

Everyone who **started**, which is the honest set: a girl who never turned up has
nothing to re-sit, and giving her an attempt would quietly change what "all your
attempts" means for her later. Each of them is pushed a message. The confirmation
says how many students it will affect, because "everyone" is not a press to make by
accident.

A separate flag rather than a null student id meaning "all" — a null id also arrives
when something has gone wrong on the way, and *everybody gets another go* should not
be a possible accident.

### 3. The popup wording

Reported from the screen: with five seconds showing on the clock, the popup still
said *"Less than a minute left"*. True and useless.

The payload changed from whole minutes to **seconds**, because the wording the
customer asked for names both. It now reads:

> **90% of the exam time has gone**
> You have 6 minutes and 42 seconds left.

The sentence is composed on the **server**, like the countdown itself, so the screen
does not invent the numbers. Singular and plural are handled, and under a minute it
names only seconds rather than saying "0 minutes and 45 seconds".

### 4. Why a coordinator can release an exam — and why that is correct

Asked directly, so it was checked against the sources rather than answered from
memory.

| Source | Who releases |
|---|---|
| SUC-6, actor column | **מורה** — teacher |
| SUC-6 body | *"עבור בחינה מאושרת בלבד, **המורה** מגדירה מועד..."* |
| Requirement 37 | *"לצורך ביצוע בחינה, **המורה** מגדירה קוד ביצוע..."* |
| מתווה scenario 5 | *"**המורה** מגדירה מועד... **המורה** מגדירה קוד ביצוע"* |
| Requirement 34 | passive — *"ניתן להגדיר"*, no actor named |

Every source that names anybody names **the teacher**. None mentions the coordinator.

**She releases as a teacher, not as a coordinator.** In this school a coordinator
*is* a teacher who additionally runs a subject — the seed data gives each of them
taught courses, and Noa Katz teaches Algebra. `ExamExecutionController` requires
`teacher.teaches(courseCode)`, so she can release Algebra exams and nothing else.

**Requirement 19 did not widen this.** It widened *questions* only. `M15Test` §7 now
proves the separation: her releasable list contains course 02 alone, and releasing
an approved Plane Geometry exam — in her own subject, which she may edit the
questions of — is refused with *"You do not teach that course."*

**Where she edits a teacher's questions:** the **Question bank** screen. Since
requirement 19 her course combo lists every course in her subject, so she picks
Plane Geometry there and edits its questions like any other. Tested in §1.

**One observation worth raising in the report, not a defect.** Requirement 30 says
*every* exam needs the coordinator's approval, and requirement 31 says only the
coordinator of that subject may give it. A coordinator who writes an exam in her own
subject is therefore the only person who can approve it — she approves her own work,
and the demo data contains exactly that case. The documents create it; no
requirement forbids it, and there is no second coordinator per subject to ask.

### Verified

| Suite | Result |
|---|---|
| M2 | 48/48 |
| M3–M11, M13, M14 | unchanged, all passing |
| **M15** | **90/90** (was 60; 30 new) |
| **Total** | **821 checks** |
| Screens | 19/19 load |

Run **three times back to back with no database reset**, identical each time.

---

## One teacher of every course, one girl who studies every course

### Why

Asked for directly, and it fixes something that had been quietly limiting every
demonstration: nobody in the seeded school had more than two courses. A course
picker with one entry proves almost nothing about the picker, and a per-subject
report drawn from a girl enrolled in three of the eight courses shows three
quarters of nothing. Every course picker in the system now has something in all
four subjects to show.

| Username | Password | Full name | ID | Has |
|---|---|---|---|---|
| `teacher9` | `teacher9!T` | Orit Nahum | `100000546` | all 8 courses, so all 4 subjects |
| `student41` | `student41!S` | Liat Barnea | `100000553` | all 8 courses, so all 4 subjects |

Both follow the documented convention in `docs/test_accounts.md`
(`<role><number>` / `<username>!<role initial>`), so nothing new has to be
remembered at the demo.

### No rule was invented

The client story says *"כל קורס מועבר ע"י מורה אחת או יותר ויש תלמידות הלומדות את
הקורס"* — a course is taught by one **or more** teachers and has students who
study it — and requirement 13 repeats it. Neither sentence, and nothing else in
the requirements table, caps how many courses one person may take. A teacher of
all eight is unusual for a real school; it is not illegal in this one.

### Two decisions worth defending

**Created last, after all 53 others.** `nextId` numbers people in the order they
are created, so inserting the new teacher next to the other teachers would have
shifted every student's ID by one. Every existing account keeps the exact ID it
had — checked: `student1` is still `100000140`, `student40` still `100000538` —
so a bug reproduced yesterday still reproduces today, and any note anybody made
of an ID is still true.

**Enrolled before the demo content is seeded, not after.** That means the seeder
seats Liat like anybody else, and she comes out with real marked papers: 75, 60
and 50 in Plane Geometry and 40 in Mechanics. A girl with results in more than
one course is exactly what מתווה 12's "one student across her exams" report
needs, and the alternative — enrolling her afterwards — would have given the demo
a student in eight courses with an empty results screen.

That does move one thing, and it is worth stating plainly rather than being
found later: Plane Geometry had exactly 18 students and the mid-term seats 18, so
it used to seat everyone. With Liat enrolled there are 19, and the alphabetically
last girl no longer has a mid-term paper. **The marks themselves did not change.**
A sitting is capped by how many marks the seeder has to hand out, not by how many
girls are enrolled, and the mark each seat receives depends on the seat's position
rather than on who is sitting in it — so the histogram, the average and every
statistic are identical. Verified directly: still 78 marked papers, still 18 in
the GEO1 sitting.

### The answer to "can a coordinator have more than one subject?"

**No — not in this system, and that is deliberate rather than accidental.**

| Source | What it says |
|---|---|
| Requirement 12 | *"לכל קורס יש רכזת מקצוע"* — every course has a subject coordinator |
| Requirement 19 | *"רכזת המקצוע תוכל לערוך שאלות של אותו המקצוע **שמרכזת**"* — singular |
| Requirement 31 | *"...בחינות השייכות **למקצוע שהיא מרכזת** בלבד"* — singular |
| Class diagram (Assignment 2, submitted) | `SubjectCoordinator -coordinatedSubject : Subject`, association **1 — 1** |

No requirement says a coordinator may have two subjects, and none forbids it
either — but every sentence that mentions her subject is singular, and the
submitted class diagram pins the association at one to one. The database follows
the diagram: `users.coordinated_subject` is a single `CHAR(2)` column, not a join
table, so a second subject cannot be stored even by hand.

Changing it would not be a small edit. It would need a `subject_coordinator`
table, a `Set<String>` on the entity, `coordinates()` becoming a membership test,
three call sites in `ExamApprovalController` and `ExamBuilderController`,
`listMyCourses` in `QuestionController`, and a redline against the submitted class
diagram. Worth doing only if the customer actually wants it — so it is recorded
here as an answer, not done.

### Verified

A new harness, `NewUsersTest`, checks both accounts through **real logins and the
same requests the screens send** — a row in `course_teacher` proves nothing about
what her own client receives.

| What | Result |
|---|---|
| Orit's course list | 8 courses, 4 subjects |
| Questions she may edit | the whole current bank (121 with test debris, 80 clean) |
| Exams she may release | every approved exam version in the school |
| Liat's courses | 8, covering 4 subjects |
| Her results | 4 published marks, in 2 courses |
| Her study bots | one row per course of hers that has a bot; only the active ones usable |
| The live sitting | `NOW1` accepts her, and checking the code does not use her attempt |
| Nothing else moved | 55 users, GEO1 still 18 papers, every course still has a teacher and students, every subject still exactly one coordinator |

| Suite | Result |
|---|---|
| M2 | **54/54** (was 48; 6 new) |
| M3–M11, M13–M15 | unchanged, all passing |
| **NewUsersTest** | **27/27** |
| **Total** | **854 checks** |
| Screens | 19/19 load |

Run **three times back to back with no database reset**, identical each time, and
`NewUsersTest` run separately against freshly reset data as well — the two states
report different bot counts and it passes in both, because the numbers it expects
come from the database rather than from what I remembered them being.

**Three of its checks failed the first time and all three were the harness, not
the product** — each recorded here because the reasoning behind the product's
behaviour is the interesting part:

- It compared the visible bank against every row in `question`. Editing keeps the
  old version and deleting is a soft delete, so the table holds history the bank
  screen must never show. Now compared against current, undeleted questions.
- It expected the release list to hold only *current* approved exams. The list is
  deliberately per **version** — an exam approved and later edited can still be
  given to a class, and the screen prints `020101 · v2 · Plane Geometry` so the
  teacher can tell them apart.
- It expected a student to see only active bots. She is deliberately shown a
  switched-off bot on a course that has no active one, **so that she is told it is
  off** rather than left wondering; requirement 71 is enforced on the asking, and
  the refusal reads *"is not switched on at the moment. Your teacher turns it on
  and off."* The harness now proves that refusal for her directly.

---

## The sitting's closing time now ends the exam for everybody

### What changed and why

Asked for directly: when the exam's end time arrives, everyone still inside is
handed in, with a five-minute warning beforehand.

**This reverses a decision that was agreed on 2026-07-29** — answer Q8, "the close
time is a deadline to *start*, not to finish" — so it is worth saying plainly that
the requirements are on the customer's side. Two of them describe two different
endings:

| | Requirement | Ends the exam when |
|---|---|---|
| 41 | *"עם הזנת מספר הזהות מתחיל מד-הזמן; עם תום הזמן המוקצה הבחינה נסגרת אוטומטית"* | her own allowance runs out |
| 45 | *"בסיום זמן הבחינה, המערכת תסגור את הבחינה **עבור כל התלמידות** ותשמור את התשובות שהוזנו"* | the sitting's time ends, for everybody |

The old reading left **requirement 45 with nothing to do**: if every exam ended on
its own student's clock, nothing ever closed anything "for all the students". The
new reading gives both requirements work, and whichever end comes first is the one
she meets.

### Two ends, kept apart on purpose

`StudentExam` now carries the sitting's `closeTime` beside her own `deadline`, and
`effectiveEnd()` is whichever comes first. Every judgement in the system — how
long is left, has she run out, how long did she take — goes through that one
method, so the countdown on her screen, the guard that refuses a late answer and
the clock that hands her paper in cannot disagree.

They are **not** folded into one clamped deadline, for two reasons. The teacher
may still add minutes mid-exam (requirement 47) and that has to move one column
rather than a computed value. And the two ends need different words: *"your time
is up"* and *"the exam has closed for everyone"* are different things to be told,
and a girl with twenty minutes of her own left would rightly be baffled by the
first.

The close is **read with the attempt** rather than copied onto it, so a sitting
whose window moves can never leave a stale copy behind.

### One warning per student — the relevant one

This is the part that needed a decision rather than an implementation. Requirement
43's popup is at 90% of the exam time; but a girl who starts ten minutes before
the room closes still has 89% of her ninety minutes in hand when she is handed
in, so that popup would never reach her at all.

| Which end will stop her | What she gets |
|---|---|
| Her own allowance | *"90% of the exam time has gone. You have 6 minutes and 42 seconds left."* |
| The sitting's close | Five minutes before it: *"This exam closes for everyone at 13:30. You have 4 minutes and 12 seconds left, and your paper will be handed in for you."* |

Exactly one arrives per attempt. The closing wording deliberately names the
**wall-clock time** as well as the countdown, because this end is not hers: it is
the same for the girl beside her and working faster will not move it.

A tie goes to her own time, because that is the one the requirements actually name
a warning for.

### The smaller consequences, each done rather than left

- **She is told the truth when she starts.** *"Good luck. This sitting closes for
  everyone at 16:57, so you have 7 minutes rather than the full 100."* Telling her
  she had a hundred minutes would have been a plain untruth she discovered at the
  worst possible moment.
- **The teacher is told when extra time buys nothing.** Adding ten minutes to a
  sitting that closes in four now answers *"...this sitting closes at 16:55 and the
  exam ends then for everyone, so 2 students gain nothing from the extra time."*
  Without it she would add time, see nothing change, and reasonably think the
  system had ignored her.
- **The end time recorded is the close**, not the moment the tick happened to
  notice, so two girls closed by the same event are recorded as taking the same
  time.
- **Both endings stay `TIMED_OUT`.** Requirement 48 counts started / finished /
  "לא הספיקו" — two outcomes, not three — and a girl the room closed on did not
  finish by herself either. What differs is what she is *told*, which is the part
  she can see.

### Verified

`ClosingTimeTest`, a new harness: the two ends as pure logic, the countdown, the
warning choice, the forced close of **two** students at once with their answers
kept, both wordings, and the branch where her own time still binds.

| What | Result |
|---|---|
| Which end wins | close before / after / equal / absent — all four |
| Countdown | counts to the close, not to her full allowance |
| Warning | closing one arrives; **the 90% one does not also arrive**; neither repeats |
| Forced close | both students pushed, both `TIMED_OUT`, end time = the close, durations recorded |
| Requirement 45's other half | the answers each had chosen are still in the database |
| After the close | she cannot answer, and nobody can start |
| The other branch | the 90% popup still arrives when her own time binds, and the closing one does not |

**`M7Test` had to change, because it asserted the old rule.** Section 12 was
called "DECISION 8 - the window closes, her own time does not" and section 14
checked that she could carry on answering afterwards. Both now check the opposite,
and section 15 was given its own sitting and student so that the *personal*
time-out path is still tested rather than lost in the rewrite. That is the whole
point of keeping the suites: the change showed up as four failures rather than as
a surprise at the demo.

| Suite | Result |
|---|---|
| **M7** | **67/67** (was 55; 12 new, 3 rewritten) |
| **ClosingTimeTest** | **51/51** |
| M2–M6, M8–M15, NewUsersTest | unchanged, all passing |
| **Total** | **917 checks** |
| Screens | 19/19 load |

Run **three times back to back with no database reset**, identical each time, and
once more against freshly reset demo data.

One thing to know before running it a fourth time: each pass builds exams in
Plane Geometry, and three passes take that course from 9 exams to 87. The
documented ceiling is 99 (a two-digit exam number), so a fourth pass would hit it.
`buildExam` says so in as many words rather than failing obscurely, and
`SeedRunner.resetAndSeed` puts it back to 9.

---

## Saying whose approval it needs, and unread badges on the menu

Two UX changes, asked for together. Nothing else was touched: no rule about who may
approve what has changed, and no screen does anything it did not do before.

### 1. Every waiting thing names who it is waiting for

`ExamStatus.PENDING_APPROVAL` used to display as **"Pending approval"**, which left
the obvious question unanswered. It now reads:

> **Waiting for Subject Coordinator approval**

Changed in the enum, not on the screens. Six places show an exam's status - the
exam list, the exam detail, the version history, the release screen, the
principal's browser and the teacher's report - and wording repeated six times is
wording that drifts. `getWaitingFor()` returns the role on its own, for a screen
that wants to build its own sentence rather than take the display name apart.

The same for a mark: `Grade.getWaitingFor()` returns *"the teacher"* until it is
approved, and the marking screen now says **"waiting for your approval"** rather
than "not approved yet" - she is reading her own queue, so the answer is "yours".

What was already right and was left alone: saving an exam already answered *"sent
to the subject coordinator for approval"*; the student's results screen already
said *"Waiting for your teacher to approve it"*; the coordinator's own list is
already headed **WAITING FOR YOU**; and the release screen already explained that
an exam needs the coordinator's approval before a class can sit it.

### 2. Unread badges

A red circle with a count, at the right-hand end of the menu entry, as on a phone.

| Role | Entry | What the number is |
|---|---|---|
| Coordinator | Approve or reject exams | exams in her subject waiting for her decision |
| Teacher | Mark and approve grades | papers handed in on her sittings that she has not approved |
| Student | Take an exam | sittings open right now that she may still go into |
| Student | My grades | marks published since she last opened her results |

A coordinator is also a teacher, so she gets both of the first two.

**The principal has none, deliberately.** She approves nothing and marks nothing
(system description §7.3), so a badge on her menu could only ever be noise. Her
menu does not even ask for the counts.

**Only her own work is counted.** An exam she wrote that is sitting with the
coordinator does not appear on the teacher's badge: there is nothing for her to do
about it, and the exam's status already says who it is waiting for. A badge that
counted other people's work would never reach nought and would be ignored inside a
day.

**Each count is produced the way the screen behind it lists.** The coordinator's
badge calls the very method her approval screen calls - the suite asserts the two
are equal rather than asserting a number. The student's "take an exam" badge
applies the same attempts arithmetic the code screen applies, so it cannot promise
a sitting the next screen refuses.

**Updated by push, never by polling.** The menu asks once when it opens, and again
only when a push arrives that could have changed a count - a small allowlist, not
"anything", because the exam clock pushes a tick every second and a menu that
answered those with a request each would be polling by another name.

### The student's "unread", and the one new column

The other three counts are questions about existing rows. "Marks she has not read"
is not: nothing recorded whether she had looked. So `users.results_seen_at` was
added - one nullable column on the person rather than a flag on every mark, because
she reads the *list*, and the list is what to remember. Opening her results sets
it; the badge counts marks approved after it.

An existing student's column starts NULL, so every published mark is unread to it.
That is the honest starting point rather than a special case.

### The defect the badge suite found

The first version compared `grade.approved_at > users.results_seen_at`, both
`DATETIME` - whole seconds. **A mark approved in the same second as she opened her
results sorts equal to her visit, so it never counts as unread and she is never
told about it at all.** Not a test artefact: the marker never rewinds, so the
notification is lost for good.

`BadgeTest` is fast enough to land in that same second every run, which is how it
surfaced. Both columns are now `DATETIME(3)`. The migration widens
`grade.approved_at` on an existing database as well - checked by rolling the schema
back by hand, running the schema step, and reading the column types out again:

```
grade.approved_at      = datetime(3)
users.results_seen_at  = datetime(3)
```

A second, smaller thing the suite corrected was my own assumption: I wrote a check
saying a just-submitted paper has no mark row yet. It has - handing in marks it at
once. The row that genuinely has none is one **the clock** closed, because
`closeExpired` finishes the attempt and leaves the marking until the teacher opens
the sitting. That is precisely the case the LEFT JOIN in `countAwaitingApprovalBy`
exists for, so the suite now produces it - a second girl, closed by the clock - and
asserts both halves: no mark row, and still counted.

### Verified

| Suite | Result |
|---|---|
| **BadgeTest** | **35/35** - the wording, and every count as a *delta* before and after the action |
| **MenuBadgeTest** | **20/20** - the badge as JavaFX actually lays it out |
| M2–M15, NewUsersTest, ClosingTimeTest | unchanged, all passing |
| **Total** | **952 checks** |
| Screens | 19/19 load |

`BadgeTest` measures deltas, never absolutes: the suites leave exams and papers
behind on purpose, so "she has three waiting" is true only on freshly reset data
and would fail for reasons that have nothing to do with badges.

`MenuBadgeTest` is a JavaFX harness in the shape of `FxmlLoadTest`. It loads the
real menu for each role, applies counts, forces a layout pass and then measures:
that the badge exists on the right entries and on no others, that two digits still
fit inside the button, that the badge ends within 16 pixels of the button's right
edge - the button's own padding - and that emptying the queue hides it and gives
back its room rather than showing a nought.

Run **three times back to back with no database reset**, identical each time.

---

## Seven interface changes, and the defect two of them uncovered

All asked for from the screen after a walk through the system.

### 1. Nothing says the system is unfinished any more

The menu used to grey out entries that had not been built and name the milestone
that would deliver them - *"Course study bot — milestone 14"* - under a footer
reading *"Greyed-out entries are not built yet."* There is nothing left to grey
out. The milestone is gone from every entry, the footer is hidden rather than
blanked so it leaves no empty line, and `MenuBadgeTest` now walks every button on
every role's menu and fails if any of them is disabled or still says "milestone".

### 2. The badges move with nothing pressed

The counts were right from the first version. What was missing was the
announcement, in two places:

- **A class was not told when an exam was given to them.** Nothing announced a
  release, so a girl sitting on her menu saw the count stay at nought until she
  clicked something. `PushType.PENDING_COUNTS_CHANGED` now goes to the enrolled
  students: *"Plane Geometry is open now - you can take it."*
- **The menu ignored the announcement a teacher already got.** A hand-in was
  already pushed to her - it is what refreshes her live view - but the menu's list
  of events worth re-counting for did not include it. One line.

Still an allowlist rather than "refresh on any push": the exam clock sends a tick
every second, and a menu that answered those with a request each would be polling
by another name.

**A mistake made and taken out again, worth recording.** The first attempt added a
callback so the take-exam controller could announce a hand-in - not realising the
dispatcher already did, three files away. Every hand-in was pushed twice. The
suite now asserts the announcement arrives **exactly once**, which is how it was
found.

### 3. The badge is pink, not red

`#f7d3d8` with deep-rose text, rather than the flat `#c02626` it started as. The
count is a nudge - *there are three things here* - not an alarm, and a saturated
red on every menu makes the whole screen read as a warning.

### 4. No word is cut off anywhere

Reported from the screen: the release form showed a button reading **"..."** where
**Now** should have been. Rather than hunt the rest by eye across seventeen
screens, `TruncationTest` measures: it lays every screen out three times - at its
preferred size, at 1280x720, and with every hidden pane revealed - and measures
each piece of text **in its own font** against the width it was given.

That last detail matters. The first version compared a control's preferred width
against its actual width, which called a button pinned to `prefWidth="30"`
perfectly happy while it showed an ellipsis. Checked by deliberately squeezing the
Now button again and confirming the suite catches it:

```
[CUT]     /fxml/ExamRelease.fxml
            "Now" needs 56 and has 39
```

All seventeen screens are clean.

### 5. Every window fits the display

The screens ask for the width they need - the marking screen wants 1280 - and a
laptop at 125% or 150% scaling has a desktop only 1536 or 1280 points wide.
`sizeToScene` will happily make a window taller than the screen, and the bottom of
it is simply not there: *"the choose question window isn't fully visible"*.

Every window is now clamped to the working area, moved back into view if the
clamp left it half off the edge, and **resizable**. There used to be a flag for
that and two screens passed `false`; a window a user cannot resize is one she
cannot read on a smaller laptop, so the flag is gone rather than set. Dialogs go
through one helper that makes them resizable and gives their text a width to wrap
into.

### 6. "Now" sets an end time as well

It set the opening moment and left the closing one at whatever the boxes happened
to hold - which, now that the close ends the exam for everybody, was either
already past or days away. It now sets the close to **an hour later** and says so:
*"Opens now and closes at 15:42. Change either if you need to."*

The caption under the closing time still described the **old** rule - *"This is
the deadline to START. A student who begins just before it still gets her full
time."* - which stopped being true when the customer changed it. It now reads
*"Nobody may start after this moment, and anybody still working is handed in when
it arrives."*

### 7. A student can see how she is doing

**My grades** now carries her own figures, course by course: how many exams, her
average, her best and lowest, and a line for all her courses together. Built as a
`Report` with a `ReportLine` per course, the same shape the principal's reports
use, so nothing new had to be invented to carry it. Strongest course first - she
is asking how she is doing, not for an index.

**Her marks only.** No class average and no position: SUC-10 gives her *her*
results, requirement 57 keeps one girl's marks away from another, and an average
is a short step from working out somebody else's in a class of four. Unapproved
papers are left out rather than counted as nought - a mark her teacher has not
released is not a mark she has, and averaging it in would quietly make her look
worse than she is. The card disappears entirely when she has no marks yet; a panel
of dashes reads as a broken screen.

---

## The defect underneath: two threads, one connection

While the badge suite was being extended it failed **once in ten runs** with
*"no reply to TAKE_START"*, and the server log said *"connection was aborted"*.

It is not the network. OCSF's `sendToClient` is one line -
`output.writeObject(msg)` - with **no synchronisation**, and two threads write to
the same connection routinely:

| Thread | What it writes | When |
|---|---|---|
| The exam clock | the seconds remaining | every second, to every student in an exam |
| Her connection's own thread | the reply to her request | whenever she answers or submits |
| The inactivity sweep | "you have been signed out" | on its own timer |
| Any request thread | a push to somebody else | approvals, releases, publishing |

Two writes interleaving inside `ObjectOutputStream` put the bytes of one object
inside the other. The client cannot read past the damage and drops the connection,
and what the user sees is **a request that never comes back, in the middle of an
exam**.

`StreamRaceTest` makes it happen on purpose rather than waiting for it: a girl
sitting an exam answering as fast as she can for fourteen seconds while the clock
ticks at her. Before the fix it broke the connection on **every** run.

The fix is `Transport.send`, which every writer in the system now goes through and
which locks on the connection. Holding a lock across the write is safe here: these
go to a buffered stream on a local network and take microseconds, and the
alternative - a queue and a writer thread per client - is a great deal of
machinery for a school with thirty terminals.

| | Before | After |
|---|---|---|
| Requests answered | connection died mid-run, every run | **~7,000** |
| Ticks arriving during them | - | 14 |
| Replies lost | the rest of the run | **0** |

Three runs each way.

**This is not new.** The same signature is in this file from milestone 7: *"It
failed once in a long sequence run and could not be reproduced in nine further
attempts."* That was this, and it would have been the demo's flake to explain.

### Verified

| Suite | Result |
|---|---|
| **BadgeTest** | **51/51** (was 35; the live announcements and her own figures) |
| **MenuBadgeTest** | **29/29** (was 20; every entry's wording too) |
| **TruncationTest** | **17 screens, 0 with cut-off text** (new) |
| **StreamRaceTest** | **5/5** (new) |
| M2–M15, NewUsersTest, ClosingTimeTest | unchanged, all passing |
| **Total** | **973 checks** |
| Screens | 19/19 load |

Run **three times back to back with no database reset**, identical each time.

---

## The coordinator's decision now waits for the teacher, and the menu says who she is

### 1. Was the teacher already told? Yes - and it was not enough

She has been pushed a message the moment her coordinator decides since milestone 5:

> *"Your exam 050101 was approved by Noa Katz. You can now release it to a class."*

But a push only reaches somebody who is **signed in at that moment**, and a teacher
usually is not. She logged in the next morning to nothing at all: no mark on the
menu, no mark beside the exam, and the only way to find out was to open the release
list and compare it against her memory.

So the decision now **waits for her**, in three places:

| Where | What |
|---|---|
| The message, at the time | now names the **course** as well as the number, and says **APPROVED** or **REJECTED** in as many words |
| The menu | a badge on **Release an exam** counting exams of hers approved since she last looked |
| The release list | a **dot** beside each of those exams, and a line saying "Approved since you last looked" |

The badge says how many; the dot says which. One without the other leaves her to
work out for herself which of eleven approved exams is the one that just changed.

**Her own exams only.** Another teacher's exam being approved in a course she happens
to teach is not news she is waiting for, and a badge counting it would be pointing at
somebody else's work.

**Looking is what spends it**, exactly as with a student's results: the flags are
worked out first and the marker is written after, so the reply that carries the dots
is the last one that does.

Rejections take the same path and say the same things, but carry no dot - a rejected
exam is not in the release list at all, because it cannot be released. The message
and the exam's status ("Rejected", with the reason) are where she sees it.

### A schema detail that would have bitten

`exam.approved_at` was whole seconds. It is now `DATETIME(3)`, for the same reason
`grade.approved_at` was widened a fortnight ago: this column is compared against the
moment the teacher last looked, and at whole-second precision an approval made in
that same second sorts equal to her visit and would never be marked as new - not
"late", but never. Widened on existing databases too.

### 2. A coordinator who teaches nothing has no Release entry

Releasing is done by the teacher **of the course** (SUC-6, requirement 37, מתווה 5),
which the system enforces. A coordinator with no classes of her own therefore has
nothing she could ever release, and the entry opened onto an empty screen - a
question the user had to answer for herself.

The rule is written as "teaches no courses", not as "is a coordinator": a plain
teacher between timetables gets the same treatment, and there is no special case for
a role.

Her **Approve or reject exams** entry is untouched - that is subject-scoped and has
nothing to do with teaching.

### 3. The menu says who she is, in words

The line under her name read:

> Coordinates subject 01   ·   teaches course(s): 02

Both facts were already there and neither meant anything. It now reads:

> Coordinates Mathematics (01)   ·   Teaches Algebra (02)

...and for a coordinator with no classes of her own, *"Coordinates Physics (02) ·
teaches no courses of her own"* - said out loud, because it is a real state and a
sentence that trails off looks like a fault.

The names could not come from the question bank's course list: since requirement 19
that list is deliberately **wider** than the courses a coordinator teaches, because
she may edit the questions of her whole subject. Printing it as "teaches" would have
been plainly false. `MENU_CONTEXT` returns the two lists separately, each meaning
exactly what it says, from `course_teacher` and `course_student`.

### Two faults in the suites, both found by the demo

**A test that assumed nobody else had touched the data.** `M11Test` looked for a
sitting with a full class by checking `xs.get(0)` - the *newest* sitting of each
exam. A teacher released the same exam again by hand during the walkthrough, nobody
had sat it yet, and eighteen students behind it became invisible; the check failed
and the next line threw. It now scans every sitting of every exam, which is what
requirement 36 says an exam can have.

**Every suite held a fixed port.** One run failed with "Address already in use", and
the port turned out to be held by an unrelated process on the machine - Windows here
hands out ephemeral ports from 1024 upwards, the whole range, so any fixed number a
suite picks can be taken at any moment. It was never safe, just usually lucky. All
eighteen suites now ask the operating system for a free port at the moment they
start.

### Verified

| Suite | Result |
|---|---|
| **BadgeTest** | **77/77** (was 51; the notice, the dot, and the menu context) |
| **MenuBadgeTest** | **37/37** (was 29; the release entry appearing and disappearing, and the context line) |
| M2–M15, NewUsersTest, ClosingTimeTest, StreamRaceTest | unchanged, all passing |
| **Total** | **999 checks** |
| Screens | 19/19 load, 17/17 with no cut-off text |

One practical note for anyone running the suites: three full passes take Plane
Geometry from 9 exams to the documented ceiling of 99, because most suites build
their exams there. `SeedRunner.resetAndSeed` puts it back; `buildExam` says so in as
many words rather than failing obscurely.
