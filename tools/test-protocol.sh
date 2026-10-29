#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
./gradlew testDebugUnitTest
java tools/CheckSyntax.java
