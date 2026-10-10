#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
rm -rf target/classes target/test-classes target/example-classes
mkdir -p target/classes target/test-classes target/example-classes
find src/main/java -name '*.java' | sort > target/main-sources.txt
javac --release 17 -Xlint:all -d target/classes @target/main-sources.txt
jar --create --date=2026-10-10T00:00:00Z --file target/tooltape.jar --main-class dev.tooltape.Main -C target/classes .
find src/test/java -name '*.java' ! -name '*JUnitTest.java' | sort > target/test-sources.txt
javac --release 17 -cp target/classes -d target/test-classes @target/test-sources.txt
java -ea -cp target/classes:target/test-classes dev.tooltape.CoreTest
javac --release 17 -cp target/classes -d target/example-classes examples/RetryRegression.java
printf 'Built target/tooltape.jar\n'
