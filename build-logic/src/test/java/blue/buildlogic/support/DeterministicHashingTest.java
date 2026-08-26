package blue.buildlogic.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DeterministicHashingTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldProduceTheSameIdentityForEveryInputEnumerationOrder() throws Exception {
        // given
        Path first = Files.writeString(
                temporaryDirectory.resolve("a.txt"), "alpha", StandardCharsets.UTF_8);
        Path second = Files.createDirectories(temporaryDirectory.resolve("nested"))
                .resolve("b.txt");
        Files.writeString(second, "beta", StandardCharsets.UTF_8);

        // when
        SourceSnapshot forward = DeterministicHashing.snapshot(
                temporaryDirectory, Arrays.asList(first, second));
        SourceSnapshot reverse = DeterministicHashing.snapshot(
                temporaryDirectory, Arrays.asList(second, first));

        // then
        assertEquals(forward.getIdentity(), reverse.getIdentity());
        assertEquals("a.txt", reverse.getEntries().get(0).getPath());
        assertEquals("nested/b.txt", reverse.getEntries().get(1).getPath());
    }

    @Test
    void shouldIncludeNormalizedPathsInTheAggregateIdentity() throws Exception {
        // given
        Path firstRoot = Files.createDirectories(temporaryDirectory.resolve("first"));
        Path secondRoot = Files.createDirectories(temporaryDirectory.resolve("second"));
        Path first = Files.writeString(firstRoot.resolve("a.txt"), "same", StandardCharsets.UTF_8);
        Path second = Files.writeString(secondRoot.resolve("b.txt"), "same", StandardCharsets.UTF_8);

        // when
        SourceSnapshot firstSnapshot = DeterministicHashing.snapshot(firstRoot, Arrays.asList(first));
        SourceSnapshot secondSnapshot = DeterministicHashing.snapshot(secondRoot, Arrays.asList(second));

        // then
        org.junit.jupiter.api.Assertions.assertNotEquals(
                firstSnapshot.getIdentity(), secondSnapshot.getIdentity());
    }

    @Test
    void shouldRejectAnInputOutsideTheDeclaredSnapshotRoot() throws Exception {
        // given
        Path root = Files.createDirectories(temporaryDirectory.resolve("root"));
        Path external = Files.writeString(
                temporaryDirectory.resolve("external.txt"), "value", StandardCharsets.UTF_8);

        // when / then
        assertThrows(
                GradleException.class,
                () -> DeterministicHashing.snapshot(root, Arrays.asList(external)));
    }

    @Test
    void shouldUseStableLogicalPrefixesAcrossDifferentPhysicalRoots() throws Exception {
        // given
        Path firstProject = Files.createDirectories(temporaryDirectory.resolve("first-project"));
        Path firstStage = Files.createDirectories(temporaryDirectory.resolve("first-stage"));
        Path secondProject = Files.createDirectories(temporaryDirectory.resolve("second-project"));
        Path secondStage = Files.createDirectories(temporaryDirectory.resolve("second-stage"));
        Path firstSource = write(firstProject, "build/source.zip", "source");
        Path firstArtifact = write(firstStage, "blue/language/module/1/module-1.jar", "artifact");
        Path secondSource = write(secondProject, "build/source.zip", "source");
        Path secondArtifact = write(secondStage, "blue/language/module/1/module-1.jar", "artifact");

        // when
        SourceSnapshot first = DeterministicHashing.snapshot(
                roots(firstProject, firstStage), Arrays.asList(firstSource, firstArtifact));
        SourceSnapshot second = DeterministicHashing.snapshot(
                roots(secondProject, secondStage), Arrays.asList(secondArtifact, secondSource));

        // then
        assertEquals(first.getIdentity(), second.getIdentity());
        assertEquals("build/source.zip", first.getEntries().get(0).getPath());
        assertEquals(
                "build/staged-dependency-repository/blue/language/module/1/module-1.jar",
                first.getEntries().get(1).getPath());
    }

    @Test
    void shouldPreferTheNestedDeclaredRootForLogicalNaming() throws Exception {
        // given
        Path project = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path stage = Files.createDirectories(project.resolve("build/stage"));
        Path artifact = write(stage, "blue/language/module/1/module-1.jar", "artifact");

        // when
        SourceSnapshot snapshot = DeterministicHashing.snapshot(
                roots(project, stage), Arrays.asList(artifact));

        // then
        assertEquals(
                "build/staged-dependency-repository/blue/language/module/1/module-1.jar",
                snapshot.getEntries().get(0).getPath());
    }

    @Test
    void shouldRejectAnInputOutsideEveryDeclaredRoot() throws Exception {
        // given
        Path project = Files.createDirectories(temporaryDirectory.resolve("bounded-project"));
        Path stage = Files.createDirectories(temporaryDirectory.resolve("bounded-stage"));
        Path external = write(temporaryDirectory, "outside/value.txt", "value");

        // when / then
        assertThrows(
                GradleException.class,
                () -> DeterministicHashing.snapshot(
                        roots(project, stage), Arrays.asList(external)));
    }

    private static Map<String, Path> roots(Path project, Path stage) {
        Map<String, Path> roots = new LinkedHashMap<>();
        roots.put("", project);
        roots.put("build/staged-dependency-repository", stage);
        return roots;
    }

    private static Path write(Path root, String relativePath, String content) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
