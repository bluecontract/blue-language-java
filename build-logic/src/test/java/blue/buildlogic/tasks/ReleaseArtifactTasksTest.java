package blue.buildlogic.tasks;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReleaseArtifactTasksTest {

    private static final String COMMIT = "0123456789012345678901234567890123456789";

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldGenerateVersionedMetadataAndAConventionalChecksum() throws Exception {
        // given
        Project project = project();
        Path source = Files.writeString(
                temporaryDirectory.resolve(".cz.toml"),
                "[tool.commitizen]\nversion = \"0.1.0\"\n",
                StandardCharsets.UTF_8);
        GenerateSourceReleaseMetadataTask metadata = project.getTasks().register(
                "metadata", GenerateSourceReleaseMetadataTask.class).get();
        metadata.getSourceFile().set(source.toFile());
        metadata.getReleaseVersion().set("2.0.0");
        metadata.getOutputFile().set(project.getLayout().getBuildDirectory()
                .file("metadata/.cz.toml"));
        GenerateChecksumFileTask checksum = project.getTasks().register(
                "checksum", GenerateChecksumFileTask.class).get();
        checksum.getInputFile().set(metadata.getOutputFile());
        checksum.getOutputFile().set(project.getLayout().getBuildDirectory()
                .file("metadata/.cz.toml.sha256"));

        // when
        metadata.generate();
        checksum.generate();

        // then
        String generated = Files.readString(
                metadata.getOutputFile().get().getAsFile().toPath(), StandardCharsets.UTF_8);
        String tracked = Files.readString(source, StandardCharsets.UTF_8);
        String sidecar = Files.readString(
                checksum.getOutputFile().get().getAsFile().toPath(), StandardCharsets.UTF_8);
        assertTrue(generated.contains("version = \"2.0.0\""));
        assertTrue(tracked.contains("version = \"0.1.0\""));
        assertTrue(sidecar.matches("[0-9a-f]{64}  \\.cz\\.toml\\n"));
    }

    @Test
    void shouldGenerateAndVerifyCleanBuildEvidenceThenRejectChangedSource() throws Exception {
        // given
        Project project = project();
        Path source = Files.writeString(
                temporaryDirectory.resolve("source.txt"), "first", StandardCharsets.UTF_8);
        GenerateCleanSourceEvidenceTask clean = project.getTasks().register(
                "cleanEvidence", GenerateCleanSourceEvidenceTask.class).get();
        configure(clean, source);
        clean.getOutputFile().set(project.getLayout().getBuildDirectory()
                .file("clean-source.json"));
        GenerateCleanBuildEvidenceTask build = project.getTasks().register(
                "buildEvidence", GenerateCleanBuildEvidenceTask.class).get();
        build.getSourceFiles().from(source.toFile());
        build.getSourceRoot().set(project.getLayout().getProjectDirectory());
        build.getCleanSourceEvidenceFile().set(clean.getOutputFile());
        build.getSourceCommit().set(COMMIT);
        build.getSourceDateEpoch().set("42");
        build.getCleanTaskPath().set(":clean");
        build.getBuildTaskPath().set(":build");
        build.getInvocationTasks().set(Arrays.asList("clean", "build"));
        build.getExcludedTasks().set(Collections.emptyList());
        build.getCleanTaskExecuted().set(true);
        build.getBuildTaskSuccessful().set(true);
        build.getOutputFile().set(project.getLayout().getBuildDirectory()
                .file("clean-build.json"));
        VerifyCleanBuildEvidenceTask verify = project.getTasks().register(
                "verifyCleanEvidence", VerifyCleanBuildEvidenceTask.class).get();
        verify.getSourceFiles().from(source.toFile());
        verify.getSourceRoot().set(project.getLayout().getProjectDirectory());
        verify.getEvidenceFile().set(build.getOutputFile());
        verify.getSourceCommit().set(COMMIT);
        verify.getSourceDateEpoch().set("42");
        verify.getCleanTaskPath().set(":clean");
        verify.getBuildTaskPath().set(":build");
        verify.getReportFile().set(project.getLayout().getBuildDirectory()
                .file("clean-verification.json"));

        // when / then
        clean.generate();
        build.generate();
        assertDoesNotThrow(verify::verify);
        assertTrue(build.getOutputFile().get().getAsFile().isFile());
        Files.writeString(source, "second", StandardCharsets.UTF_8);
        assertThrows(GradleException.class, verify::verify);
        assertFalse(Files.readString(
                verify.getReportFile().get().getAsFile().toPath(), StandardCharsets.UTF_8)
                .contains("\"verified\":true"));
    }

    private Project project() {
        return ProjectBuilder.builder()
                .withProjectDir(temporaryDirectory.toFile())
                .build();
    }

    private static void configure(GenerateCleanSourceEvidenceTask task, Path source) {
        task.getSourceFiles().from(source.toFile());
        task.getSourceRoot().set(task.getProject().getLayout().getProjectDirectory());
        task.getSourceCommit().set(COMMIT);
        task.getSourceDateEpoch().set("42");
        task.getCleanTaskPath().set(":clean");
        task.getInvocationTasks().set(Arrays.asList("clean", "build"));
        task.getExcludedTasks().set(Collections.emptyList());
    }
}
