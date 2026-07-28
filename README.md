# HSTS — High School Test System

Software Engineering 203.3140 · Spring 2026 · **Group 1**

A client–server exam management system for a high school: question bank, exam
building, exam sitting, automatic marking, statistics and reports, and a
per-course AI study bot. Java over TCP/IP with a MySQL database, delivered as two
runnable jars on two separate machines.

> **Status: Milestone 1 (walking skeleton) complete.** No HSTS features are
> implemented yet. Milestone 1 exists only to prove the technology stack works
> end to end. See [CHANGELOG.md](CHANGELOG.md).

## Documents

| | |
|---|---|
| [docs/00_understanding.md](docs/00_understanding.md) | What the system must do, all 15 use cases, and the contradictions found across the source documents |
| [docs/01_implementation_plan.md](docs/01_implementation_plan.md) | Architecture, database schema, protocol, milestones, risks |
| [docs/test_accounts.md](docs/test_accounts.md) | Login convention for the seeded test users |
| [CHANGELOG.md](CHANGELOG.md) | What changed, why, and how |

## Layout

```
hsts-ocsf      the reused OCSF communication framework (external reuse, unmodified)
hsts-common    Data tier <<Entity>> + message protocol — packaged into BOTH jars
hsts-server    Application tier <<Control>>            → G1_Server.jar
hsts-client    Presentation tier <<Boundary>>          → G1_Client.jar
```

## Requirements

- **JDK 26** (built and tested on 26.0.1)
- **Maven 3.9+**
- **MySQL 8.0** — on the server machine only

## Build

```bash
mvn clean package
```

Produces `hsts-server/target/G1_Server.jar` and `hsts-client/target/G1_Client.jar`.

> Both jars contain the same shared classes and are sent over an object stream.
> **Always rebuild and copy both together** — a mismatched pair fails on the first
> message with `InvalidClassException`.

## Run

**On the server machine:**

```bash
java -jar G1_Server.jar
```

A startup window asks for the listening port and the MySQL details. The database
is created automatically on first run. Settings are saved to
`%USERPROFILE%\.hsts\config.properties` — outside this repository, so no secret
is ever inside the project folder.

**On the client machine:**

```bash
java -jar G1_Client.jar
```

A startup window asks for the server's address and port.

**Windows Firewall** — once, as Administrator, on the *server* machine only:

```bash
netsh advfirewall firewall add rule name="HSTS Server" dir=in action=allow protocol=TCP localport=5555
```

MySQL deliberately gets no firewall rule. It stays on localhost, and only the
server process talks to it — the client has no database driver at all.

## Configuration and secrets

This repository is public. Nothing secret is in it.

The MySQL password and the Gemini API key live in
`%USERPROFILE%\.hsts\config.properties`, outside the project folder, so that no
git mistake can publish them:

```properties
gemini.api.key=your-key-here
mysql.password=your-password-here
```

Only the server machine needs this file. The client never touches the database
and never calls the bot API.

## A warning about the OCSF sources

`AbstractClient.java`, `AbstractServer.java` and `ConnectionToClient.java` come
from the original 2001 OCSF release, which used **carriage-return-only** line
endings. They have been converted to normal line endings, and `.gitattributes`
marks them so git will not touch them again.

This is not a theoretical concern: the same conversion has already gone wrong
once in the original source folder, fusing two lines of `EchoServer.java` into
one. **Do not remove those entries from `.gitattributes`.**

## Academic integrity

All work in this repository is original to Group 1. The only reused component is
the OCSF framework, which the course provides and which is isolated in its own
module, unmodified.
