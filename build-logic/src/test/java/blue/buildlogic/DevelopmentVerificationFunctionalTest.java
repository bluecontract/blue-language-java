package blue.buildlogic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proves that narrow development execution remains incremental, isolated, and fail closed. */
final class DevelopmentVerificationFunctionalTest {

    private static final String SELECTED_TEST = "example.FixtureTest.chosen";
    private static final String DEVELOPMENT_OUTPUT =
            "build/development-verification/focusedTest/";
    private static final String RELEASE_SENTINEL = "frozen release evidence\n";

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldSelectOneMethodAndInvalidateRelevantInputsWithoutReusingCachedResults()
            throws Exception {
        writeFixture();
        write("build/test-results/test/TEST-release-sentinel.xml", RELEASE_SENTINEL);
        write("build/reports/tests/test/index.html", RELEASE_SENTINEL);
        write("build/reports/release-evidence/clean-build.json", RELEASE_SENTINEL);

        BuildResult initial = runSelected("initial-output-cold");

        assertOutcome(initial, ":focusedTest", TaskOutcome.SUCCESS);
        assertSelectedInventoryAndIsolatedOutputs();

        BuildResult unchanged = runSelected("unchanged");

        assertOutcome(unchanged, ":compileJava", TaskOutcome.UP_TO_DATE);
        assertOutcome(unchanged, ":compileTestJava", TaskOutcome.UP_TO_DATE);
        assertOutcome(unchanged, ":focusedTest", TaskOutcome.UP_TO_DATE);

        // Add test bytecode: assertEquals(6, 3 + Fixture.value() + 1).
        writeFixtureTest(true);
        BuildResult changedTest = runSelected("changed-test");

        assertOutcome(changedTest, ":compileJava", TaskOutcome.UP_TO_DATE);
        assertOutcome(changedTest, ":compileTestJava", TaskOutcome.SUCCESS);
        assertOutcome(changedTest, ":focusedTest", TaskOutcome.SUCCESS);

        // Change only production bytecode: return 2 -> return 1 + Integer.parseInt("1").
        writeProduction("1 + Integer.parseInt(\"1\")");
        BuildResult changedProduction = runSelected("changed-production");

        assertOutcome(changedProduction, ":compileJava", TaskOutcome.SUCCESS);
        assertOutcome(changedProduction, ":focusedTest", TaskOutcome.SUCCESS);

        // Change only resource bytes: "fixture resource original\n" -> "fixture resource changed\n".
        write("src/main/resources/fixture.txt", "fixture resource changed\n");
        BuildResult changedResource = runSelected("changed-resource");

        assertOutcome(changedResource, ":compileJava", TaskOutcome.UP_TO_DATE);
        assertOutcome(changedResource, ":processResources", TaskOutcome.SUCCESS);
        assertOutcome(changedResource, ":focusedTest", TaskOutcome.SUCCESS);

        deleteDevelopmentOutputs();
        BuildResult missingOutputs = runSelected("deleted-output");

        // A result restored from cache would look newly executed in the inventory. This task
        // deliberately permits ordinary up-to-date reuse only, never cached execution evidence.
        assertOutcome(missingOutputs, ":focusedTest", TaskOutcome.SUCCESS);
        assertSelectedInventoryAndIsolatedOutputs();
    }

    @Test
    void shouldRejectMissingEmptyAndUnknownSelectors() throws Exception {
        writeFixture();
        runSelected("before-rejected-selector");

        BuildResult missing = runAndFail("focusedTest");
        assertOutcome(missing, ":focusedTest", TaskOutcome.FAILED);
        assertTrue(missing.getOutput().contains("--tests"), missing.getOutput());
        assertFalse(Files.exists(temporaryDirectory.resolve(DEVELOPMENT_OUTPUT + "xml")));
        assertFalse(Files.exists(temporaryDirectory.resolve(DEVELOPMENT_OUTPUT + "html")));
        assertTrue(read(DEVELOPMENT_OUTPUT + "inventory.txt").contains("REJECTED"));

        BuildResult empty = runAndFail("focusedTest", "--tests", "");
        assertTrue(empty.getOutput().contains("FAILED"), empty.getOutput());

        BuildResult unknown = runAndFail(
                "focusedTest", "--tests", "example.FixtureTest.doesNotExist");
        assertOutcome(unknown, ":focusedTest", TaskOutcome.FAILED);
        assertTrue(unknown.getOutput().contains("doesNotExist"), unknown.getOutput());
    }

    @Test
    void shouldPropagateSelectedAssertionFailure() throws Exception {
        writeFixture();

        BuildResult result = runAndFail(
                "focusedTest", "--tests", "example.FixtureTest.unselected");

        assertOutcome(result, ":focusedTest", TaskOutcome.FAILED);
        assertTrue(result.getOutput().contains("unrelated assertion failed"),
                result.getOutput());
        assertTrue(read(DEVELOPMENT_OUTPUT + "xml/TEST-example.FixtureTest.xml")
                .contains("failures=\"1\""));
    }

    @Test
    void shouldRejectASelectionWhoseOnlyTestIsSkipped() throws Exception {
        writeFixture();

        BuildResult result = runAndFail(
                "focusedTest", "--tests", "example.FixtureTest.disabled");

        assertOutcome(result, ":focusedTest", TaskOutcome.FAILED);
        assertTrue(read(DEVELOPMENT_OUTPUT + "inventory.txt").contains("disabled"));
    }

    @Test
    void shouldRejectTaskWhoseCandidateClassFilesAreEmpty() throws Exception {
        writeFixture();
        Files.delete(temporaryDirectory.resolve("src/test/java/example/FixtureTest.java"));

        BuildResult result = runAndFail("focusedTest", "--tests", SELECTED_TEST);

        assertOutcome(result, ":compileTestJava", TaskOutcome.NO_SOURCE);
        assertOutcome(result, ":focusedTest", TaskOutcome.FAILED);
    }

    @Test
    void shouldRejectCompiledClassesWithoutAnyDiscoveredTests() throws Exception {
        writeFixture();
        write("src/test/java/example/FixtureTest.java",
                "package example;\nclass FixtureTest {}\n");

        BuildResult result = runAndFail(
                "focusedTest", "--tests", "example.FixtureTest");

        assertOutcome(result, ":compileTestJava", TaskOutcome.SUCCESS);
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve(
                "build/classes/java/test/example/FixtureTest.class")));
        assertOutcome(result, ":focusedTest", TaskOutcome.FAILED);
    }

    private void writeFixture() throws Exception {
        write("settings.gradle", "rootProject.name = 'development-verification-fixture'\n"
                + "buildCache { local { directory = file('.gradle/fixture-build-cache') } }\n");
        write("gradle.properties", "org.gradle.jvmargs=-Xmx384m -XX:MaxMetaspaceSize=256m\n"
                + "org.gradle.workers.max=1\n"
                + "org.gradle.parallel=false\n");
        write("build.gradle", "plugins { id 'blue.java8-library-conventions' }\n"
                + "configurations.testImplementation.dependencies.clear()\n"
                + "configurations.testRuntimeOnly.dependencies.clear()\n"
                + "dependencies { testImplementation files(" + fixtureJunitClasspath() + ") }\n"
                + "tasks.register('focusedTest', blue.buildlogic.tasks.DevelopmentTest) {\n"
                + "    testClassesDirs = sourceSets.test.output.classesDirs\n"
                + "    classpath = sourceSets.test.runtimeClasspath\n"
                + "    javaLauncher.set(javaToolchains.launcherFor {\n"
                + "        languageVersion = JavaLanguageVersion.of(17)\n"
                + "    })\n"
                + "    selectionRequired.set(true)\n"
                + "}\n");
        writeProduction("2");
        writeFixtureTest(false);
        write("src/main/resources/fixture.txt", "fixture resource original\n");
    }

    private String fixtureJunitClasspath() throws Exception {
        // TestKit has its own Gradle user home. Reuse this test process's resolved JUnit jars
        // rather than requiring that separate home to contain Maven dependency metadata.
        List<String> paths = new ArrayList<>();
        for (String type : Arrays.asList(
                "org.junit.jupiter.api.Test",
                "org.junit.jupiter.engine.JupiterTestEngine",
                "org.junit.platform.engine.TestEngine",
                "org.junit.platform.launcher.Launcher",
                "org.junit.platform.commons.JUnitException",
                "org.opentest4j.AssertionFailedError",
                "org.apiguardian.api.API")) {
            Path jar = Path.of(Class.forName(type).getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            paths.add("'" + jar.toString().replace("\\", "\\\\").replace("'", "\\'") + "'");
        }
        return String.join(", ", paths);
    }

    private void writeProduction(String valueExpression) throws Exception {
        write("src/main/java/example/Fixture.java", String.join("\n",
                "package example;",
                "import java.io.BufferedReader;",
                "import java.io.InputStreamReader;",
                "import java.nio.charset.StandardCharsets;",
                "public final class Fixture {",
                "    public static int value() { return " + valueExpression + "; }",
                "    public static String resource() throws Exception {",
                "        try (BufferedReader reader = new BufferedReader(new InputStreamReader(",
                "                Fixture.class.getResourceAsStream(\"/fixture.txt\"),",
                "                StandardCharsets.UTF_8))) {",
                "            return reader.readLine();",
                "        }",
                "    }",
                "}",
                ""));
    }

    private void writeFixtureTest(boolean changedBytecode) throws Exception {
        write("src/test/java/example/FixtureTest.java", String.join("\n",
                "package example;",
                "import org.junit.jupiter.api.Disabled;",
                "import org.junit.jupiter.api.Test;",
                "import static org.junit.jupiter.api.Assertions.*;",
                "class FixtureTest {",
                "    @Test void chosen() throws Exception {",
                "        assertEquals(2, Fixture.value());",
                "        assertTrue(Fixture.resource().startsWith(\"fixture resource\"));",
                changedBytecode ? "        assertEquals(6, 3 + Fixture.value() + 1);" : "",
                "    }",
                "    @Test void unselected() { fail(\"unrelated assertion failed\"); }",
                "    @Test @Disabled(\"intentional zero-execution fixture\")",
                "    void disabled() { fail(\"must remain skipped\"); }",
                "}",
                ""));
    }

    private void assertSelectedInventoryAndIsolatedOutputs() throws Exception {
        String inventory = read(DEVELOPMENT_OUTPUT + "inventory.txt");
        assertTrue(inventory.contains("example.FixtureTest"), inventory);
        assertTrue(inventory.contains("commandLineSelected=[" + SELECTED_TEST + "]"),
                inventory);
        assertTrue(inventory.contains("executed=example.FixtureTest#chosen() SUCCESS"),
                inventory);
        assertTrue(inventory.contains(
                "totals: discovered=1 executed=1 passed=1 failed=0 skipped=0"), inventory);
        assertFalse(inventory.contains("unselected"), inventory);
        assertFalse(inventory.contains("disabled"), inventory);
        String xml = read(DEVELOPMENT_OUTPUT + "xml/TEST-example.FixtureTest.xml");
        assertTrue(xml.contains("tests=\"1\""), xml);
        assertTrue(xml.contains("failures=\"0\""), xml);
        assertTrue(xml.contains("name=\"chosen()\""), xml);
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve(
                DEVELOPMENT_OUTPUT + "html/index.html")));
        assertTrue(Files.isDirectory(temporaryDirectory.resolve(DEVELOPMENT_OUTPUT + "binary")));
        assertEquals(RELEASE_SENTINEL,
                read("build/test-results/test/TEST-release-sentinel.xml"));
        assertEquals(RELEASE_SENTINEL, read("build/reports/tests/test/index.html"));
        assertEquals(RELEASE_SENTINEL, read("build/reports/release-evidence/clean-build.json"));
    }

    private void deleteDevelopmentOutputs() throws IOException {
        try (Stream<Path> paths = Files.walk(temporaryDirectory.resolve(DEVELOPMENT_OUTPUT))) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private BuildResult runSelected(String scenario) {
        GradleRunner invocation = runner("focusedTest", "--tests", SELECTED_TEST);
        long started = System.nanoTime();
        BuildResult result = invocation.build();
        long wallMillis = (System.nanoTime() - started) / 1_000_000L;
        List<String> outcomes = new ArrayList<>();
        for (String path : Arrays.asList(
                ":focusedTest", ":compileJava", ":compileTestJava", ":processResources")) {
            outcomes.add(path + "=" + (result.task(path) == null
                    ? "NOT_SCHEDULED" : result.task(path).getOutcome()));
        }
        System.out.println("DEVELOPMENT MEASUREMENT scenario=" + scenario
                + " wallMillis=" + wallMillis
                + " selector=" + SELECTED_TEST
                + " " + String.join(" ", outcomes));
        return result;
    }

    private BuildResult runAndFail(String... arguments) {
        return runner(arguments).buildAndFail();
    }

    private GradleRunner runner(String... requestedArguments) {
        List<String> arguments = new ArrayList<>(Arrays.asList(requestedArguments));
        arguments.addAll(Arrays.asList(
                "--offline", "--stacktrace", "--max-workers=1", "--no-parallel", "--build-cache"));
        return GradleRunner.create()
                .withProjectDir(temporaryDirectory.toFile())
                .withPluginClasspath()
                .withArguments(arguments);
    }

    private void assertOutcome(BuildResult result, String path, TaskOutcome expected) {
        assertNotNull(result.task(path), result.getOutput());
        assertEquals(expected, result.task(path).getOutcome(), result.getOutput());
    }

    private String read(String relativePath) throws IOException {
        return Files.readString(temporaryDirectory.resolve(relativePath), StandardCharsets.UTF_8);
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
