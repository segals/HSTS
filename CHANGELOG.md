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
