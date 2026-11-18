            #!/usr/bin/env bash
# Runs the formatter tests, installs the formatter into the local Maven repository, then builds and tests the plugin.
set -euo pipefail
cd "$(dirname "$0")/.."

java build/jenesis/Make.java >/dev/null
mvn -q -f formatter/pom.xml install

mvn -f maven-plugin/pom.xml "${@:-verify}"
