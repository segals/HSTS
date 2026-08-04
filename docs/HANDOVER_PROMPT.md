# The starting prompt

Paste the block below as the **first message** of a new conversation, from the
folder containing the repository. Nothing else needs to be attached — everything
it needs is in the repository it points at.

---

```
I am a Software Engineering student (course 203.3140, Spring 2026, Group 1). We
are building HSTS - High School Test System - as Assignment 3. The repository is
at C:\GitHub\SE\HSTS and it is already cloned on this machine.

Before doing anything else, read docs/HANDOVER.md. It is written for exactly this
moment and it explains the project, the state of the work, and how I want to be
worked with. Then read the last two or three entries of CHANGELOG.md from the
bottom so you know what was done most recently.

How I want you to work - these are the same rules as the previous sessions:

- Use plain, simple language. I am a student, not an expert. Never use jargon
  without explaining it.
- Never invent a requirement. If the source documents do not say it, tell me it
  is not there and ask me, instead of designing a rule and presenting it as
  required.
- Report honestly. If something is broken, unfinished or skipped, say so plainly
  with the evidence. Never tell me something works without actually checking it.
- Keep the code, the schema, the design documents and the test results in sync,
  and keep the running change log in CHANGELOG.md saying what changed, why, and
  how.
- Make a validation loop. Do not finish until you have checked everything is
  right, including edge cases. Run the whole test suite, more than once, with a
  reset between passes.
- Do NOT add Claude as a contributor to the repository. No Co-Authored-By line on
  any commit.

Security, and this is not negotiable:

- The repository is PUBLIC. Never commit a secret.
- The Gemini API key must never be written into any file that git tracks.
- Never write my key into anything yourself - I will paste it in.
- Never create a config file for secrets inside the project folder. .gitignore is
  not enough protection for a public repository.
- The key and the MySQL password live in %USERPROFILE%\.hsts\config.properties,
  outside the project folder.
- Only the SERVER calls Gemini. The client never does.

To confirm the machine is set up, build it and run the tests:

    mvn -o clean package
    bash tests/run-all.sh <my-mysql-user> <my-mysql-password> reset
    bash tests/run-screens.sh

I will give you the MySQL user and password when you ask. The last session
finished with 1126 checks passing across 19 suites, 21/21 screens loading and
19/19 with no cut-off text - so anything less than that means this machine is set
up differently, not that the code is broken. Tell me what you see before changing
anything.

Then tell me where the project stands and what is left, and wait for me before
starting any work.
```

---

## Notes for whoever pastes it

- **Change the path** on the third line if the repository is somewhere other than
  `C:\GitHub\SE\HSTS`.
- **Do not paste the MySQL password into the first message.** The prompt is
  written so the assistant asks for it.
- The Gemini key is only needed for the study bot. Everything else — and every
  test except the bot's live calls — works without it.
- If the first test run comes back with fewer checks, the most likely causes are:
  MySQL not running, a different MySQL password, or JDK older than 26. That is a
  setup difference, not a broken repository.
