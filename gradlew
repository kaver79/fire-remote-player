#!/bin/sh

set -eu

APP_HOME=$(cd "${0%/*}" && pwd -P)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "${JAVA_HOME:-}" ] ; then
  JAVACMD="$JAVA_HOME/bin/java"
  if [ ! -x "$JAVACMD" ] ; then
    echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME" >&2
    exit 1
  fi
else
  JAVACMD=java
fi

if [ ! -f "$CLASSPATH" ] ; then
  echo "ERROR: Missing $CLASSPATH" >&2
  echo "Run 'gradle wrapper' once (from Android Studio terminal or system Gradle) to generate gradle-wrapper.jar." >&2
  exit 1
fi

exec "$JAVACMD" -Dorg.gradle.appname=gradlew -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
