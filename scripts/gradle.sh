#!/usr/bin/env bash
# Runs Gradle with this workspace's project-local Android toolchain.
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
workspace_root=$(cd "$project_root/.." && pwd)

export JAVA_HOME="$workspace_root/.toolchains/jdk-17/Contents/Home"
export ANDROID_HOME="$workspace_root/.toolchains/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$workspace_root/.toolchains/gradle-home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "Missing project-local JDK 17: $JAVA_HOME" >&2
  exit 1
fi
if [[ ! -x "$ANDROID_HOME/platform-tools/adb" ]]; then
  echo "Missing project-local Android SDK: $ANDROID_HOME" >&2
  exit 1
fi

if [[ -x "$project_root/gradlew" ]]; then
  exec "$project_root/gradlew" -p "$project_root" --no-daemon "$@"
fi

exec bash "$workspace_root/gradle-8.13/bin/gradle" -p "$project_root" --no-daemon "$@"
