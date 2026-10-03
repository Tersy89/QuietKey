#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
java app/src/main/java/app/quietkey/Protocol.java
java tools/CheckSyntax.java
