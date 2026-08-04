#!/bin/bash
# ---------------------------------------------------------------------
#  Every server-side suite, against the classes Maven has just built.
#
#  Usage:  bash tests/run-all.sh <mysql-user> <mysql-password>
#
#  Run `mvn -o compile` first. This deliberately uses target/classes rather
#  than the jars, so a change can be tested without repackaging.
#
#  Each suite starts its own server on a free port and talks to it over a
#  real socket. They are not unit tests: they are the whole stack.
# ---------------------------------------------------------------------
source "$(dirname "$0")/env.sh"
need_db_credentials "$@" || exit 2
cd "$(dirname "$0")"

DB_USER="$1"
DB_PASS="$2"

SUITES="M2Test M3Test M4Test M5Test M6Test M7Test M8Test M9Test M10Test M11Test M13Test M14Test M15Test NewUsersTest ClosingTimeTest BadgeTest StreamRaceTest StaffViewTest LiveUpdateTest"

for t in $SUITES ResetNow; do rm -f $t.class $t\$*.class; done
for t in $SUITES ResetNow; do
  javac -cp "$CP_CLASSES" $t.java 2>&1 | grep -v "^Note:" | head -5
  if [ ! -f "$t.class" ]; then echo "!!!! $t DID NOT COMPILE"; exit 1; fi
done
echo "all compiled"

# Optional third argument: "reset". Wipes the demo data and re-seeds it
# before the pass.
#
# Worth knowing about rather than discovering: an exam id is two digits of
# exam number plus the course and the subject, so a course holds ninety-nine
# exams and no more. A full pass writes a few dozen into course 01, and the
# THIRD pass in a row without a reset runs the course out of ids. Everything
# that builds an exam then fails at once, which looks like a broken system
# and is a full disk.
#
# Not automatic, because a reset also throws away whatever was set up by
# hand for a demo, and losing that silently would be worse than a failing
# suite that explains itself.
if [ "$3" = "reset" ]; then
  echo "resetting the demo data first..."
  java -cp "$CP_CLASSES$SEP." ResetNow "$DB_USER" "$DB_PASS" | tail -1
fi

total=0
failed=0
for t in $SUITES; do
  out=$(java -cp "$CP_CLASSES$SEP." $t "$DB_USER" "$DB_PASS" 2>&1)
  line=$(echo "$out" | grep "==== passed")
  echo "$t : $line"
  if echo "$line" | grep -q "failed 0"; then
    n=$(echo "$line" | sed -E 's/.*passed ([0-9]+).*/\1/')
    total=$((total+n))
  else
    failed=$((failed+1))
    echo "---- FAILURES in $t ----"
    echo "$out" | grep -B2 -A2 "\[FAIL\]"
  fi
done
echo "TOTAL PASSED: $total"
if [ "$failed" -gt 0 ]; then
  echo "SUITES WITH FAILURES: $failed"
  exit 1
fi
