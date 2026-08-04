#!/bin/bash
# ---------------------------------------------------------------------
#  Where everything is, worked out from where this script is.
#
#  NOT a hard-coded path. This repository is cloned to a different folder
#  on every machine it is worked on, and a script that only ran on the
#  laptop it was written on would be a script nobody else could run.
#
#  Sourced by the three runners:  source "$(dirname "$0")/env.sh"
#  Sets HSTS_ROOT, CP_CLASSES and CP_JARS. Nothing else, and no secrets.
# ---------------------------------------------------------------------

_here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Git Bash reports /c/GitHub/... which java does not understand - it wants
# C:/GitHub/... . cygpath is what translates between the two, and it is
# part of Git for Windows. Elsewhere the path is already right.
if command -v cygpath >/dev/null 2>&1; then
  HSTS_ROOT="$(cygpath -m "$_here")"
else
  HSTS_ROOT="$_here"
fi

# Windows separates classpath entries with ; and everything else with :.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=";" ;;
  *)                    SEP=":" ;;
esac

# Fresh classes FIRST, then the fat jars for the MySQL driver and JavaFX.
# Classpath order decides, so a class Maven has just compiled shadows the
# stale copy packaged inside the jar - which is what makes it possible to
# test a change without repackaging.
#
# The resources folders are here so the FXML and the stylesheet are the ones
# in src, not the ones packaged last time.
CP_CLASSES="$HSTS_ROOT/hsts-common/target/classes$SEP$HSTS_ROOT/hsts-server/target/classes$SEP$HSTS_ROOT/hsts-client/target/classes$SEP$HSTS_ROOT/hsts-ocsf/target/classes$SEP$HSTS_ROOT/hsts-common/src/main/resources$SEP$HSTS_ROOT/hsts-client/src/main/resources$SEP$HSTS_ROOT/hsts-server/target/G1_Server.jar$SEP$HSTS_ROOT/hsts-client/target/G1_Client.jar"

# The packaged jars alone - what a marker or a second machine actually runs.
CP_JARS="$HSTS_ROOT/hsts-server/target/G1_Server.jar$SEP$HSTS_ROOT/hsts-client/target/G1_Client.jar"

# MySQL credentials are never written down here. This repository is public.
# Each runner that needs them takes them from the command line.
need_db_credentials() {
  if [ -z "$2" ]; then
    echo "Usage: bash tests/$(basename "$0") <mysql-user> <mysql-password>"
    echo "  e.g. bash tests/$(basename "$0") root mypassword"
    return 1
  fi
  return 0
}
