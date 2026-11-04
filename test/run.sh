#!/bin/sh
# usage: sh test/run.sh
#   Runs the checks on the development computer. The phone build does not run them.
#   JAVAC and JAVA name the JDK when it is not on PATH.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
out=$here/out

rm -rf "$out"
"${JAVAC:-javac}" --release 17 -Xlint:all -Werror -d "$out" "$here/../src/dev/tinyvault/Vault.java" "$here/Check.java"
"${JAVA:-java}" -cp "$out" dev.tinyvault.Check
