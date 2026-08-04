# The test harnesses

Twenty-three test programs and a few utilities. Nineteen of them start a real
server on a real socket, sign real users in and check what comes back; the other
four open the real screens and measure what JavaFX did with them.

They are **not** unit tests and there is no JUnit anywhere. The course does not
ask for one, and what was worth proving was that the whole stack works together —
which a mock cannot show.

Every one of them prints `==== passed N, failed M ====` and exits non-zero if
anything failed, so a runner can add them up.

## Running them

You need a MySQL running locally and the project built.

```bash
mvn -o clean package
bash tests/run-all.sh <mysql-user> <mysql-password> reset
bash tests/run-screens.sh
```

| Script | What it does |
|---|---|
| `run-all.sh <user> <pass> [reset]` | The nineteen server suites, against `target/classes` — so a change can be tested without repackaging |
| `run-jars.sh <user> <pass> [reset]` | The same nineteen, against the packaged jars only |
| `run-screens.sh` | The four harnesses that open the screens themselves. Needs a real display |
| `env.sh` | Works out where the repository is. Sourced by the others; not run directly |

The MySQL password is passed on the command line and is **never** written into
any file here. This repository is public.

### Always pass `reset` on a full pass

An exam id is two digits of exam number, two of course and two of subject, so a
course holds **ninety-nine exams and no more**. One full pass writes a few dozen
into course 01. The **third** pass in a row without a reset runs the course out
of ids, and then everything that builds an exam fails at once — which reads like
a broken system and is a full disk.

`reset` is a third argument rather than the default because a reset also throws
away anything set up by hand for a demo, and losing that silently would be worse
than a suite that fails and explains itself.

## What each one covers

### The nineteen server suites

| Suite | Covers |
|---|---|
| `M2Test` | Login, roles, the no-double-login rule (NFR 16) |
| `M3Test` | The question bank: adding, editing into a new version, deleting |
| `M4Test` | Building an exam by hand and automatically |
| `M5Test` | The coordinator approving and rejecting, and the reason reaching the author |
| `M6Test` | Releasing an exam to a class, and the four-character code |
| `M7Test` | Sitting an exam: the clock, the deadline, the forced close |
| `M8Test` | Automatic marking |
| `M9Test` | Marking by hand, approving, the factor |
| `M10Test` | The student's results |
| `M11Test` | Statistics and the histogram |
| `M13Test` | Reports — the strategy and the factory |
| `M14Test` | The study bot and everything around it (the largest, 153 checks) |
| `M15Test` | The six derived requirements left until last: 19, 39, 43, 61, 76, 77 |
| `NewUsersTest` | The two accounts that cover every course |
| `ClosingTimeTest` | The sitting's close ending the exam for everybody, and the one warning |
| `BadgeTest` | The unread counts, and that they move without anybody pressing anything |
| `StreamRaceTest` | Two threads writing to one OCSF connection — a real defect, found here |
| `StaffViewTest` | The principal's calendar and activity log, and a member of staff's to-do list |
| `LiveUpdateTest` | Course names, saving twice, and the principal's screens keeping up |

### The four screen harnesses

They load the FXML, lay it out and measure what JavaFX actually did. No database
and no server.

| Harness | Covers |
|---|---|
| `FxmlLoadTest` | Every screen loads and every `fx:id` is wired to a field |
| `MenuBadgeTest` | The unread badges: on the right entries, at the right end, hidden at nought |
| `TruncationTest` | No text cut off, at four window sizes, with hidden panes revealed |
| `ScreenBehaviourTest` | The calendar grid, the exam version history, and a message surviving the exam clock |

### Utilities, not tests

`ResetNow` (wipe and re-seed), `DbSmokeTest`, `MigrateCheck`, `WhoIsEnrolled`,
`GeminiDiag` and `GeminiProbe` (is the API key working), and the two older
end-to-end scripts `E2ETest` and `BotE2E`. They print what they find and are run
by hand when something needs looking at.

## Why they live here and not in `src/test`

They were written as free-standing programs, compiled with `javac` against the
built classes, so that a suite could be run in seconds against a change without
Maven rebuilding or a test framework deciding what "a failure" means. Moving them
under `src/test/java` would mean adopting JUnit, rewriting twenty-three
harnesses, and gaining nothing the course asks for.
