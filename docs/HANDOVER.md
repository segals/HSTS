# Handover — everything a new session needs to know

Written to be read once, at the start of a new conversation on a different
machine. It is the project's state, its rules, and the things that are true but
not obvious from the code.

**Read this, then `CHANGELOG.md` from the bottom up as far as you need.**

---

## 1. What this is

**HSTS — High School Test System.** Software Engineering **203.3140**, Spring
2026, **Group 1**, Assignment 3.

A client–server exam system for a high school: a question bank, exam building,
sitting exams, automatic and manual marking, statistics and reports, and a
per-course AI study bot. Java over TCP/IP with MySQL, delivered as **two runnable
jars on two separate machines**.

The requirements come from Hebrew source documents (Assignment 1 requirements,
use cases, textual specifications, acceptance tests, the מתווה scenario guide,
and the system description). They are summarised in
[`docs/00_understanding.md`](00_understanding.md), including the contradictions
found **between** them.

---

## 2. How I am asked to work — these are standing instructions

They come from the user and they have not changed. They matter more than any
habit.

- **Plain, simple language.** *"I am a student, not an expert. Never use jargon
  without explaining it."*
- **Never invent a requirement.** If the source documents do not say it, say so
  and ask. Do not quietly design a rule and present it as required.
- **Report honestly.** *"If something is broken, unfinished or skipped, say so
  plainly with the evidence. Never claim something works without actually
  checking."*
- **Keep code, schema, design documents and test results in sync**, and keep a
  running change log recording **what** changed, **why**, and **how**.
- **Validation loop.** *"Don't finish until you checked everything is right and
  edge cases."* In practice: run the whole suite, three passes, with a reset
  between them, and once from the packaged jars.
- **No `Co-Authored-By` trailer on commits.** Claude must not appear as a
  contributor on the GitHub repository.

### Security rules, stated by the user and not negotiable

- The repository is **public**. **Never commit a secret.**
- The Gemini API key must **never** be written into any file git tracks.
- **Never write the key anywhere yourself** — the user pastes it in.
- **Never create a config file for secrets inside the project folder.**
  `.gitignore` is not enough protection for a public repository.
- The key and the MySQL password live in `%USERPROFILE%\.hsts\config.properties`,
  outside the repository.
- **Only the server calls Gemini.** The client never does, and has no API key and
  no database driver.

---

## 3. The state of the work

**Milestones 1–15 are built, tested and pushed.** The system is complete and
working: every use case has a screen and a server path behind it.

**Milestone 16 is the one outstanding piece of work:**

1. Run the Assignment 1 **acceptance tests by hand** and fill in the results
   table. *(The acceptance test document only covers SUC-3, 7, 9 and 10.)*
2. Finish the redlines in
   [`docs/03_document_updates.md`](03_document_updates.md) §6 — class diagram
   changes, textual specifications, the requirements table and the use case
   table.
3. Produce the **Word document** and the **ZIP** for submission.

**Also outstanding:** the **two-laptop LAN test** — server on one machine, client
on another, over the network. Deferred by the user, recorded in the changelog as
required before submission. Everything so far has been tested on one machine.

### Known gaps, deliberately left, all reported to the user

| Gap | Why it stands |
|---|---|
| **SUC-10's subject-wide grade view for a coordinator is not built.** She gets only what she has as a teacher | Reported and offered; the user has not asked for it |
| Requirement 73 is knowingly broken by bot deletion | Written up in `docs/03_document_updates.md` §7 |
| A coordinator may approve **her own** exam | Nothing in the sources gives a subject a second coordinator |
| A coordinator may coordinate only **one** subject | Offered to the user, not asked for |

---

## 4. How it is built and run

**JDK 26** (26.0.1), **Maven 3.9+**, **MySQL 8.0** on the server machine only.
JavaFX 26 with the `win` classifier. Fat jars via the shade plugin.

```bash
mvn -o clean package          # -> hsts-server/target/G1_Server.jar
                              #    hsts-client/target/G1_Client.jar
java -jar G1_Server.jar       # a window asks for the port and MySQL details
java -jar G1_Client.jar       # a window asks for the server address and port
```

Both jars contain the same shared classes and talk over an object stream.
**Always rebuild and copy both together** — a mismatched pair fails on the first
message with `InvalidClassException`.

The database is created and migrated automatically on first run.

---

## 5. The shape of the code

```
hsts-ocsf      the reused OCSF framework, unmodified (external reuse)
hsts-common    entities + the message protocol - packaged into BOTH jars
hsts-server    <<Control>>, DAOs, push services      -> G1_Server.jar
hsts-client    <<Boundary>>, JavaFX screens          -> G1_Client.jar
```

Patterns actually used, not just named: **Singleton** (`DBController`,
`HSTSServer`), **Strategy** + **Factory** (`ReportStrategy`, `ReportFactory`),
**DAO**, **Observer** (server push), and the two boundary interfaces
(`IUserManagementSystem`, `IStudyBotService`).

### Things that will bite you if you do not know them

- **Every request goes through one dispatch method** in `HSTSServer`. That is
  where activity is logged and where the principal is told the school changed. Do
  not add a second place.
- **The server pushes; the client never polls.** NFR 18 forbids a Refresh button.
  A screen that shows data somebody else can change must handle a push.
- **`Transport.send` is synchronised on the connection.** OCSF's `sendToClient`
  is not, and two threads writing at once corrupt the object stream. This was a
  real defect found by `StreamRaceTest`; do not write to a connection directly.
- **A push with no message must not be shown.** The exam clock ticks once a
  second with no message; showing it blanks the status line.
- **Versioning:** questions and exams have a composite key of (id + version) with
  an `is_current` flag. An exam pins the **question versions** it was built with.
  Editing never overwrites.
- **Ids carry meaning.** A question is `%03d` + 2-digit course = 5 characters. An
  exam is `%02d` + course + subject = 6 characters, so **99 exams per course, and
  no more**. A sitting code is exactly 4 characters.
- **The clock is the server's.** `StudentExam.effectiveEnd()` is the earlier of
  her personal deadline and the sitting's close.
- **The demo data resets** with `ResetNow`: 55 users, 80 questions, 9 exams, 7
  sittings, 78 marked papers, 3 bots.

---

## 6. Logging in

The convention is in [`docs/test_accounts.md`](test_accounts.md). Passwords are
salted SHA-256 hashes, so they cannot be looked up — the convention is the point.

```
username = <role><number>          password = <username>!<ROLE INITIAL>
```

| Who | Username | Password |
|---|---|---|
| Principal | `principal` | `principal!P` |
| Teacher | `teacher1` | `teacher1!T` |
| Coordinator | `coordinator1` | `coordinator1!C` |
| Student | `student1` | `student1!S` |
| **Teaches all 8 courses** | `teacher9` | `teacher9!T` |
| **Studies all 8 courses** | `student41` | `student41!S` |
| **Coordinator who teaches nothing** | `coordinator3` | `coordinator3!C` |

Students type an **Israeli ID** before an exam: `teacher9` is `100000546`,
`student41` is `100000553`.

---

## 7. Testing

Everything is in [`tests/`](../tests/README.md) — twenty-three harnesses that
drive the real system, and no JUnit anywhere.

```bash
bash tests/run-all.sh <mysql-user> <mysql-password> reset
bash tests/run-screens.sh
```

**As of this handover: 1126 checks across 19 suites, all passing.** 21/21 screens
load, 19/19 with no cut-off text at four sizes.

**Always pass `reset` on a full pass.** Ninety-nine exams per course is a hard
ceiling and a third pass without a reset exhausts course 01 — every exam-building
check then fails at once, which looks like a broken system and is a full disk.

---

## 8. Where to look

| File | What is in it |
|---|---|
| `CHANGELOG.md` | Every change, why and how. **The most useful file in the repository.** Read it from the bottom |
| `docs/00_understanding.md` | The 15 use cases and the contradictions between the source documents |
| `docs/01_implementation_plan.md` | Architecture, schema, protocol, milestones, risks |
| `docs/03_document_updates.md` | Every place the submitted Assignment 1 and 2 documents are now wrong, and the suggested wording |
| `docs/test_accounts.md` | The login convention and the seeded set |
| `tests/README.md` | What each harness covers and how to run them |
