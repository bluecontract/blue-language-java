package blue.buildlogic.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AggregateReleaseReceiptTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldGenerateStableArtifactTestFixtureAndApiEvidenceGroups() throws Exception {
        // given
        Path artifact = write("build/libs/blue.jar", "artifact");
        Path test = write("build/test-results/test.xml", "tests");
        Path fixture = write("build/reports/conformance/fixtures.json", "fixtures");
        Path api = write("build/reports/api/current-api.txt", "api");
        Path verification = write("build/reports/architecture/modules.json", "verification");
        Map<String, String> forwardMetadata = new LinkedHashMap<>();
        forwardMetadata.put("version", "1.0.0");
        forwardMetadata.put("channel", "rc");
        Map<String, String> reverseMetadata = new LinkedHashMap<>();
        reverseMetadata.put("channel", "rc");
        reverseMetadata.put("version", "1.0.0");

        // when
        String forward = AggregateReleaseReceipt.create(
                temporaryDirectory,
                Collections.singletonList(artifact),
                Collections.singletonList(test),
                Collections.singletonList(fixture),
                Collections.singletonList(api),
                Collections.singletonList(verification),
                "commit",
                "0007",
                forwardMetadata);
        String reverse = AggregateReleaseReceipt.create(
                temporaryDirectory,
                Collections.singletonList(artifact),
                Collections.singletonList(test),
                Collections.singletonList(fixture),
                Collections.singletonList(api),
                Collections.singletonList(verification),
                "commit",
                "7",
                reverseMetadata);

        // then
        assertEquals(forward, reverse);
        assertTrue(forward.contains("\"artifacts\":{"));
        assertTrue(forward.contains("\"tests\":{"));
        assertTrue(forward.contains("\"fixtures\":{"));
        assertTrue(forward.contains("\"api\":{"));
        assertTrue(forward.contains("\"verification\":{"));
        assertTrue(forward.contains("\"sourceDateEpoch\":\"7\""));
    }

    @Test
    void shouldDetectAnyArtifactChangeByteForByte() throws Exception {
        // given
        Path artifact = write("build/libs/blue.jar", "first");
        String recorded = receipt(artifact);
        Path receiptFile = write("receipt.json", recorded);

        // when
        AggregateReleaseReceipt.Verification before = AggregateReleaseReceipt.verify(
                receiptFile, receipt(artifact));
        Files.writeString(artifact, "second", StandardCharsets.UTF_8);
        AggregateReleaseReceipt.Verification after = AggregateReleaseReceipt.verify(
                receiptFile, receipt(artifact));

        // then
        assertTrue(before.isVerified());
        assertFalse(after.isVerified());
        assertTrue(after.getReport().contains("\"verified\":false"));
    }

    @Test
    void shouldBindAnExternalStageWithoutBindingItsHostPath() throws Exception {
        // given
        Path firstProject = Files.createDirectories(temporaryDirectory.resolve("first-project"));
        Path secondProject = Files.createDirectories(temporaryDirectory.resolve("second-project"));
        Path firstStage = Files.createDirectories(
                firstProject.resolve("build/staged-dependency-repository"));
        Path secondStage = Files.createDirectories(temporaryDirectory.resolve("invocation-stage-b"));
        Path firstSource = write(firstProject, "build/distributions/source.zip", "source");
        Path secondSource = write(secondProject, "build/distributions/source.zip", "source");
        Path firstArtifact = write(
                firstStage, "blue/language/module/1/module-1.jar", "artifact");
        Path secondArtifact = write(
                secondStage, "blue/language/module/1/module-1.jar", "artifact");

        // when
        String first = receipt(firstProject, firstSource, firstArtifact);
        String second = receipt(secondProject, secondStage, secondSource, secondArtifact);

        // then
        assertEquals(first, second);
        assertTrue(first.contains(
                "\"path\":\"build/staged-dependency-repository/blue/language/module/1/module-1.jar\""));
        assertTrue(first.contains("\"path\":\"build/distributions/source.zip\""));
        assertFalse(first.contains(firstStage.toString()));
        assertFalse(first.contains(secondStage.toString()));
    }

    private String receipt(Path artifact) {
        return AggregateReleaseReceipt.create(
                temporaryDirectory,
                Collections.singletonList(artifact),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                "commit",
                "11",
                Collections.emptyMap());
    }

    private static String receipt(
            Path root, Path artifactRoot, Path sourceArchive, Path artifact) {
        return AggregateReleaseReceipt.create(
                root,
                artifactRoot,
                Arrays.asList(sourceArchive, artifact),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                "commit",
                "11",
                Collections.emptyMap());
    }

    private static String receipt(Path root, Path sourceArchive, Path artifact) {
        return AggregateReleaseReceipt.create(
                root,
                Arrays.asList(sourceArchive, artifact),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                "commit",
                "11",
                Collections.emptyMap());
    }

    private Path write(String relativePath, String content) throws Exception {
        return write(temporaryDirectory, relativePath, content);
    }

    private static Path write(Path root, String relativePath, String content) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
