#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
python3 "$root/tools/bootstrap.py"
export JAVA_HOME="$root/.tools/jdk"
export MAVEN_OPTS="${MAVEN_OPTS:-} -Djavax.net.ssl.trustStore=$root/.tools/cacerts -Djavax.net.ssl.trustStorePassword=changeit"
pom="$root/pom.xml"
if [ "${1:-}" = "-f" ] || [ "${1:-}" = "--file" ]; then pom="$2"; shift 2; fi
case "$pom" in *backend-bridge*) ;; *) python3 "$root/tools/build-bundled-ffmpeg.py" ;; esac
exec "$root/.tools/maven/bin/mvn" -s "$root/.tools/settings.xml" -Dmaven.repo.local="$root/.tools/m2" -f "$pom" "$@"
