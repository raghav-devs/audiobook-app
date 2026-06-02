#!/bin/sh
#
# Gradle start up script for UN*X
#

# Attempt to set APP_HOME
PRG="$0"
while [ -h "$PRG" ] ; do
    ls=$(ls -ld "$PRG")
    link=$(expr "$ls" : '.*-> \(.*\)$')
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=$(dirname "$PRG")"/$link"
    fi
done
SAVED="$(pwd)"
cd "$(dirname "$PRG")/" >/dev/null
APP_HOME="$(pwd -P)"
cd "$SAVED" >/dev/null

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")

DEFAULT_JVM_OPTS=

MAX_FD="maximum"

warn () {
    echo "$*"
}

die () {
    echo
    echo "$*"
    echo
    exit 1
}

JAVA_HOME_CANDIDATES="$JAVA_HOME $JDK_HOME /usr/lib/jvm/java-17-microsoft /usr/lib/jvm/java-17-openjdk-amd64 /usr/local/sdkman/candidates/java/current"
for candidate in $JAVA_HOME_CANDIDATES; do
    if [ -x "$candidate/bin/java" ]; then
        JAVA_HOME="$candidate"
        break
    fi
done

JAVACMD="$JAVA_HOME/bin/java"
[ -x "$JAVACMD" ] || die "ERROR: JAVA_HOME is not set correctly, cannot find java at $JAVACMD"

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

JAVA_OPTS=""

exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain "$@"
