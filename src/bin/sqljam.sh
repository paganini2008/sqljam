#!/bin/sh
#
# SqlJam launcher for Linux and macOS. It picks the runnable jar of the platform
# (Linux x64, macOS x64 or Apple Silicon) next to this script and runs it with Java 17 or later.
#
#   sh sqljam.sh
#   sh sqljam.sh sync --task NAME    runs a sync task without the window, e.g. from cron
#
# JVM options are read from sqljam.vmoptions next to this script (one option per line),
# JAVA_OPTS adds more and wins, e.g. JAVA_OPTS=-Xmx4g. JAVA_HOME selects the Java runtime.
#

DIR=$(cd "$(dirname "$0")" && pwd)
VERSION="@project.version@"

if [ -n "$JAVA_HOME" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA=java
fi
if ! command -v "$JAVA" >/dev/null 2>&1; then
    echo "Java 17 or later is required, install it or set JAVA_HOME" >&2
    exit 1
fi

# Java version and architecture of the runtime (an x64 runtime may run on an arm64 Mac)
SETTINGS=$("$JAVA" -XshowSettings:properties -version 2>&1)
MAJOR=$(echo "$SETTINGS" | sed -n 's/^ *java\.specification\.version = //p' | cut -d. -f1)
ARCH=$(echo "$SETTINGS" | sed -n 's/^ *os\.arch = //p')
if [ -z "$MAJOR" ] || [ "$MAJOR" -lt 17 ] 2>/dev/null; then
    echo "Java 17 or later is required, found: $MAJOR" >&2
    exit 1
fi

case "$(uname -s)" in
    Darwin) OS=mac ;;
    Linux) OS=linux ;;
    *) echo "Unsupported system: $(uname -s), use sqljam.bat on Windows" >&2; exit 1 ;;
esac
case "$OS-$ARCH" in
    mac-aarch64|mac-arm64) PLATFORM=mac-aarch64 ;;
    mac-*|linux-amd64|linux-x86_64) PLATFORM="$OS" ;;
    *) echo "Unsupported architecture: $ARCH, an x64 Java runtime is required on Linux" >&2; exit 1 ;;
esac

JAR="$DIR/sqljam-$VERSION-$PLATFORM.jar"
if [ ! -f "$JAR" ]; then
    echo "Runnable jar not found: $JAR" >&2
    exit 1
fi
VMOPTIONS=""
if [ -f "$DIR/sqljam.vmoptions" ]; then
    VMOPTIONS=$(grep -v '^[[:space:]]*#' "$DIR/sqljam.vmoptions" | grep -v '^[[:space:]]*$' | tr '\n' ' ')
fi
# The Dock of macOS shows the name and the icon of SqlJam instead of Java
if [ "$OS" = mac ]; then
    exec "$JAVA" -Xdock:name=SqlJam -Xdock:icon="$DIR/sqljam.png" $VMOPTIONS -Dsqljam.vmoptions.applied=true \
        $JAVA_OPTS -jar "$JAR" "$@"
fi
exec "$JAVA" $VMOPTIONS -Dsqljam.vmoptions.applied=true $JAVA_OPTS -jar "$JAR" "$@"
