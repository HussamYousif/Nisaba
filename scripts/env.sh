# source scripts/env.sh — project toolchain (everything lives in .tools/)
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
export ANDROID_HOME="$ROOT/.tools/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$ROOT/.tools/gradle-home"
export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home)"
export PATH="$ANDROID_HOME/platform-tools:$ROOT/.tools/gradle-8.13/bin:$PATH"
