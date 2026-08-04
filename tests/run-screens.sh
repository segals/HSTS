#!/bin/bash
# ---------------------------------------------------------------------
#  The four harnesses that open the screens themselves.
#
#  Usage:  bash tests/run-screens.sh
#
#  No database and no server: these load the FXML, lay it out, and measure
#  what JavaFX actually did. They need a real display - they will not run
#  over a plain SSH session.
#
#    FxmlLoadTest        every screen loads and every fx:id is wired up
#    MenuBadgeTest       the unread badges, where they belong and nowhere else
#    TruncationTest      no text cut off, at four window sizes
#    ScreenBehaviourTest the calendar, the exam history, and messages that
#                        must survive the exam clock's once-a-second tick
# ---------------------------------------------------------------------
source "$(dirname "$0")/env.sh"
CP="$CP_CLASSES"
cd "$(dirname "$0")"

bad=0
for t in FxmlLoadTest MenuBadgeTest TruncationTest ScreenBehaviourTest; do
  javac -cp "$CP" $t.java 2>&1 | grep -v "^Note:" | head -5
  if [ ! -f "$t.class" ]; then echo "!!!! $t DID NOT COMPILE"; exit 1; fi
  out=$(java -cp "$CP$SEP." $t 2>&1)
  line=$(echo "$out" | grep -E "^==== ")
  echo "$t : $line"
  if ! echo "$line" | grep -qE "failed 0|cut-off text 0"; then
    bad=$((bad+1))
    echo "$out" | grep -A3 "\[FAIL\]\|\[CUT\]" | head -20
  fi
done
if [ "$bad" -gt 0 ]; then
  echo "SCREEN HARNESSES WITH PROBLEMS: $bad"
  exit 1
fi
