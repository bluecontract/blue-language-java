package blue.buildlogic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end checks for conventions whose output bytes cannot be proven with ProjectBuilder. */
final class ConventionPluginsFunctionalTest {

    private static final int JAVA_EIGHT_CLASS_MAJOR_VERSION = 52;
    private static final String ARTIFACT_FILE_PREFIX = "blue-language-fixture-1.2.3";

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldBuildByteIdenticalJarSourceAndJavadocReplicas() throws Exception {
        // given
        writeFixture();

        // when
        BuildResult result = run("compareArchiveReplicas");

        // then
        assertEquals(TaskOutcome.SUCCESS,
                result.task(":compareArchiveReplicas").getOutcome());
        assertReplicaEquals(ARTIFACT_FILE_PREFIX + ".jar");
        assertReplicaEquals(ARTIFACT_FILE_PREFIX + "-sources.jar");
        assertReplicaEquals(ARTIFACT_FILE_PREFIX + "-javadoc.jar");
        assertReplicaReport();
    }

    @Test
    void shouldCompileFixtureToJavaEightBytecode() throws Exception {
        // given
        writeFixture();

        // when
        BuildResult result = run("jar");

        // then
        assertEquals(TaskOutcome.SUCCESS, result.task(":jar").getOutcome());
        assertJavaEightBytecode();
    }

    @Test
    void shouldGenerateCompletePublicationPom() throws Exception {
        // given
        writeFixture();

        // when
        BuildResult result = run("generatePomFileForMavenJavaPublication");

        // then
        assertEquals(TaskOutcome.SUCCESS,
                result.task(":generatePomFileForMavenJavaPublication").getOutcome());
        assertPublicationPom();
    }

    private void writeFixture() throws Exception {
        write(
                "settings.gradle",
                "rootProject.name = 'blue-language-fixture'\n");
        write(
                "build.gradle",
                String.join("\n", Arrays.asList(
                        "plugins {",
                        "    id 'blue.java8-library-conventions'",
                        "    id 'blue.reproducible-archives'",
                        "    id 'blue.jreleaser-publishing'",
                        "}",
                        "group = 'blue.language'",
                        "version = '1.2.3'",
                        "description = 'Functional publication fixture'",
                        "")));
        write(
                "src/main/java/example/Fixture.java",
                String.join("\n", Arrays.asList(
                        "package example;",
                        "",
                        "/** A deterministic archive fixture. */",
                        "public final class Fixture {",
                        "    private Fixture() {}",
                        "}",
                        "")));
    }

    private BuildResult run(String taskName) {
        return GradleRunner.create()
                .withProjectDir(temporaryDirectory.toFile())
                .withPluginClasspath()
                .withArguments(taskName, "--offline", "--stacktrace")
                .build();
    }

    private void assertReplicaEquals(String artifactName) throws Exception {
        Path reference = temporaryDirectory.resolve("build/libs").resolve(artifactName);
        Path replica = temporaryDirectory
                .resolve("build/reproducibility/archive-replicas")
                .resolve(artifactName);
        assertTrue(Files.isRegularFile(reference), reference.toString());
        assertTrue(Files.isRegularFile(replica), replica.toString());
        assertEquals(-1L, Files.mismatch(reference, replica), artifactName);
    }

    private void assertJavaEightBytecode() throws Exception {
        Path jar = temporaryDirectory.resolve("build/libs")
                .resolve(ARTIFACT_FILE_PREFIX + ".jar");
        try (ZipFile archive = new ZipFile(jar.toFile())) {
            ZipEntry entry = archive.getEntry("example/Fixture.class");
            assertNotNull(entry);
            byte[] classFile = archive.getInputStream(entry).readAllBytes();
            int majorVersion = ((classFile[6] & 0xff) << 8) | (classFile[7] & 0xff);
            assertEquals(JAVA_EIGHT_CLASS_MAJOR_VERSION, majorVersion);
        }
    }

    private void assertReplicaReport() throws Exception {
        String report = Files.readString(
                temporaryDirectory.resolve(
                        "build/reports/reproducibility/archive-replicas.json"),
                StandardCharsets.UTF_8);
        assertTrue(report.contains("\"archiveCount\":3"));
        assertTrue(report.contains("\"identical\":true"));
    }

    private void assertPublicationPom() throws Exception {
        String pom = Files.readString(
                temporaryDirectory.resolve("build/publications/mavenJava/pom-default.xml"),
                StandardCharsets.UTF_8);
        assertTrue(pom.contains("<artifactId>blue-language-fixture</artifactId>"));
        assertTrue(pom.contains("<name>Blue Language Fixture Java Library</name>"));
        assertTrue(pom.contains("<description>Functional publication fixture</description>"));
        assertTrue(pom.contains("<name>MIT license</name>"));
        assertTrue(pom.contains("<email>devsupport@timeline.blue</email>"));
        assertTrue(pom.contains("<url>https://github.com/bluecontract/blue-language-java.git</url>"));
    }

    private Path write(String relativePath, String content) throws Exception {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
