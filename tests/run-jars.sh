#!/bin/bash
# ---------------------------------------------------------------------
#  The same suites, against the PACKAGED JARS and nothing else.
#
#  Usage:  bash tests/run-jars.sh <mysql-user> <mysql-password>
#
#  Run `mvn -o package` first.
#
#  Worth doing separately from run-all.sh: the jars are what is submitted
#  and what runs on the second laptop, and a class that is on the compile
#  classpath but missing from the shaded jar passes every test in
#  run-all.sh and fails on the marker's machine.
# ---------------------------------------------------------------------
source "$(dirname "$0")/env.sh"
need_db_credentials "$@" || exit 2
cd "$(dirname "$0")"

DB_USER="$1"
DB_PASS="$2"

SUITES="M2Test M3Test M4Test M5Test M6Test M7Test M8Test M9Test M10Test M11Test M13Test M14Test M15Test NewUsersTest ClosingTimeTest BadgeTest StreamRaceTest StaffViewTest LiveUpdateTest"

for t in $SUITES ResetNow; do rm -f $t.class $t\$*.class; done
for t in $SUITES ResetNow; do
  javac -cp "$CP_JARS" $t.java 2>&1 | grep -v "^Note:" | head -5
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
  java -cp "$CP_JARS$SEP." ResetNow "$DB_USER" "$DB_PASS" | tail -1
fi

total=0
failed=0
for t in $SUITES; do
  out=$(java -cp "$CP_JARS$SEP." $t "$DB_USER" "$DB_PASS" 2>&1)
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
