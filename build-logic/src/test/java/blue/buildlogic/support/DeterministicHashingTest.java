package blue.buildlogic.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
}
