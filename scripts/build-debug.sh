#!/usr/bin/env bash
# Builds the debug APK using this workspace's project-local Android toolchain.
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
exec bash "$script_dir/gradle.sh" assembleDebug "$@"
