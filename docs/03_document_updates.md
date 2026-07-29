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
