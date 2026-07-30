# HSTS — Phase 0 Understanding Document

**Course:** Software Engineering 203.3140, Spring 2026
**Written:** 2026-07-28
**Status:** Phase 0 deliverable. No code written. Awaiting answers to the open questions in §7.

Sources read: all 38 files under `C:\GitHub\SE` (13 Java, 8 PlantUML, 8 Word, 1 Excel, 1 Markdown).
The 7 SVG files are renderings of the `.puml` files and contain no independent content.

Note: the real path is `C:\GitHub\SE\SE\...` — there is a nested `SE` folder inside `C:\GitHub\SE`.

---

## 1. What the system does, in simple words

A high school wants one computer system to run all of its exams. Today the work is
spread across paper, teachers' own files, and manual marking. HSTS replaces that.

Four kinds of people use it, and each sees a different menu after logging in:

- **A teacher** writes multiple-choice questions into a shared bank, assembles them into
  exams, releases an exam to her class, watches the class take it live, marks it, and
  publishes the grades.
- **A subject coordinator** is a teacher with one extra job: she must approve every exam
  in her subject before anybody can sit it. If she rejects it, she must say why, and that
  reason goes back to the teacher.
- **A student** logs in, types a short code the teacher reads out loud, types her ID
  number, answers the questions against a countdown clock, and submits. Later she can see
  her grade and her marked paper — but only her own.
- **The principal** cannot change anything. She reads everything and pulls statistical
  reports.

On top of that there is a **study bot**: a teacher can attach course material (PDFs, Word
documents, free text, and questions from the bank) to an AI assistant for her course.
Students in that course can ask it questions — except while they are sitting an exam.

Two ideas run through the whole system and are worth stating plainly, because most of the
design follows from them:

1. **"In the drawer" vs "out of the drawer."** An exam is written once and stored. It can
   then be handed out to students *many separate times* — a different class, a different
   date, a re-sit. Each hand-out is its own event with its own code, its own clock, and
   its own set of results. (System description §2.1–2.2)
2. **Nothing is thrown away.** Editing a question or an exam does not overwrite the old
   one; the old one stays in the bank. An exam sat last month must still show the exact
   questions it contained at the time. (מתווה scenario 2 item 2, scenario 3 item 5)

The system is two Java programs — a client and a server — on two separate laptops,
talking over the local network, with a MySQL database behind the server.

---

## 2. The 15 use cases and what each must do

Taken from `סעיף 2 - use case table.docx`. These are binding.

| ID | Name | Primary actor(s) | What it must do |
|---|---|---|---|
| **SUC-1** | Login | All roles + external user-management system | Identify by username + password. The same user may not be logged in twice at once. Auto-logout after a period of inactivity. After login, show a menu matching the role. |
| **SUC-2** | Manage question bank | Teacher, Coordinator | Create and edit questions for courses she teaches. Each question: text + instructions, exactly 4 answers with exactly 1 correct, optional image, difficulty, one course. Unique 5-digit ID. A coordinator may also edit questions belonging to her subject. |
| **SUC-3** | Build exam manually | Teacher | Pick questions from the bank by hand. Set duration in minutes and points per question. Optional instructions for students and hidden notes for the teacher. Saved with a unique 6-digit ID, not yet active. |
| **SUC-4** | Build exam automatically | Teacher | Give criteria — how many questions, split by topic, difficulty — and let the system pick. **If there are not enough matching questions, say so and create nothing.** |
| **SUC-5** | Approve / reject exam | Coordinator | Review exams awaiting approval *for her subject only*. Approve, or reject with a written reason that is sent to the author and stored. |
| **SUC-6** | Set exam dates | Teacher | For an **approved** exam only, set an opening and closing date+time. Cannot set dates on an unapproved exam. May be done repeatedly — each release is a separate execution. |
| **SUC-7** | Take exam | Student | Enter the execution code, then her ID number. The clock starts on ID entry. Remaining time shown on screen throughout; a popup at 90% of the time. Submit manually, or the system closes it automatically at time-up. Records actual minutes taken and whether she finished herself or ran out of time. |
| **SUC-8** | Manage exam during execution | Teacher | Define the 4-character execution code, given to students verbally. Define how many attempts are allowed. **While students are mid-exam, change the allotted time** — a temporary change valid for this execution only. Approve an extra attempt for a student. |
| **SUC-9** | Mark exam and grade | Teacher | System marks automatically and computes a grade. Teacher reviews the list of exams awaiting her approval, may change a grade by hand (**explanation mandatory**), may add comments, then approves. On approval the result becomes visible to the student. System computes and stores average, median, and decile distribution — **not visible to students**. |
| **SUC-10** | View exam grades | Student, Teacher, Coordinator, Principal | Each sees according to permission. A student sees only her own — grade, her submitted paper, wrong answers marked, teacher comments. A student can never see another student's grade. |
| **SUC-11** | Teacher reports | Teacher | Statistical analysis of every exam **she wrote**, even if another teacher ran it. |
| **SUC-12** | Principal data access and reports | Principal | Read-only view of all questions, exams, and results. Plus statistical reports (average, median, deciles) comparing grades across: different exams by one teacher / different exams in one course / different exams by one student. Must be easy to add new report types later. |
| **SUC-13** | Create and run the bot | Teacher | Create a bot for a course she teaches: name, course, knowledge sources (question bank, PDF, Word, free text). If a bot already exists and the course has a second teacher, that teacher can add to it. Activate and deactivate. |
| **SUC-14** | Use the bot | Student | Only if enrolled in the course and the bot is active. **Not available while she is sitting an exam in that course.** Question goes to the external API, answer is shown. If no suitable answer comes back, show a message. |
| **SUC-15** | View bot history | Student, Teacher | Student sees her own question/answer/time history. Teacher sees general usage information — how many questions, common questions — **with no student identities**. |

---

## 3. Architecture and design patterns I committed to in Assignment 2

### Three tiers

**Presentation (Boundary)** — everything the user sees, plus the client's network endpoint.
`HSTSClient` (extends OCSF `AbstractClient`), `ClientController`, abstract `GUIScreen`, and
nine concrete screens: Login, QuestionMgmt, ExamBuilder, ExamApproval, TakeExam, Grading,
StudentResults, Report, Bot. Two external-facing interfaces live here:
`IUserManagementSystem` and `IBotAPI`.

**Application (Control)** — the thinking. `HSTSServer` (extends OCSF `AbstractServer`,
Singleton) receives every message and routes it to one of nine controllers: Login,
Question, ExamBuilder, ExamApproval, ExamExecution, Grading, ResultsView, Report, Bot.
Persistence sits here too: `DBController` (Singleton) plus `IDAO` with `QuestionDAO`,
`ExamDAO`, `GradeDAO`.

**Data (Entity)** — the things being stored. `User` (abstract) with `Teacher`,
`SubjectCoordinator extends Teacher`, `Student`, `Principal`; `Subject`, `Course`,
`Question`, `Answer`, `Exam`, `ExamQuestion`, `ExamExecution`, `StudentExam`,
`StudentAnswer`, `Grade`, `QuestionFeedback`, `ExamStatistics`, `Bot`, `KnowledgeSource`,
`BotConversation`, `Report`, and six enums.

### The five patterns, and why each is there

| Pattern | Where | Why, in plain words |
|---|---|---|
| **Singleton** | `HSTSServer`, `DBController` | There must be exactly one server and one database connection manager. A second one would mean two sources of truth. |
| **Strategy** | `ExamBuildStrategy` → Manual / Automatic; `ReportStrategy` → Teacher / Course / Student | Two ways to pick questions, three ways to build a report. Same interface, swappable body. Adding a fourth report means writing one new class and changing nothing else — which is exactly what requirement 64 demands. |
| **Factory** | `ReportFactory` | Turns a report *type* into the right strategy object, so `ReportController` never needs a chain of `if` statements. |
| **DAO** | `IDAO` + `QuestionDAO` / `ExamDAO` / `GradeDAO` | All SQL lives in one layer. Controllers never see SQL. This is what makes the version-2 move (web access) cheap — requirement 8. |
| **Observer** | OCSF `AbstractClient` / `ClientController` | The client does not ask "is anything new?" The server pushes, the client reacts. This is what satisfies NFR 18, "no manual screen refresh". |

### OCSF — verified, not assumed

I test-compiled the three OCSF classes I will use against the JDK actually installed on
this machine (**Java 26.0.1, Maven 3.9.15**):

```
javac -d out -Xlint:all AbstractClient.java AbstractServer.java ConnectionToClient.java
→ 4 warnings, 0 errors, exit code 0
```

The warnings are: three raw-`HashMap` warnings in `ConnectionToClient`, and one
`[removal] finalize() in Object has been deprecated and marked for removal`.
**None of these block anything.** `finalize()` still exists in JDK 26. Good news: OCSF is
safe on our Java version.

The API I get is exactly what the class diagram assumes:
- `AbstractClient`: `openConnection()`, `sendToServer(Object)`, `closeConnection()`,
  `isConnected()`, and the abstract `handleMessageFromServer(Object)`.
- `AbstractServer`: `listen()`, `stopListening()`, `close()`, `sendToAllClients(Object)`,
  `getClientConnections()`, plus hooks `clientConnected` / `clientDisconnected` /
  `clientException`, and the abstract `handleMessageFromClient(Object, ConnectionToClient)`.
- `ConnectionToClient`: `sendToClient(Object)`, `setInfo(String, Object)`,
  `getInfo(String)`, `close()`.

`setInfo`/`getInfo` matter a lot: that is where I will hang the logged-in user on each
connection, which is how the server knows who is asking and how it pushes updates to the
right people.

### The line-ending hazard is real — and it has already happened once here

`AbstractClient.java`, `AbstractServer.java`, `ConnectionToClient.java` (and
`AdaptableClient.java`) are in the original 2001 Mac format: **carriage-return only, zero
line-feeds** (e.g. `AbstractClient.java` = 352 CR, 0 LF). Java compiles them fine. Git may
not leave them alone.

Evidence that this is not theoretical: `EchoServer.java` in the same folder has **already
been converted** (0 CR, 110 LF), and in the process its first two lines were fused:

```java
package org.openjfx.demo2;// This file contains material supporting section 3.7 of the textbook:
```

It survived only by luck — the `;` ends the statement, so the rest is just a comment. Had
the merge happened in the other order, the `//` would have swallowed the package
declaration. This is precisely the accident the project instructions warn about, and it is
sitting in the repository right now. Mitigation is planned for Phase 1 (convert to LF once,
plus a `.gitattributes` marking them binary-safe).

---

## 4. Confirmation of the seven gaps already known

All seven are real; I verified each against the sources.

1. **No version history** — confirmed. `Question` and `Exam` have no version fields; `ExamStatus` has no superseded state. מתווה 2.2 and 3.5 both require the old copy to survive.
2. **No question deletion** — confirmed. `QuestionController` has `addQuestion`, `editQuestion`, `getQuestionsByCourse`, `generateQuestionId` — no delete. מתווה 2.4 requires it.
3. **No topic field** — confirmed, with a wrinkle. Requirement 15 gives a question *"a tag of the subject it belongs to"* — but a question already belongs to one course (req 16) and a course belongs to one subject, so that tag is redundant and is **not** a topic. Requirement 28 and מתווה 3 need `נושא` (topic) for automatic building. The field is genuinely missing.
4. **"Total = 100 points" is test-only** — confirmed. It appears in מתווה scenario 3 note 3 and acceptance test 1.5, and in no numbered requirement.
5. **Histogram has no requirement and no class** — confirmed. מתווה 10 requires it; nothing in the requirements table or class diagram mentions it.
6. **Server push** — confirmed as necessary. NFR 18 forbids manual refresh, and acceptance test 2.7 requires a student's timer to jump *by itself* when the teacher grants extra time.
7. **Two acceptance tests assume a browser** — confirmed. Test 2.11 says "the browser was refreshed"; test 4.6 says "change a URL parameter". We are building a desktop JavaFX client.

---

## 5. Further contradictions and gaps I found

These are **in addition** to the seven above. Each is quoted from its source.

### 5.1 The exam status model contradicts itself (highest impact)

Three different meanings of "in the drawer" are in play:

- **System description §2.1–2.2** — "בחינה שהוכנה ושמורה 'במגירה'" vs "בחינה ש'הוצאה מהמגירה' וניתנה לתלמידות". Here "in the drawer" means *not currently being sat*. An **approved** exam that has not been released is still in the drawer.
- **Requirement 27** — "בחינה שהוכנה ונשמרת נמצאת במצב 'שמורה במגירה' (לא פעילה)".
- **My SUC-3 post-condition** — "הבחינה נמצאת במצב 'שמורה במגירה' (ממתינה לאישור)" — treats in-drawer and awaiting-approval as *the same state*.
- **My acceptance test 1.1** expects the post-state to be "**ממתינה לאישור**" (pending approval).
- **My `ExamStatus` enum** lists `IN_DRAWER` and `PENDING_APPROVAL` as *two different values*.

So: is a freshly saved exam `IN_DRAWER` or `PENDING_APPROVAL`? The activity diagram and the
sequence diagram both say `IN_DRAWER`; the acceptance test says pending approval. The root
cause is that the enum mixes two independent things — *where the exam is stored* and *how
far through approval it is*. An approved-but-unreleased exam has no valid value at all.

### 5.2 The 5- and 6-digit ID formats have no room for a version number

Question IDs are fixed at 5 digits (3 question number + 2 course code) and exam IDs at 6
digits (2 exam + 2 course + 2 subject). Both formats are fully allocated — every digit has
a meaning. But versioning requires the old and new copies to coexist in the same table.
Two rows cannot share a primary key. The documents define the ID format and separately
demand versioning, and never reconcile them.

### 5.3 Nothing in the design ever *sets* the execution code

`ExamExecutionController.validateCode(code)` reads it. `ExamExecution.executionCode` stores
it. **No class has a method that assigns it.** `ExamApprovalController` has
`releaseFromDrawer()` and `setExamDates()` but no `setExecutionCode()`.

### 5.4 Teacher-owned actions are placed in the coordinator's controller

מתווה scenario 5 says the **teacher** sets the dates and the code: "המורה מגדירה מועד
(תאריך וזמן) פתיחה ומועד סגירה. **המורה** מגדירה קוד ביצוע". SUC-6's primary actor is the
teacher. But `setExamDates()` and `releaseFromDrawer()` sit in **`ExamApprovalController`**
— the coordinator's controller. The responsibility is in the wrong class.

### 5.5 Setting the code is allocated to the wrong use case

Requirement 37 and SUC-8 put "define the 4-character code" in *"managing the exam during
execution"*. But SUC-7's own pre-condition says the code must already exist —
"המורה הגדירה קוד ביצוע בן 4 שדות לבחינה [SUC-8]". So SUC-8 must run *before* SUC-7, yet it
is also the use case for changing time *during* SUC-7. מתווה groups code-setting with
release-from-drawer (scenario 5), which is the more natural place.

### 5.6 "Exams she wrote" vs "courses she teaches" — two different rules

- מתווה 10: "מורה יכולה לצפות בתוצאות בחינות **שכתבה**" (that she wrote).
- Requirement 59: "כל הבחינות **שכתבה** (גם אם בוצעו ע"י מורות אחרות)".
- **But my SUC-10 says**: "מורה צופה בציוני התלמידות **בקורסים שהיא מלמדת**" (courses she teaches).

These are different sets of exams. A teacher can teach a course using an exam somebody else
wrote, and can write an exam somebody else runs.

### 5.7 It is undefined which teacher marks and approves a grade

If teacher A wrote the exam and teacher B released it to her own class, who reviews and
approves the grades? מתווה 8 says only "אישור הציון על ידי **המורה**" — *the* teacher.
Requirement 50 and SUC-9 say the same. Nothing distinguishes author from executor.

### 5.8 Authentication contradicts where the user data lives

- **System description §8**: all user details including permissions are "**זמינים במסד הנתונים של המערכת**" — available *in our own database* — and the external system only *manages* them.
- **My SUC-1**: "ההזדהות מתבצעת **מול מערכת ניהול המשתמשים החיצונית**" — authentication is performed *against the external system*.
- **My class diagram**: `IUserManagementSystem.verifyCredentials(user, pwd)`.

There is no external system to call. §8 says the data is local.

### 5.9 Auto-logout will throw students out of exams

Requirement 76 auto-logs-out an inactive user. But a student reading a long question for
several minutes without clicking anything *is* inactive. Requirement 76 and SUC-7 collide,
and no document mentions the interaction.

### 5.10 The exam window and the exam duration can conflict

Acceptance test 2.10 fixes a window of 10:00–12:00. Requirement 41 starts a per-student
clock on ID entry. Nothing says what happens if a student starts at 11:55 with a 90-minute
exam. Does she get 5 minutes, 90 minutes, or is she refused?

> **Settled on 2026-07-30: she gets 5 minutes.** Requirement 45 closes the exam "עבור כל
> התלמידות" at the end of the exam time, so the window's close is a real end and not only a
> bar on starting. She is told when she starts that she has 5 minutes rather than 90, warned
> five minutes before the close, and handed in automatically when it arrives.

### 5.11 "Factor" is defined nowhere

Requirement 77 lets a teacher "לתת פקטור" after approving grades. `Grade.factor : int`
exists. But: is it added (+5) or multiplied (×1.1)? Acceptance test 3.6 requires grades to
stay between 0 and 100 — so is the result capped? And acceptance test 3.3 uses the word
"פקטור" as the *explanation text for a manual change*, treating it as the same mechanism,
while requirement 77 makes it a separate bulk operation.

### 5.12 The bot's knowledge sources can be added but never edited or removed

מתווה 13.2 requires "ניתן **לערוך** את מקורות המידע" and 13.3 requires other teachers to be
able to edit them. `BotController` has `addKnowledgeSource(bot, src)` only — no update, no
remove. This is the same shape of gap as the missing question deletion.

### 5.13 Two screens required by the מתווה do not exist in the design

- **A main menu per role.** מתווה 1: "לאחר הכניסה מוצג **תפריט מתאים** לכל משתמשת". The class table lists nine screens; none is a menu.
- **A read-only data browser for the principal.** מתווה 11: "מנהלת יכולה לראות את מאגר השאלות, המבחנים ותוצאות המבחנים". `ReportScreen` covers reports (the second half of SUC-12); nothing covers browsing the raw data.

### 5.14 Rejection is supposed to be "sent" to the teacher, but nothing sends it

Requirement 33: "סיבת הדחייה **תישלח** למורה ותישמר במערכת" — *sent*, not merely stored.
Combined with NFR 18 (no manual refresh) this implies a live notification. There is no
notification class or mechanism anywhere in the design.

### 5.15 Points-per-question: one value or one per question?

System description §3.2 says "מספר הנקודות של **כל שאלה**". My sequence diagram calls
`setPointsPerQuestion(points)` — a single value for the whole exam. My class diagram puts
`points : int` on **`ExamQuestion`** — one value per question. The class diagram is the
correct reading, and it is also the only one that can make the total come to exactly 100
when the question count does not divide 100 (e.g. 3 or 7 questions). The sequence diagram
is the odd one out.

### 5.16 Two more acceptance tests describe features with no requirement

Beyond the "total = 100" case already known:
- **Test 3.10** — bulk-approve all 30 grades at once. No requirement mentions bulk approval.
- **Test 3.12** — change a grade *after* final approval. No requirement mentions reopening an approved grade.
- **Test 4.11** — the student is shown her actual solving time. Requirement 46 records it but never says the student sees it.

### 5.17 Points where the two Tier-1 documents disagree — resolved

- **Execution code format.** מתווה 5.3 says "4 **ספרות**" (4 digits); system description §4 and requirement 37 say "4 שדות – ספרות ואותיות" (digits and letters). *Already resolved in the project instructions: accept 4 characters, each a digit or a letter, which satisfies both. To be mentioned in the report.*
- **Bot blocking scope.** System description §6.2 is the more precise text — "לא יהיה זמין **לתלמידות הנבחנות** באותו קורס" (unavailable *to the students being examined*). מתווה's shorter "הבוט אינו זמין בזמן ביצוע בחינה" could be misread as blocking every student in the course. *The already-agreed decision — block only the student inside an active exam session — matches the more precise source. No change needed.*

### 5.18 Things that are consistent (checked, no action needed)

- Question ID digit layout: system description §3.1 table and requirement 17 agree exactly.
- Exam ID digit layout: system description §3.2 table and requirement 23 agree exactly.
- Decile buckets: acceptance test 3.14 defines 0–10, 11–20, … , 91–100. Unambiguous; will be implemented exactly so.
- Median: acceptance test 3.8 (5 grades → middle value) is the standard definition.
- Statistics are computed over **approved** grades — SUC-9 step 7 runs after approval in step 6, and test 3.7 counts approved exams only.
- The class diagram and the "classic" class diagram contain an identical set of classes; only the layout differs.
- The 10 OCSF/SimpleChat files to be excluded are exactly as described: `AdaptableClient`, `ObservableClient`, `AdaptableServer`, `ObservableServer`, `ObservableOriginatorServer`, `OriginatorMessage` (packages `ocsf.client` / `ocsf.server`), plus `EchoServer` and `ClientConsole` (`org.openjfx.demo2`), `ChatClient` (`client`), `ChatIF` (`common`).

---

## 6. Two implementation facts that will force small, deliberate deviations

Flagging these now because they change the class diagram and therefore need approval.

**6.1 `Question.image : Image` cannot stay as-is.** The class diagram types the image as
`Image`. A JavaFX `Image` is not serializable, and every entity has to travel over OCSF's
`ObjectOutputStream` between the two laptops. It must become `byte[]` in the entity, and be
turned into a JavaFX `Image` only inside the screen classes. This also matches the agreed
decision to store images as binary in the database rather than as file paths.

**6.2 Every entity must implement `Serializable`.** OCSF sends bare `Object` over an
object stream. The class diagram does not mention it. It also means the entity classes must
be packaged into **both** jars — so the Maven build needs a shared module, not just a client
module and a server module.

Both are genuine "detailed-design problems met during implementation" — good raw material
for the question the Assignment 3 Word document must answer.

---

## 7. Questions I cannot answer from the documents

> **STATUS: all twelve answered on 2026-07-29.** The settled decisions are recorded in
> [01_implementation_plan.md §0](01_implementation_plan.md). Three answers differ from the
> recommendation below and the table is left unedited on purpose, so the reasoning that was
> put to you stays on the record:
> - **Q5** — the teacher who *released* the execution grades it (confirmed).
> - **Q8** — ~~the close time is a deadline to **start**, not to finish. A student starting at
>   11:55 on a 90-minute exam finishes at 13:25.~~ **Superseded on 2026-07-30 at the
>   customer's instruction: the close time ends the exam for everybody.** She still may not
>   *start* at 12:00 or later — acceptance test 2.10's message, "מועד **פתיחת** הבחינה
>   הסתיים", is about the opening period and is unchanged — but a girl who started at 11:55
>   is now handed in automatically at 12:00 with her answers kept. Requirement 45 says
>   exactly that and the first reading left it with nothing to do. The "refuse under 5
>   minutes left" suggestion stays dropped: she may start late, and is told at once how long
>   she really has.
> - **Q11** — the Israeli check digit **is** validated, so all seeded IDs must be genuinely
>   valid numbers.

Each has my recommendation. Answering "agree with all" is a valid reply; override
individually where you disagree.

| # | Question | My recommendation |
|---|---|---|
| **1** | **Exam status (§5.1)** — how do we model in-drawer vs approval? | Split into two independent fields: an **approval status** (`PENDING_APPROVAL` / `APPROVED` / `REJECTED`) and a separate fact of whether an execution exists. "In the drawer" then simply means *no open execution* — matching system description §2.1. This kills the contradiction instead of papering over it. Documents needing update: requirements table (27), SUC-3 post-condition, acceptance test 1.1, class diagram enum. |
| **2** | **Versioning vs fixed ID formats (§5.2)** — how can two versions share a 5- or 6-digit ID? | Keep the 5-/6-digit number as the **human-facing catalogue number** exactly as specified, and add a separate `version` column plus an `isCurrent` flag. The database primary key becomes (ID + version). Every screen shows only the ID, so the graders see exactly the format the documents demand, and old versions still exist. |
| **3** | **Who sets dates and the execution code (§5.3, §5.4, §5.5)?** | The **teacher**, in one action, at release-from-drawer time: pick an approved exam → set open time, close time, execution code, number of attempts → creates one `ExamExecution`. This matches מתווה scenario 5 exactly. It moves `releaseFromDrawer` / `setExamDates` out of `ExamApprovalController` into `ExamExecutionController` and adds the missing code-assignment method. Requires updating the class diagram and SUC-6/SUC-8. |
| **4** | **Which exams may a teacher see (§5.6)?** | The union of both rules: exams **she wrote** (required by מתווה 10 + req 59) **and** executions **she released** (she must be able to mark her own class). Widest rule that satisfies every source. |
| **5** | **Who marks and approves grades (§5.7)?** | The teacher who **released** the execution, since those are her students. The author separately gets read-only statistics on her exam through SUC-11 — which is exactly what requirement 59 describes. |
| **6** | **Login and the "external" system (§5.8)?** | Keep the `IUserManagementSystem` interface from the class diagram, but implement it as a **local adapter reading our own `users` table**. Nothing external is called. This honours §8 ("the data is in our database"), keeps the submitted design intact, keeps it swappable for a real external system later — and is easy to defend at the demo. |
| **7** | **Auto-logout during an exam (§5.9)?** | Suspend the inactivity timer for as long as the student has an exam session in progress. Otherwise requirement 76 actively breaks SUC-7. |
| **8** | **Window vs duration (§5.10)?** | Allow starting at any time inside the window, but the exam ends at **whichever comes first** — her personal time running out, or the window closing. Also refuse to start with under 5 minutes left, with a clear message. Needs your approval because it invents a rule the documents do not state. |
| **9** | **What is "factor" (§5.11)?** | **Additive** — the teacher enters "+5" and it is added to every selected student's final grade, capped at 100 (required by test 3.6) and floored at 0. Applied as a bulk action after approval, recorded in `Grade.factor` with a stored reason. Simplest to explain and to demo. |
| **10** | **Deleting a question that is already used in an exam (§4 item 2)?** | **Soft delete** — mark it not-current so it disappears from the bank and from future exam-building, but every exam that already contains it still shows it. A hard delete would corrupt marked historical exams. |
| **11** | **Is the student's ת"ז the same as `User.userId`?** And should the 9-digit Israeli check digit be validated? | Yes, `userId` is the ת"ז. Test 2.4 requires it to match the logged-in student. Recommend checking **format only** (9 digits) and the match against the logged-in user — **not** the check digit, so that seeded test data stays easy to write and read. |
| **12** | **Group number and member details** | Not needed until submission, but the jar names `G<N>_Client.jar` / `G<N>_Server.jar` are baked into the Maven build. Tell me the group number whenever convenient — before then I will use a placeholder. |

---

## 8. What happens next

**Done.** Phase 0 closed on 2026-07-29. The Phase 1 plan is
[01_implementation_plan.md](01_implementation_plan.md) — package structure mapped to the
three tiers, the full MySQL schema with versioning, the seed-data approach, the complete
client-server message protocol, all 15 use cases mapped to classes and methods, where each of
the five patterns is used, how server push works, the Maven and JavaFX setup, the milestone
order beginning with a walking skeleton, the risk list, and the list of exactly which
submitted Assignment 1 and 2 documents each decision makes out of date (plan §9).
