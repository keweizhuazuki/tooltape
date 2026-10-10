# Packaged Jupiter compatibility consumer

This is a standalone verification project, not a parent or module of the core build.
It consumes only locally installed `dev.tooltape:tooltape:0.2.0` and
`dev.tooltape:tooltape-junit-jupiter:0.2.0` JARs. These coordinates describe local
build artifacts; the project does not claim they are published to Maven Central.
There are no parent-POM links, sibling source roots, or reactor dependencies.

The dedicated `jupiter.yml` workflow:

1. Builds the core once using the JDK-only build and the adapter once with Java 17
   and JUnit 5.14.4.
2. Uploads both JARs, their POMs, this consumer's sources, and SHA-256 checksums as
   an internal CI artifact. No repository deployment or release is performed.
3. Downloads that exact artifact in four fresh consumer jobs, with **no checkout**,
   then installs its JARs into an independent Maven repository in the runner's
   temporary directory.
4. Runs this project outside the checkout with Java 17 and 21 against JUnit 5.14.4
   and 6.0.3. Each job checks artifact hashes and installed JAR identity.

Tests independently assert that core, adapter, and the selected Jupiter API load
from actual JARs, and that the shared adapter bytecode targets Java 17. They check
path-based replay, repeated executions with fresh state, swallowed divergence,
and both relative and absolute classpath resources.

The checked-in `retry.tape` contains **synthetic**, reviewed weather data only:
`weather.lookup({"city":"Hangzhou"})` first records `java.io.IOException`, then returns
`{"temperature":22}`. Its capture policy permits input `city` and output
`temperature`; durations are zero. Test setup packages this existing tape and a trivial
anchor class into a JAR, then uses an isolated classloader and verifies its `jar:`
resource URL. Setup does not record a tape or invoke a real tool.

A programmatic Jupiter Platform launcher executes actual `@TestFactory` children
and verifies four exact display names and their outcomes: one successful full
replay and three failures (missing calls, changed arguments, and an application
assertion). The outer suite passes only if those intentional failures are
reported on the named children, rather than the factory container. Child outcomes
are printed in the retained Surefire evidence.

## Local use

First build the core and adapter using the [Jupiter guide](../../docs/JUNIT.md). Then copy this whole
directory outside the checkout and install the **same** core and adapter JARs
with their POMs into a new local Maven repository (using
`org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file`). From the copied
consumer directory, run both:

```sh
mvn -Dmaven.repo.local=/absolute/path/to/consumer-m2 -Djunit.version=5.14.4 verify
mvn -Dmaven.repo.local=/absolute/path/to/consumer-m2 -Djunit.version=6.0.3 clean verify
```

Repeat under Java 17 and 21 without rebuilding the core or adapter. The Java test
code is portable and makes no shell or external-tool calls. CI currently proves
the matrix on Ubuntu; it does not claim this separate consumer has run on Windows
or macOS.
