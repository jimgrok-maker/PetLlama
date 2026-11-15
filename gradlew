#!/bin/sh

# Minimal Gradle wrapper stub. GitHub Actions installs Gradle via the
# gradle/gradle-build-action or by running the wrapper. For CI we rely on
# a pre-installed Gradle, so this script just delegates to `gradle` on PATH.

if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi

echo "gradle not found on PATH" >&2
exit 1
