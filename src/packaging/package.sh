#!/bin/sh
#
# Packages SqlJam for download with jpackage (JDK 17 or later), after mvn package, on the system of the platform
# (jpackage builds for the system it runs on):
#
#   sh src/packaging/package.sh <platform> [version]      platform: mac-aarch64, mac, win or linux
#
# The version names the packages, e.g. 1.0.0-rc1 of the tag v1.0.0-rc1; the version of pom.xml by default.
#
#   mac-aarch64, mac   SqlJam.app in SqlJam-<version>-<platform>.dmg
#   win                SqlJam-<version>-win.zip, SqlJam\SqlJam.exe without installing, and SqlJam-<version>-win.msi
#                      of releases (not of pre-releases like 1.0.0-rc1) when the WiX Toolset 3 is on the path
#   linux              SqlJam-<version>-linux.tar.gz (SqlJam/bin/SqlJam) and sqljam_<version>_amd64.deb
#   every platform     SqlJam-<version>-<platform>-jar.zip, the runnable jar with its launch scripts (Java 17+)
#
# The packages have their own Java runtime and the JVM options of sqljam.vmoptions. They are not signed with a
# certificate: macOS opens a downloaded app by System Settings > Privacy & Security > Open Anyway, Windows by
# More info > Run anyway.
#
set -e

PLATFORM=${1:?platform: mac-aarch64, mac, win or linux}
DIR=$(cd "$(dirname "$0")/../.." && pwd)
# The runnable jar is named by the version of pom.xml, the packages by the given version
POM_VERSION=$(sed -n 's:^    <version>\(.*\)</version>:\1:p' "$DIR/pom.xml" | head -1)
VERSION=${2:-$POM_VERSION}
JAR="sqljam-$POM_VERSION-$PLATFORM.jar"
# jpackage takes numbers only, e.g. 1.0.0 of 1.0.0-SNAPSHOT
APP_VERSION=$(echo "$VERSION" | sed 's/[^0-9.].*$//')
WORK="$DIR/target/package-$PLATFORM"
OUT="$DIR/bin"

if [ ! -f "$OUT/$JAR" ]; then
    echo "Runnable jar not found: $OUT/$JAR, run mvn package first" >&2
    exit 1
fi

rm -rf "$WORK"
# The portable files are prepared in a directory of their own: sqljam and SqlJam (jpackage) are the same
# directory on Windows, its file names ignore the case
mkdir -p "$WORK/input" "$WORK/portable/sqljam"
# sqljam.properties is read next to the jar
cp "$OUT/$JAR" "$OUT/sqljam.properties" "$WORK/input/"

# The runnable jar with its launch scripts, for a Java runtime of the user
cp "$OUT/$JAR" "$OUT/sqljam.properties" "$OUT/sqljam.vmoptions" "$OUT/sqljam.png" "$WORK/portable/sqljam/"
case "$PLATFORM" in
    win) cp "$OUT/sqljam.bat" "$WORK/portable/sqljam/" ;;
    *) cp "$OUT/sqljam.sh" "$WORK/portable/sqljam/" ;;
esac
rm -f "$OUT/SqlJam-$VERSION-$PLATFORM-jar.zip"
(cd "$WORK/portable" && jar --create --no-manifest --file "$OUT/SqlJam-$VERSION-$PLATFORM-jar.zip" sqljam)

# JVM options of the launcher; they are applied already, SqlJam does not start another java process
set --
while IFS= read -r line; do
    line=$(echo "$line" | tr -d '\r')
    case "$line" in
        ''|\#*) ;;
        *) set -- "$@" --java-options "$line" ;;
    esac
done < "$OUT/sqljam.vmoptions"
set -- "$@" --java-options "-Dsqljam.vmoptions.applied=true"

case "$PLATFORM" in
    mac*) set -- "$@" --icon "$DIR/src/packaging/macos/SqlJam.icns" --mac-package-identifier com.github.sqljam \
              --mac-package-name SqlJam ;;
    win) set -- "$@" --icon "$DIR/src/packaging/windows/SqlJam.ico" ;;
    linux) set -- "$@" --icon "$OUT/sqljam.png" ;;
    *) echo "Unknown platform: $PLATFORM" >&2; exit 1 ;;
esac

jpackage --type app-image \
    --dest "$WORK" \
    --input "$WORK/input" \
    --main-jar "$JAR" \
    --main-class com.github.sqljam.face.Launcher \
    --name SqlJam \
    --app-version "$APP_VERSION" \
    --vendor "Fred Feng" \
    --copyright "Copyright 2023-2026 Fred Feng, Apache License 2.0" \
    "$@"

case "$PLATFORM" in
    mac*)
        APP="$WORK/SqlJam.app"
        PLIST="$APP/Contents/Info.plist"
        # Databases of the local network are refused without the permission of the local network (macOS 15+)
        plist() {
            /usr/libexec/PlistBuddy -c "Set :$1 $2" "$PLIST" 2>/dev/null \
                || /usr/libexec/PlistBuddy -c "Add :$1 string $2" "$PLIST"
        }
        plist NSLocalNetworkUsageDescription "SqlJam connects to the databases of your local network."
        plist LSApplicationCategoryType public.app-category.developer-tools
        # Info.plist is changed, the app is signed again (ad hoc, without a Developer ID)
        codesign --force --deep --sign - "$APP"
        jpackage --type dmg --dest "$WORK" --app-image "$APP" --name SqlJam --app-version "$APP_VERSION" \
            --vendor "Fred Feng"
        rm -f "$OUT/SqlJam-$VERSION-$PLATFORM.dmg"
        mv "$WORK"/SqlJam-*.dmg "$OUT/SqlJam-$VERSION-$PLATFORM.dmg"
        rm -rf "$OUT/SqlJam.app"
        cp -R "$APP" "$OUT/SqlJam.app"
        ;;
    win)
        rm -f "$OUT/SqlJam-$VERSION-win.zip"
        (cd "$WORK" && jar --create --no-manifest --file "$OUT/SqlJam-$VERSION-win.zip" SqlJam)
        # An installer with a directory chooser, Start menu and desktop shortcuts. The upgrade code is the same
        # for every version, a newer version replaces the installed one. Versions of msi are numbers only, a
        # pre-release (1.0.0-rc1, 1.0.0-SNAPSHOT) would be the same version as its release and would not be
        # replaced by it, pre-releases have the zip only.
        case "$VERSION" in
            *-*)
                echo "Pre-release $VERSION, the msi installer is skipped" >&2
                ;;
            *)
                if command -v candle >/dev/null 2>&1 || command -v candle.exe >/dev/null 2>&1; then
                    jpackage --type msi --dest "$WORK" --app-image "$WORK/SqlJam" --name SqlJam \
                        --app-version "$APP_VERSION" --vendor "Fred Feng" \
                        --description "Import and export tool for relational and OLAP databases" \
                        --win-dir-chooser --win-menu --win-menu-group SqlJam --win-shortcut \
                        --win-upgrade-uuid 494b38a3-8f4d-457f-bd03-4b48948608e5
                    rm -f "$OUT/SqlJam-$VERSION-win.msi"
                    mv "$WORK"/SqlJam-*.msi "$OUT/SqlJam-$VERSION-win.msi"
                else
                    echo "WiX Toolset 3 not found, the msi installer is skipped" >&2
                fi
                ;;
        esac
        ;;
    linux)
        rm -f "$OUT/SqlJam-$VERSION-linux.tar.gz"
        tar -czf "$OUT/SqlJam-$VERSION-linux.tar.gz" -C "$WORK" SqlJam
        # A deb for Debian and Ubuntu, installed into /opt/sqljam with a menu entry
        if command -v dpkg-deb >/dev/null 2>&1; then
            jpackage --type deb --dest "$WORK" --app-image "$WORK/SqlJam" --name SqlJam --app-version "$APP_VERSION" \
                --vendor "Fred Feng" --linux-package-name sqljam --linux-shortcut --linux-menu-group Development
            mv "$WORK"/sqljam_*.deb "$OUT/"
        fi
        ;;
esac
echo "Packaged SqlJam $VERSION for $PLATFORM into $OUT"
