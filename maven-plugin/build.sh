#!/usr/bin/env bash
# Builds the formatter, installs it into the local Maven repository, then builds and tests the plugin.
set -euo pipefail
cd "$(dirname "$0")/.."

java build/jenesis/Make.java >/dev/null
JAR=$(find target -name 'org.opentripplanner.java.code.formatter-1-SNAPSHOT.jar' -path '*observed/test/dependencies/resolve*' | head -1)

mvn -q install:install-file -Dfile="$JAR" -DgroupId=org.opentripplanner.java.code -DartifactId=formatter \
  -Dversion=1-SNAPSHOT -Dpackaging=jar -DgeneratePom=true

mvn -f maven-plugin/pom.xml "${@:-verify}"
