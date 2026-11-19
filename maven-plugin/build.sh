#!/usr/bin/env bash
# Runs the formatter tests with Jenesis, then builds the formatter and the plugin with Maven and runs the plugin's
# integration tests.
set -euo pipefail
cd "$(dirname "$0")/.."

java build/jenesis/Make.java >/dev/null

mvn "${@:-verify}"
