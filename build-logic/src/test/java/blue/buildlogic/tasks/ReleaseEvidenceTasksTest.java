package blue.buildlogic.tasks;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReleaseEvidenceTasksTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldDetectWhenGeneratedEvidenceBecomesStale() throws Exception {
        // given
        Path source = Files.writeString(
                temporaryDirectory.resolve("source.txt"), "first", StandardCharsets.UTF_8);
        Project project = ProjectBuilder.builder()
                .withProjectDir(temporaryDirectory.toFile())
                .build();
        GenerateReleaseEvidenceTask generate = project.getTasks().register(
                "generateTestEvidence", GenerateReleaseEvidenceTask.class).get();
        generate.getSourceFiles().from(source.toFile());
        generate.getSourceRoot().set(project.getLayout().getProjectDirectory());
        generate.getSourceCommit().set("test-commit");
        generate.getSourceDateEpoch().set("7");
        generate.getOutputFile().set(project.getLayout().getProjectDirectory()
                .file("build/evidence.json"));
        VerifyInputIdentityTask verify = project.getTasks().register(
                "verifyTestEvidence", VerifyInputIdentityTask.class).get();
        verify.getSourceFiles().from(source.toFile());
        verify.getSourceRoot().set(project.getLayout().getProjectDirectory());
        verify.getEvidenceFile().set(project.getLayout().getProjectDirectory()
                .file("build/evidence.json"));

        // when / then
        generate.generate();
        assertDoesNotThrow(verify::verify);
        Files.writeString(source, "second", StandardCharsets.UTF_8);
        assertThrows(GradleException.class, verify::verify);
    }
}
