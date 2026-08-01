package blue.buildlogic.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ArchiveReplicaComparisonTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldAcceptByteIdenticalReplicasAndReportTheirHashes() throws Exception {
        // given
        Path referenceDirectory = Files.createDirectories(temporaryDirectory.resolve("reference"));
        Path replicaDirectory = Files.createDirectories(temporaryDirectory.resolve("replica"));
        Path reference = Files.write(
                referenceDirectory.resolve("blue.jar"), new byte[] {1, 2, 3});
        Path replica = Files.write(
                replicaDirectory.resolve("blue.jar"), new byte[] {1, 2, 3});

        // when
        ArchiveReplicaComparison.Result result = ArchiveReplicaComparison.compare(
                Collections.singletonList(reference), Collections.singletonList(replica));

        // then
        assertTrue(result.isIdentical());
        assertTrue(result.toJson().contains("\"identical\":true"));
        assertTrue(result.toJson().contains("sha256:"));
    }

    @Test
    void shouldRejectChangedOrMissingArchiveReplicasDeterministically() throws Exception {
        // given
        Path referenceDirectory = Files.createDirectories(temporaryDirectory.resolve("reference"));
        Path replicaDirectory = Files.createDirectories(temporaryDirectory.resolve("replica"));
        Path changedReference = Files.writeString(
                referenceDirectory.resolve("changed.jar"), "first", StandardCharsets.UTF_8);
        Path missingReference = Files.writeString(
                referenceDirectory.resolve("missing.jar"), "only", StandardCharsets.UTF_8);
        Path changedReplica = Files.writeString(
                replicaDirectory.resolve("changed.jar"), "second", StandardCharsets.UTF_8);

        // when
        ArchiveReplicaComparison.Result result = ArchiveReplicaComparison.compare(
                Arrays.asList(missingReference, changedReference),
                Collections.singletonList(changedReplica));

        // then
        assertFalse(result.isIdentical());
        assertTrue(result.toJson().contains("byte-mismatch-at-"));
        assertTrue(result.toJson().contains("missing-replica"));
    }

    @Test
    void shouldRejectAmbiguousDuplicateArchiveNames() throws Exception {
        // given
        Path first = Files.createDirectories(temporaryDirectory.resolve("one")).resolve("same.jar");
        Path second = Files.createDirectories(temporaryDirectory.resolve("two")).resolve("same.jar");
        Files.write(first, new byte[] {1});
        Files.write(second, new byte[] {1});

        // when / then
        assertThrows(
                GradleException.class,
                () -> ArchiveReplicaComparison.compare(
                        Arrays.asList(first, second), Collections.emptyList()));
    }
}
