# Changes needed in the submitted Assignment 1 and 2 documents

Phase 3 deliverable. Each entry says **which document**, **what it says now**,
**what it should say**, and **why it changed**. Nothing here is a silent edit —
every one is a decision that was made during implementation and can be defended.

---

## 1. Acceptance test 3.3 — changing a mark by hand

**Document:** `סעיף 4 - בדיקות קבלה.docx`, SUC-9 table, row 3.3

### As submitted

| Field | Text |
|---|---|
| קלט | שינוי הציון ל-90. המערכת מקפיצה חלון חובה להזנת הסבר. המורה מקלידה: "פקטור". לחיצה על **"שמור"**. |
| פלט צפוי | המערכת שומרת את הציון המעודכן, הודעת הצלחה מופיעה. |
| מצב אחרי | הציון עודכן ל-90, הנימוק נשמר ב-DB וניתן למעקב. |

### As it should read

| Field | Text |
|---|---|
| קלט | שינוי הציון ל-90. המורה מקלידה נימוק: "פקטור". לחיצה על **"אשר ופרסם"**. |
| פלט צפוי | המערכת שומרת את הציון המעודכן ואת הנימוק, מפרסמת אותו לתלמידה, והודעת הצלחה מופיעה. |
| מצב אחרי | הציון עודכן ל-90, הנימוק נשמר ב-DB וניתן למעקב, והציון גלוי לתלמידה. |

### Why

The marking screen had four separate presses — save the mark, save the overall
comment, save a comment beside each question, then approve. On a ten-question
paper that is **thirteen buttons**, and worse, it let a teacher publish a mark
while a comment she had typed was still sitting unsaved on screen.

There is one button now. It sends the mark, the reason and every comment
together, and the server writes nothing unless the whole request is acceptable.

**What did not change:** requirement 52. An explanation is still compulsory
whenever the mark is moved by hand — it is simply checked at the moment of
publishing instead of at a separate save. Verified by `M10Test` §2 and §4.

**What this costs:** a mark can no longer be saved as a draft without publishing
it. No numbered requirement asks for that; it was implied only by this test's own
wording. NFR 21 (interface quality) argues the other way.

**Also dropped:** the words *"המערכת מקפיצה חלון חובה"* — a compulsory pop-up
window. The reason box is on the screen the whole time instead, so there is
nothing to pop up. The compulsion is real, but it is enforced when the button is
pressed rather than by a modal dialog.

---

## 2. Acceptance test 3.4 — changing a mark with no reason

**Document:** `סעיף 4 - בדיקות קבלה.docx`, SUC-9 table, row 3.4

### As submitted

| Field | Text |
|---|---|
| קלט | שינוי הציון ל-90, השארת תיבת הנימוק ריקה, לחיצה על **"שמור"**. |
| פלט צפוי | הודעת שגיאה: "חובה להכניס הסבר לשינוי הציון הידני". |
| מצב אחרי | הציון לא נשמר, התהליך נעצר עד להזנת טקסט. |

### As it should read

| Field | Text |
|---|---|
| קלט | שינוי הציון ל-90, השארת תיבת הנימוק ריקה, לחיצה על **"אשר ופרסם"**. |
| פלט צפוי | הודעת שגיאה המציינת שנדרש נימוק ושדבר לא פורסם. |
| מצב אחרי | הציון לא נשמר, **לא פורסם**, ואף הערה שהוקלדה באותה לחיצה לא נשמרה. התהליך נעצר עד להזנת טקסט. |

### Why

Same change of button. The end state is **stronger** than the submitted version,
not weaker: because one press now carries the mark *and* the comments, a refusal
has to leave the whole paper untouched — otherwise a rejected mark could still
have saved a comment. The server checks everything before it writes anything.

Verified by `M10Test` §2, which asserts after the refusal that the mark is still
50, that it is not published, and that the comment sent alongside the bad mark was
**not** stored.

---

## 3. Acceptance test 3.5 — adding a comment to a question

**Document:** `סעיף 4 - בדיקות קבלה.docx`, SUC-9 table, row 3.5

### As submitted

> המורה לוחצת על שאלה שגויה, מוסיפה טקסט: "שימי לב לחילוק", **שומרת ומאשרת**.

### As it should read

> המורה מקלידה בתיבת ההערה של השאלה השגויה: "שימי לב לחילוק", ולוחצת **"אשר ופרסם"**.

### Why

"שומרת ומאשרת" is two presses. It is one now. The expected output and end state
are unchanged — the comment is stored against the question and becomes visible to
the student on approval.

---

## 4. Acceptance test 3.11 — a paper still being sat

**Document:** `סעיף 4 - בדיקות קבלה.docx`, SUC-9 table, row 3.11

### As submitted

> כפתור הבדיקה מנוטרל/מוסתר או מופיעה הודעה: "בחינה טרם הוגשה".

### Clarification, not a change

The test offers two acceptable outcomes. We implement the **second**: the student
appears in the teacher's list marked *"still sitting · nothing to mark yet"*, and
selecting her says so plainly. The row is deliberately **not** hidden.

Hiding her caused a real defect. The sittings list counts everybody who *started*,
so a sitting announcing "1 sat it" opened onto an empty student list with nothing
to explain it — reported from the screen, fixed, and recorded in `CHANGELOG.md`.

---

## 5. Acceptance tests 2.11 and 4.6 — they assume a web browser

**Document:** `סעיף 4 - בדיקות קבלה.docx`, rows 2.11 and 4.6

- 2.11 says *"the browser was refreshed"*.
- 4.6 says *"change a URL parameter"*.

This is a desktop JavaFX client. There is no browser and no URL.

- **2.11** becomes: the client was closed and reopened mid-exam. Covered by
  `M7Test`.
- **4.6** becomes: a student asks the server directly for another student's
  submission number. The server answers **"That exam does not exist"** — the same
  wording as for a genuinely missing paper, so trying numbers reveals nothing.
  Covered by `M9Test` §10.

Both were flagged in `docs/00_understanding.md` §4 item 7 before implementation
began.

---

## 6. Still to be written up

These are listed in `docs/01_implementation_plan.md` §9 and are not yet drafted
here. They belong to Phase 3 alongside the entries above.

| Document | What changed |
|---|---|
| Class diagram (Assignment 2) | `GUIScreen` base class; `ExamStatus.IN_DRAWER` deleted and derived instead; version fields on `Question` and `Exam` |
| Class diagram (Assignment 2) | `PushEvent` / `PushService` — required by NFR 18, absent from the submitted design |
| Textual specifications | SUC-8: setting the execution code moved to release time, where מתווה scenario 5 puts it |
| Textual specifications | SUC-9: who marks — the teacher who **released** the sitting, not the author |
| Requirements table | Requirement 15's "subject tag" versus the `topic` field automatic building actually needs |
| Use case table | SUC-10 says "courses she teaches"; requirement 59 and מתווה 10 say "exams she wrote" — these are different sets |
| Class diagram (Assignment 2) | `StudentExam` carries the sitting's close time beside her own deadline, and `effectiveEnd()` picks the earlier — see §9 |
| Textual specifications | SUC-7: the exam now ends at the sitting's close for anyone still inside, and the warning she receives depends on which end binds — see §9 |

---

## 7. Requirement 73 versus deleting a bot

**Documents:** requirements table, requirement 73; class diagram, `Bot`

### As submitted

> 73. המערכת תשמור את השאלות שנשלחו לבוט ואת התשובות שהתקבלו.

The system keeps the questions sent to the bot and the answers received.

### What the system now does

A teacher may **delete a bot**, and its stored questions and answers are deleted
with it. Requirement 73 is therefore satisfied for the life of the bot and no
longer.

### Why

The customer asked for a plain delete: *"I want her to be able to delete a bot in
a simple way"*. The alternative reading of requirement 73 - refuse to delete
anything that has ever been used - would leave a teacher permanently stuck with a
bot she created by mistake, and there is no requirement asking for that either.

The conflict is **narrowed rather than ignored**:

- deleting requires confirmation, and the confirmation **names how many stored
  questions will be destroyed**, so the cost is stated before it is paid;
- the count comes from the server, so it is the real number;
- deactivating (requirement 60) remains the way to take a bot out of service
  **without** losing anything, and is what the screen recommends;
- nothing else in the system deletes a conversation.

### Suggested wording

> 73. המערכת תשמור את השאלות שנשלחו לבוט ואת התשובות שהתקבלו, כל עוד הבוט קיים.
>     מחיקת בוט מוחקת גם את היסטוריית השיחות שלו, ולכן היא דורשת אישור מפורש
>     שמציין את מספר הרשומות שיימחקו.

---

## 8. One bot per course became several, one active

**Documents:** requirements table, requirement 67; class diagram, `Bot` multiplicity

### As submitted

Requirement 67 says that if a course has more than one teacher and a bot exists,
another teacher may add knowledge sources **to the existing bot**. The class
diagram and the first implementation both read that as *one bot per course*,
enforced by a UNIQUE key on `bot.course_code`.

### What the system now does

A course may have **several bots**, of which **at most one is active**. The
customer asked for this directly.

### Why it does not break requirement 67

Requirement 67 grants a colleague the ability to add to an existing bot. It does
not forbid a second bot. Both halves still hold:

- any teacher of the course may add material to any of its bots, and the material
  list names who added each piece;
- a student is unaffected, because requirement 70 speaks of *the* course bot and
  there is still exactly one she can reach - the active one.

The UNIQUE key is replaced by a plain index, and the one-active rule lives in
`BotController`. It is a condition on a *subset* of rows, which MySQL cannot
express as an index - and putting it in code lets it explain itself: switching one
bot on switches the course others off, and says which.

### Suggested wording

> 67. לקורס יכולים להיות כמה בוטים, אך רק אחד מהם פעיל בכל רגע נתון. כל מורה
>     המלמדת את הקורס יכולה להוסיף או להסיר מקורות ידע מכל אחד מהבוטים של הקורס,
>     ולהחליף איזה מהם פעיל.

---

## 9. The sitting's closing time ends the exam for everybody

**Documents:** requirements table, requirements 43 and 45; SUC-7 textual
specification; acceptance test 2.6

### As submitted

Two requirements describe two different endings and neither says which wins:

- **41** — *"עם הזנת מספר הזהות מתחיל מד-הזמן; עם תום הזמן המוקצה הבחינה נסגרת
  אוטומטית"* — her own clock, started when she enters her ID.
- **45** — *"בסיום זמן הבחינה, המערכת תסגור את הבחינה עבור כל התלמידות ותשמור את
  התשובות שהוזנו"* — at the end of the exam time the system closes it **for all
  the students**.

Question 8 of the understanding document read the sitting's close as a deadline
to *start* only, so a girl who began at 11:55 of a 10:00–12:00 window with 90
minutes allowed worked until 13:25. That answer was accepted on 2026-07-29.

### What the system now does

**Changed at the customer's instruction on 2026-07-30.** An attempt has two ends
and **the earlier one wins**:

- she may still not *start* after the window closes — acceptance test 2.10's
  message is about the *opening* period and is unchanged;
- a student already inside is **handed in automatically when the sitting closes**,
  with everything she had chosen kept;
- she is told at the start how long she really has: *"This sitting closes for
  everyone at 12:00, so you have 5 minutes rather than the full 90."*

### One warning per student, and it is the relevant one

Requirement 43 asks for a popup at 90% of the exam time. With two possible ends,
90% of *her own* time can easily be a moment that never arrives — a girl who
starts ten minutes before the room closes still has 89% of her ninety minutes in
hand when she is handed in. So:

| Which end will stop her | What she is sent |
|---|---|
| Her own allowance | Requirement 43's popup at 90%: *"90% of the exam time has gone. You have 6 minutes and 42 seconds left."* |
| The sitting's close | Five minutes before it: *"This exam closes for everyone at 13:30. You have 4 minutes and 12 seconds left, and your paper will be handed in for you."* |

**Exactly one of the two reaches any one attempt.** Two popups saying nearly the
same thing is worse than one saying the right thing, and the wrong one of the two
is actively misleading.

### Why this is a correction, not a new rule

The first reading left requirement 45 with nothing to do: if every student's exam
ended on her own clock, nothing ever closed anything "עבור כל התלמידות". The new
reading gives both requirements work — 41 ends her exam when her time runs out,
45 ends it when the room closes, and whichever comes first is the one she meets.

Both endings are still recorded as *"did not finish by herself"*, because
requirement 48 counts started / finished / did not manage — two outcomes, not
three.

### Suggested wording

> 43. לקראת סיום הבחינה תוצג לנבחנת התראה אחת בלבד, המתאימה למועד הסיום הרלוונטי
>     עבורה: אם זמנה האישי הוא שיסתיים ראשון — התראה בסיום 90% מזמנה, הכוללת את
>     הזמן שנותר בדקות ובשניות; אם מועד סגירת הביצוע הוא שיסתיים ראשון — התראה
>     חמש דקות לפני הסגירה, הכוללת את שעת הסגירה ואת הזמן שנותר.
>
> 45. בסיום זמן הביצוע, המערכת תסגור את הבחינה עבור כל התלמידות שעדיין נבחנות,
>     תשמור את התשובות שהוזנו ותודיע לכל אחת מהן. תלמידה שהתחילה לפני מועד
>     הסגירה תסיים לכל המאוחר במועד הסגירה, גם אם נותר לה זמן אישי.

---

## 10. Two additions to the screens: who an approval waits for, and unread badges

**Documents:** class diagram (`ExamStatus`, `Grade`, `User`); requirements table

Asked for by the customer on 2026-07-30 as user-interface improvements. Neither
changes a rule: who may approve what, and what an approval does, are exactly as
before.

### As submitted

`ExamStatus` has three values and the class diagram shows no display text for
them. Nothing in the requirements says how a waiting state should be worded, and
nothing describes a notification count anywhere in the system.

### What the system now does

**Every waiting thing names the role it is waiting for.** An exam awaiting its
coordinator reads *"Waiting for Subject Coordinator approval"* wherever it is
shown; a mark awaiting its teacher reads *"waiting for your approval"* on the
marking screen and *"Waiting for your teacher to approve it"* on the student's.

**The menu carries unread counts**, as a phone does: a red circle at the right of
the entry, showing how many things are waiting for that user.

| Role | Entry | Counted |
|---|---|---|
| Coordinator | Approve or reject exams | exams in her subject awaiting her decision |
| Teacher | Mark and approve grades | papers on her sittings not yet approved |
| Student | Take an exam | sittings open now she may still enter |
| Student | My grades | marks published since she last looked |

The principal has none: she approves nothing (system description §7.3).

### Changes to the submitted design

| Class | Change |
|---|---|
| `ExamStatus` | a second attribute, the role an approval waits for, alongside the display text |
| `Grade` | `getWaitingFor()` - who must approve this mark, or nothing once approved |
| `User` | `resultsSeenAt` - when this student last opened her results |
| *(new)* `PendingCounts` | the four counts, carried in one reply |
| *(new)* `PendingCountsController` | answers "what is waiting for this user" |

`User.resultsSeenAt` is the only new stored fact in either change. It exists
because "marks she has not read" is the one count that cannot be derived from
rows that already exist - nothing recorded whether she had looked. It sits on the
person rather than on each mark because she reads the list, not the rows.

### Suggested wording, if the requirements table is to mention it

> 79. בכל מסך שבו מוצג פריט הממתין לאישור, תוצג במפורש בעלת התפקיד שממנה מצופה
>     האישור.
>
> 80. בתפריט הראשי יוצג לצד כל פעולה מונה של הפריטים הממתינים לטיפולה של
>     המשתמשת. המונה מתעדכן מעצמו ונעלם כאשר לא נותרו פריטים ממתינים.

---

## 11. Exams have a name, chosen by their author

**Documents:** requirements table (a new derived requirement); class diagram, `Exam`

### As submitted

Requirements 22 to 26 say what an exam is made of:

| # | What it gives an exam |
|---|---|
| 22 | questions from the bank |
| 23 | a unique 6-digit number: 2 exam + 2 course + 2 subject |
| 24 | a duration in minutes and points per question |
| 25 | free text in two categories - for the students, and for the teacher |
| 26 | the name of the teacher who wrote it |

**No name.** So every list, every heading and every message identified an exam as
"020101", and a teacher who wrote three exams this week had to remember which was
which.

### What the system now does

Every exam has a **name**, typed by whoever creates it, and it is **compulsory**.
The 6-digit number is unchanged - still generated by the system, still unique,
still in the format requirement 23 fixes - and the two are shown together
everywhere, name first:

> Plane Geometry mid-term  ·  010101

### Why compulsory

A name that may be left blank is a name half the exams will not have, and a list
that is half names and half numbers is harder to read than a list of numbers. The
customer asked for it directly.

### Why this is a small change to the documents, not a large one

The shape already exists in them: requirement 66 has a teacher naming her study
bot at the moment she creates it. This is the same idea applied to an exam.

Nothing is taken away: requirement 23's number is what the release code, the
marking screen and the reports still key on, and it is what a student would quote.

### Suggested wording

> 26א. בעת יצירת בחינה המורה מזינה שם לבחינה. השם הוא שדה חובה ומוצג לצד המספר
>      המזהה בכל מסך שבו מופיעה הבחינה. המספר המזהה בן 6 הספרות אינו משתנה.

---

## 12. A coordinator need not teach

**Documents:** requirements table, requirement 12; use case table, SUC-6

### As submitted

Requirement 12 says *"לכל קורס יש רכזת מקצוע"* and the client story lists, for each
course, its teachers **and** its coordinator - as two separate facts. Nothing says
a coordinator teaches, and nothing says she does not.

### What the system now does

The test data contains one of each: three coordinators who also teach a class, and
one (`coordinator3`, Literature) who teaches none.

For the one who teaches nothing, **"Release an exam" is not on her menu at all**.
Releasing is done by the teacher of the course (SUC-6, requirement 37, מתווה 5), so
there is nothing she could ever release and the screen could only ever be empty.

Her approval powers are untouched - those are scoped to her **subject**, and have
nothing to do with teaching.

**Marking is deliberately left on her menu.** It is scoped to sittings she
*released*, at any time in the past, so a coordinator who taught last year still
has last year's papers to finish. Hiding it would strand them.

### Still missing, and worth listing

SUC-10 says *"רכזת המקצוע צופה בציונים של המקצוע שהיא מרכזת"* - a coordinator may
view the grades of the subject she coordinates. The system gives her only what she
gets as a teacher: her own courses' results. **There is no subject-wide grade view
for a coordinator.** It is not built, and no test asserts it.
