package blue.buildlogic.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CleanBuildEvidenceTest {

    private static final String COMMIT = "0123456789012345678901234567890123456789";

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldVerifyTheSameCommitSourceAndEpochAcrossInvocations() throws Exception {
        // given
        Path source = Files.writeString(
                temporaryDirectory.resolve("source.txt"), "stable", StandardCharsets.UTF_8);
        String marker = CleanBuildEvidence.createCleanBuild(
                temporaryDirectory,
                Collections.singletonList(source),
                COMMIT,
                "00042",
                ":clean",
                ":build",
                Arrays.asList("clean", "build"),
                Collections.emptyList());
        Path markerFile = Files.writeString(
                temporaryDirectory.resolve("clean-build.json"), marker, StandardCharsets.UTF_8);

        // when
        CleanBuildEvidence.Verification result = CleanBuildEvidence.verify(
                markerFile,
                temporaryDirectory,
                Collections.singletonList(source),
                COMMIT,
                "42",
                ":clean",
                ":build");

        // then
        assertTrue(result.isVerified());
        assertEquals("verified", result.getReason());
        assertTrue(result.getReport().contains("\"verified\":true"));
        assertEquals(CleanBuildEvidence.CLEAN_BUILD_SCHEMA,
                result.getMarker().getSchema());
    }

    @Test
    void shouldRejectChangedSourceEpochAndTaskExclusionsWithStableReasons() throws Exception {
        // given
        Path source = Files.writeString(
                temporaryDirectory.resolve("source.txt"), "first", StandardCharsets.UTF_8);
        Path cleanMarker = Files.writeString(
                temporaryDirectory.resolve("clean-build.json"),
                CleanBuildEvidence.createCleanBuild(
                        temporaryDirectory,
                        Collections.singletonList(source),
                        COMMIT,
                        "42",
                        ":clean",
                        ":build",
                        Arrays.asList("clean", "build"),
                        Collections.emptyList()),
                StandardCharsets.UTF_8);
        Path excludedMarker = Files.writeString(
                temporaryDirectory.resolve("excluded-build.json"),
                CleanBuildEvidence.createCleanBuild(
                        temporaryDirectory,
                        Collections.singletonList(source),
                        COMMIT,
                        "42",
                        ":clean",
                        ":build",
                        Arrays.asList("clean", "build"),
                        Collections.singletonList("test")),
                StandardCharsets.UTF_8);

        // when
        CleanBuildEvidence.Verification wrongEpoch = CleanBuildEvidence.verify(
                cleanMarker, temporaryDirectory, Collections.singletonList(source),
                COMMIT, "43", ":clean", ":build");
        CleanBuildEvidence.Verification excluded = CleanBuildEvidence.verify(
                excludedMarker, temporaryDirectory, Collections.singletonList(source),
                COMMIT, "42", ":clean", ":build");
        Files.writeString(source, "second", StandardCharsets.UTF_8);
        CleanBuildEvidence.Verification changed = CleanBuildEvidence.verify(
                cleanMarker, temporaryDirectory, Collections.singletonList(source),
                COMMIT, "42", ":clean", ":build");

        // then
        assertFalse(wrongEpoch.isVerified());
        assertEquals("source-date-epoch-changed-since-clean-build", wrongEpoch.getReason());
        assertEquals("clean-build-used-task-exclusions", excluded.getReason());
        assertEquals("source-inputs-changed-since-clean-build", changed.getReason());
    }
}
