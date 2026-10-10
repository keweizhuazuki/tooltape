package dev.tooltape.consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import dev.tooltape.CapturePolicy;
import dev.tooltape.Replay;
import dev.tooltape.Tool;
import dev.tooltape.junit.ReplayFixture;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/** An independent consumer: dependencies are installed binary JARs, never sibling sources. */
class PackagedReplayFixtureTest {
  private static final CapturePolicy POLICY = CapturePolicy.allow(Set.of("city"), Set.of("temperature"));
  private static final String RESOURCE = "/dev/tooltape/consumer/retry.tape";
  @TempDir static Path temporary;
  private static URLClassLoader resourceLoader;
  private static Class<?> jarAnchor;

  @BeforeAll
  static void packageReviewedFixture() throws Exception {
    Path jar = temporary.resolve("reviewed-fixture.jar");
    // Packaging only: the tape is checked in; no recording or real tool exists in this test.
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
      for (String entry : new String[] {"dev/tooltape/consumer/FixtureAnchor.class", RESOURCE.substring(1)}) {
        out.putNextEntry(new JarEntry(entry));
        try (InputStream in = PackagedReplayFixtureTest.class.getResourceAsStream("/" + entry)) {
          assertNotNull(in, entry);
          in.transferTo(out);
        }
        out.closeEntry();
      }
    }
    // No application-classloader parent: resolution cannot silently pick target/test-classes.
    resourceLoader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    jarAnchor = Class.forName("dev.tooltape.consumer.FixtureAnchor", true, resourceLoader);
    assertEquals(jar.toUri(), jarAnchor.getProtectionDomain().getCodeSource().getLocation().toURI());
    assertEquals("jar", jarAnchor.getResource(RESOURCE).getProtocol());
  }

  @AfterAll
  static void closeFixtureLoader() throws IOException {
    if (resourceLoader != null) resourceLoader.close();
  }

  @Test
  void executesInstalledJava17ArtifactsAndSelectedJupiterVersion() throws Exception {
    assertJar(ReplayFixture.class, "tooltape-junit-jupiter-0.2.0.jar");
    assertJar(Tool.class, "tooltape-0.2.0.jar");
    String junitVersion = System.getProperty("consumer.junit.version");
    assertJar(Test.class, "junit-jupiter-api-" + junitVersion + ".jar");
    assertJar(Class.forName("org.junit.jupiter.engine.JupiterTestEngine"), "junit-jupiter-engine-" + junitVersion + ".jar");
    String platformVersion = junitVersion.startsWith("5.") ? "1." + junitVersion.substring(2) : junitVersion;
    assertJar(LauncherFactory.class, "junit-platform-launcher-" + platformVersion + ".jar");
    try (DataInputStream in = new DataInputStream(ReplayFixture.class.getResourceAsStream("ReplayFixture.class"))) {
      assertEquals(0xCAFEBABE, in.readInt());
      in.readUnsignedShort();
      assertEquals(61, in.readUnsignedShort(), "The shared adapter must be compiled for Java 17");
    }
  }

  @Test
  void replaysClasspathResourceActuallyInsideJarAndStartsFreshEachTime() throws Throwable {
    ReplayFixture fixture = resourceFixture();
    fixture.assertReplay(PackagedReplayFixtureTest::retryWeather);
    fixture.assertReplay(PackagedReplayFixtureTest::retryWeather);
  }

  @Test
  void acceptsRelativeClassResourceInsideJar() throws Throwable {
    ReplayFixture.fromResource(jarAnchor, "retry.tape", POLICY)
        .assertReplay(PackagedReplayFixtureTest::retryWeather);
  }

  @Test
  void replaysExplicitPathWithoutRepositoryWorkingDirectory() throws Throwable {
    Path tape = temporary.resolve("reviewed-path.tape");
    try (InputStream in = jarAnchor.getResourceAsStream(RESOURCE)) {
      assertNotNull(in);
      Files.copy(in, tape);
    }
    ReplayFixture.fromPath(tape, POLICY).assertReplay(PackagedReplayFixtureTest::retryWeather);
  }

  @Test
  void swallowedDivergenceStillFailsTheReplayAssertion() throws Exception {
    ReplayFixture fixture = resourceFixture();
    assertThrows(AssertionError.class, () -> fixture.assertReplay(tool -> {
      try {
        tool.call("wrong.tool", Map.of("city", "Hangzhou"));
      } catch (Replay.Divergence deliberatelySwallowed) {
        // Even application code that catches a divergence cannot make the tape pass.
      }
    }));
  }

  @Test
  void jupiterExecutesNamedDynamicChildrenAndReportsRealFailures() {
    Map<String, TestExecutionResult> children = new LinkedHashMap<>();
    Map<String, TestExecutionResult> containers = new LinkedHashMap<>();
    var request = LauncherDiscoveryRequestBuilder.request()
        .selectors(selectClass(DynamicScenarios.class))
        .configurationParameter("junit.jupiter.execution.parallel.enabled", "false")
        .build();
    LauncherFactory.create().execute(request, new TestExecutionListener() {
      @Override
      public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
        if (identifier.isTest()) children.put(identifier.getDisplayName(), result);
        else containers.put(identifier.getDisplayName(), result);
      }
    });
    assertEquals(Set.of("complete packaged replay", "missing recorded calls", "changed arguments",
        "application assertion"), children.keySet());
    assertEquals(TestExecutionResult.Status.SUCCESSFUL, children.get("complete packaged replay").getStatus());
    for (String name : new String[] {"missing recorded calls", "changed arguments", "application assertion"}) {
      TestExecutionResult result = children.get(name);
      assertEquals(TestExecutionResult.Status.FAILED, result.getStatus(), name);
      assertInstanceOf(AssertionError.class, result.getThrowable().orElseThrow(), name);
    }
    children.forEach((name, result) -> System.out.println("Verified Jupiter child: " + name + " => " + result.getStatus()));
    assertTrue(containers.values().stream().allMatch(result -> result.getStatus() == TestExecutionResult.Status.SUCCESSFUL),
        "Failures must belong to named children, not to test-factory discovery or execution");
  }

  /** Invoked only through the Platform launcher; intentional failures do not fail the outer suite. */
  public static class DynamicScenarios {
    @TestFactory
    Stream<DynamicTest> packagedCases() throws Exception {
      ReplayFixture fixture = resourceFixture();
      return Stream.of(
          fixture.test("complete packaged replay", PackagedReplayFixtureTest::retryWeather),
          fixture.test("missing recorded calls", tool -> {}),
          fixture.test("changed arguments", tool -> tool.call("weather.lookup", Map.of("city", "Suzhou"))),
          fixture.test("application assertion", tool -> {
            retryWeather(tool);
            fail("intentional application assertion");
          }));
    }
  }

  private static ReplayFixture resourceFixture() throws IOException {
    return ReplayFixture.fromResource(jarAnchor, RESOURCE, POLICY);
  }

  private static void retryWeather(Tool tool) throws Exception {
    Replay.RecordedToolException recorded = assertThrows(Replay.RecordedToolException.class,
        () -> tool.call("weather.lookup", Map.of("city", "Hangzhou")));
    assertEquals("java.io.IOException", recorded.originalType());
    assertEquals("22", tool.call("weather.lookup", Map.of("city", "Hangzhou")).get("temperature").toString());
  }

  private static void assertJar(Class<?> type, String expectedName) throws Exception {
    Path source = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    assertTrue(Files.isRegularFile(source), () -> type.getName() + " resolved to a source/classes directory: " + source);
    assertEquals(expectedName, source.getFileName().toString());
  }
}
