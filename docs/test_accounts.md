# HSTS — test accounts

Every account listed here is **fictional test data** for a student project. There
is no real person, no real school, and no real personal information anywhere in
this system. That is why the login convention can safely be written down in a
public repository.

## Why this file exists

Passwords are stored as **salted SHA-256 hashes**, never as plain text. That is
the right thing to do, but it creates one practical problem: when a login fails
during the live demo, you cannot look the password up in the database, because it
is not in there.

So the seeded accounts follow a fixed, predictable convention instead.

## The convention

```
username  =  <role><number>
password  =  <username>!<first letter of the role, upper case>
```

| Role | Username pattern | Example username | Example password |
|---|---|---|---|
| Teacher | `teacher<n>` | `teacher1` | `teacher1!T` |
| Subject coordinator | `coordinator<n>` | `coordinator2` | `coordinator2!C` |
| Student | `student<n>` | `student14` | `student14!S` |
| Principal | `principal` | `principal` | `principal!P` |

## The full seeded set

**55 users:** 1 principal, 4 subject coordinators, 8 teachers, 40 students — and
the two accounts below, which exist to make the whole school visible from one
login.

### Two accounts that cover everything

| Username | Password | Full name | ID (typed before an exam) | What she has |
|---|---|---|---|---|
| `teacher9` | `teacher9!T` | Orit Nahum | `100000546` | Teaches **all 8 courses**, so all 4 subjects |
| `student41` | `student41!S` | Liat Barnea | `100000553` | Studies **all 8 courses**, so all 4 subjects |

Nobody else has more than two courses, which makes some screens hard to judge: a
course picker with one entry proves very little, and a per-subject report drawn
from a girl enrolled in three of the eight courses shows three quarters of
nothing. These two give every course picker in the system something in all four
subjects.

Neither invents a rule. The client story says *"כל קורס מועבר ע"י מורה אחת או יותר
ויש תלמידות הלומדות את הקורס"* — a course has one **or more** teachers, and
students who study it. Nothing in it, or in requirement 13, caps how many courses
one person may take.

**What Orit sees:** all 8 courses in her question bank, the entire current
question bank to edit (requirement 14 limits a teacher to courses she teaches —
here that is everything), and every approved exam in the school in her
release list. She has written nothing herself, so her own reports are empty until
she writes an exam.

**What Liat sees:** 8 courses, published results in Plane Geometry and Mechanics
(the demo seeder seats her like anybody else), every active study bot in the
school, and the live sitting `NOW1` open to her.

### The coordinator who teaches nothing

`coordinator3` (Tamar Barak, Literature) coordinates a subject but **teaches no
courses at all**. She is in the data on purpose: nothing in the documents says a
coordinator must teach, and the system behaves differently for her - releasing an
exam is done by the teacher *of the course*, so "Release an exam" is not on her
menu. The other three coordinators do teach a class each.

| Username | Password | Name | Subject | Teaches |
|---|---|---|---|---|
| `coordinator1` | `coordinator1!C` | Noa Katz | Mathematics | Algebra (02) |
| `coordinator2` | `coordinator2!C` | Maya Shapira | Physics | Electricity (04) |
| `coordinator3` | `coordinator3!C` | Tamar Barak | Literature | **nothing** |
| `coordinator4` | `coordinator4!C` | Yael Golan | Biology | Human Anatomy (08) |

### Milestone 1 (historical)

Milestone 1 had a single account in a throwaway `m1_skeleton_user` table —
`teacher1` / `teacher1!T`, "Test Teacher One". Milestone 2 replaced that table
with the real `users` table.

## A note for the report

Salted SHA-256 is the correct *shape* — per-user salts mean two people with the
same password still get different hashes — but it is not what a production system
would use today. SHA-256 is fast, and being fast helps an attacker guess. A real
deployment would use a deliberately slow function such as bcrypt, scrypt or
Argon2. SHA-256 was chosen here because it is in the Java standard library and
needs no extra dependency.
