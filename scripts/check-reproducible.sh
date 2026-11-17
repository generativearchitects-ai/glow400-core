#!/usr/bin/env bash
# Builds the artifacts that would be published twice, each time from a clean tree, and compares the checksum of every file.
# Two builds of the same commit must give the same bytes (project.build.outputTimestamp in pom.xml, kickoff 12).
#
# Run from anywhere: scripts/check-reproducible.sh. Needs nothing but Java and the Maven Wrapper; it signs nothing and
# uploads nothing. It installs the modules into your local Maven repository first (the test jars that some modules share are
# only built by an ordinary build, and the release profile leaves them out of the deployment). Exit code 0: same bytes.
# 1: a difference, listed. The staging folder of the second build stays in target/.
set -euo pipefail

cd "$(dirname "$0")/.."

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# The same arguments as the CI job that checks the files (scripts/check_staged_artifacts.py): no tests, no signature, no upload.
build() {
  ./mvnw -B -q -Prelease -Dgpg.skip=true -DskipUpload=true -DskipTests -Dinvoker.skip=true clean deploy
  # A snapshot is staged in central-deferred, a release in central-staging.
  local staged
  for staged in target/central-deferred target/central-staging; do
    if [ -d "$staged" ]; then
      (cd "$staged" && find . -type f ! -name 'maven-metadata*' -print0 | sort -z | xargs -0 sha256sum) > "$1"
      return
    fi
  done
  echo "No staging folder in target/: the build did not stage anything." >&2
  exit 1
}

echo "Installing the modules (their test jars are needed, and are not part of what is compared)..."
./mvnw -B -q install -DskipTests -Dinvoker.skip=true

echo "First build..."
build "$work/first.sha256"
echo "Second build..."
build "$work/second.sha256"

files="$(wc -l < "$work/first.sha256" | tr -d ' ')"
if [ "$files" -eq 0 ]; then
  echo "No file was staged." >&2
  exit 1
fi

if diff -u "$work/first.sha256" "$work/second.sha256"; then
  echo "Reproducible: $files files, the same checksums in both builds."
else
  echo "NOT reproducible: the two builds differ (see above)." >&2
  exit 1
fi
