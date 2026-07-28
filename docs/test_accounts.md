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

## Milestone 1

Only one account exists so far, seeded into the throwaway `m1_skeleton_user`
table by the server on first start:

| Username | Password | Full name |
|---|---|---|
| `teacher1` | `teacher1!T` | Test Teacher One |

Milestone 2 replaces that table with the real `users` table and seeds the full
set: 1 principal, 4 coordinators, 8 teachers and ~40 students.

## A note for the report

Salted SHA-256 is the correct *shape* — per-user salts mean two people with the
same password still get different hashes — but it is not what a production system
would use today. SHA-256 is fast, and being fast helps an attacker guess. A real
deployment would use a deliberately slow function such as bcrypt, scrypt or
Argon2. SHA-256 was chosen here because it is in the Java standard library and
needs no extra dependency.
