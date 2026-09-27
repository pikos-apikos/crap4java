#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

MAVEN_VERSION_TEXT="$(mvn --version | head -n 1)"
JAVA_VERSION_TEXT="$(java -version 2>&1 | head -n 1)"
JAVA_MAJOR="$(printf '%s\n' "$JAVA_VERSION_TEXT" | sed -E 's/.*version "([0-9]+).*/\1/')"
if [[ "$JAVA_MAJOR" != "25" ]]; then
  echo "JDK 25 is required; detected: $JAVA_VERSION_TEXT" >&2
  exit 2
fi

WARMUPS="${CRAP_EXPERIMENT_WARMUPS:-2}"
ITERATIONS="${CRAP_EXPERIMENT_ITERATIONS:-7}"
if ! [[ "$WARMUPS" =~ ^[0-9]+$ ]] || ! [[ "$ITERATIONS" =~ ^[1-9][0-9]*$ ]]; then
  echo "CRAP_EXPERIMENT_WARMUPS must be >= 0 and CRAP_EXPERIMENT_ITERATIONS must be >= 1" >&2
  exit 2
fi

JACOCO_VERSION="0.8.14"
mvn -B clean test
mkdir -p target/experiment/classes experiment/results
mvn -B -q org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy \
  -Dartifact="org.jacoco:org.jacoco.agent:$JACOCO_VERSION:jar:runtime" \
  -DoutputDirectory=target/experiment
javac --release 25 -g -d target/experiment/classes $(find experiment/fixtures -name '*.java' -print | sort)

JACOCO_AGENT="target/experiment/org.jacoco.agent-$JACOCO_VERSION-runtime.jar"

if [[ ! -f "$JACOCO_AGENT" ]]; then
  echo "JaCoCo runtime agent not found at $JACOCO_AGENT after Maven dependency:copy" >&2
  exit 3
fi

java -javaagent:"$JACOCO_AGENT"=destfile=target/experiment/jacoco.exec \
  -cp target/experiment/classes experiment.fixtures.FixtureMatrix

mvn -B -q org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
  -DincludeScope=test -Dmdep.outputFile=target/experiment/test-classpath.txt
TEST_CLASSPATH="$(cat target/experiment/test-classpath.txt)"
SOURCE_REVISION="$(git rev-parse HEAD)"

java -cp "target/test-classes:target/classes:$TEST_CLASSPATH" crap4java.ExperimentRunner \
  experiment/fixtures target/experiment/classes target/experiment/jacoco.exec \
  experiment/results "$SOURCE_REVISION" "$MAVEN_VERSION_TEXT" "$WARMUPS" "$ITERATIONS"

echo "Experiment results written under experiment/results/"
