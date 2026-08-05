package blue.buildlogic.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class VerifyReleaseEvidenceReportTaskTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldWriteDeterministicViolationsBeforeRejectingIncompleteEvidence() throws Exception {
        // given
        Path evidence = write("evidence.json", "{}\n");
        Path markdown = write("evidence.md", "# Evidence\n");
        Path artifact = write("artifact.jar", "artifact\n");
        Project project = ProjectBuilder.builder()
                .withProjectDir(temporaryDirectory.toFile())
                .build();
        VerifyReleaseEvidenceReportTask task = project.getTasks().register(
                "verifyEvidence",
                VerifyReleaseEvidenceReportTask.class).get();
        task.getEvidenceFile().set(evidence.toFile());
        task.getMarkdownFile().set(markdown.toFile());
        task.getJarFile().set(artifact.toFile());
        task.getSourcesJarFile().set(artifact.toFile());
        task.getJavadocJarFile().set(artifact.toFile());
        task.getSourceReleaseFile().set(artifact.toFile());
        task.getMinimumTestCount().set(1);
        task.getSourceDateEpoch().set("0");
        Path verification = temporaryDirectory.resolve("verification.json");
        task.getVerificationReportFile().set(verification.toFile());

        // when
        GradleException firstFailure = assertThrows(GradleException.class, task::verify);
        String firstReport = Files.readString(verification, StandardCharsets.UTF_8);
        GradleException secondFailure = assertThrows(GradleException.class, task::verify);
        String secondReport = Files.readString(verification, StandardCharsets.UTF_8);

        // then
        assertEquals(firstFailure.getMessage(), secondFailure.getMessage());
        assertEquals(firstReport, secondReport);
        assertTrue(firstReport.contains(
                "\"schema\":\"blue-language-java-release-evidence-verification/1.0\""));
        assertTrue(firstReport.contains("\"verified\":false"));
        assertTrue(firstReport.contains("\"unexpected-release-evidence-schema\""));
    }

    private Path write(String name, String value) throws Exception {
        return Files.writeString(
                temporaryDirectory.resolve(name), value, StandardCharsets.UTF_8);
    }
}
