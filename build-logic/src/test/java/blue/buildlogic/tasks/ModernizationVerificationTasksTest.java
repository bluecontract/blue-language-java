package blue.buildlogic.tasks;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ModernizationVerificationTasksTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldDeclareEveryNewEvidenceProducerAsCacheable() {
        // given
        java.util.List<Class<?>> taskTypes = Arrays.asList(
                GenerateJavaApiInventoryTask.class,
                GenerateJavaModuleInventoryTask.class,
                VerifyJavaPackageCyclesTask.class,
                VerifyJavaModuleStructureTask.class,
                CompareArchiveReplicasTask.class,
                GenerateAggregateReleaseReceiptTask.class,
                VerifyAggregateReleaseReceiptTask.class,
                GenerateDocumentationReferencesTask.class,
                GenerateDocumentationVerificationReportTask.class,
                VerifyDocumentationReportTask.class);

        // when / then
        taskTypes.forEach(type -> assertTrue(
                type.isAnnotationPresent(CacheableTask.class), type.getSimpleName()));
    }

    @Test
    void shouldGenerateAndUnionConfiguredApiInventoryInputs() throws Exception {
        // given
        Project project = project("api-project");
        Path first = write("api-project/first.txt", "# module: first\ntype z.Z access=public\n");
        Path second = write("api-project/second.txt", "# module: second\ntype a.A access=public\n");
        GenerateJavaApiInventoryTask task = project.getTasks().register(
                "inventory", GenerateJavaApiInventoryTask.class).get();
        task.getModuleName().set("aggregate");
        task.getUnionInputs().from(first, second);
        task.getOutputFile().set(project.getLayout().getBuildDirectory().file("api.txt"));

        // when
        task.generate();
        String inventory = Files.readString(
                task.getOutputFile().get().getAsFile().toPath(), StandardCharsets.UTF_8);

        // then
        assertTrue(inventory.indexOf("type a.A") < inventory.indexOf("type z.Z"));
        assertTrue(inventory.contains("# entryCount: 2"));
    }

    @Test
    void shouldGenerateAndVerifySourceBasedModuleInventories() throws Exception {
        // given
        Project project = project("module-project");
        Path firstSource = write(
                "module-project/src/first/First.java",
                "package first.api;\nimport second.api.Second;\nclass First {}\n");
        Path secondSource = write(
                "module-project/src/second/Second.java",
                "package second.api;\nclass Second {}\n");
        GenerateJavaModuleInventoryTask first = moduleInventoryTask(
                project, "firstInventory", "first", firstSource, "first.txt");
        GenerateJavaModuleInventoryTask second = moduleInventoryTask(
                project, "secondInventory", "second", secondSource, "second.txt");
        first.generate();
        second.generate();
        VerifyJavaModuleStructureTask verify = project.getTasks().register(
                "verifyModules", VerifyJavaModuleStructureTask.class).get();
        verify.getModuleInventories().from(
                first.getOutputFile().get().getAsFile(), second.getOutputFile().get().getAsFile());
        verify.getAllowedEdges().set(java.util.Collections.singletonList("first->second"));
        verify.getEnforceAllowedEdges().set(true);
        verify.getReportFile().set(project.getLayout().getBuildDirectory().file("modules.json"));

        // when / then
        assertDoesNotThrow(verify::verify);
        assertTrue(Files.readString(
                        verify.getReportFile().get().getAsFile().toPath(), StandardCharsets.UTF_8)
                .contains("\"valid\":true"));
    }

    @Test
    void shouldWriteArchiveReplicaEvidenceAndFailAfterAByteChange() throws Exception {
        // given
        Project project = project("archive-project");
        Path reference = write("archive-project/reference/blue.jar", "same");
        Path replica = write("archive-project/replica/blue.jar", "same");
        CompareArchiveReplicasTask task = project.getTasks().register(
                "compareReplicas", CompareArchiveReplicasTask.class).get();
        task.getReferenceArchives().from(reference);
        task.getReplicaArchives().from(replica);
        task.getReportFile().set(project.getLayout().getBuildDirectory().file("replicas.json"));

        // when
        assertDoesNotThrow(task::compare);
        Files.writeString(replica, "changed", StandardCharsets.UTF_8);

        // then
        assertThrows(GradleException.class, task::compare);
        assertTrue(Files.readString(
                        task.getReportFile().get().getAsFile().toPath(), StandardCharsets.UTF_8)
                .contains("\"identical\":false"));
    }

    @Test
    void shouldRejectAnEmptyArchiveReplicaProof() throws Exception {
        // given
        Project project = project("empty-archive-project");
        CompareArchiveReplicasTask task = project.getTasks().register(
                "compareEmptyReplicas", CompareArchiveReplicasTask.class).get();
        task.getReportFile().set(project.getLayout().getBuildDirectory().file("replicas.json"));

        // when
        GradleException failure = assertThrows(GradleException.class, task::compare);

        // then
        assertTrue(failure.getMessage().contains("requires at least one"));
    }

    @Test
    void shouldGenerateAndVerifyAggregateReceiptUntilAnInputChanges() throws Exception {
        // given
        Project project = project("receipt-project");
        Path artifact = write("receipt-project/build/libs/blue.jar", "first");
        GenerateAggregateReleaseReceiptTask generate = project.getTasks().register(
                "generateReceipt", GenerateAggregateReleaseReceiptTask.class).get();
        configureReceiptInputs(generate, project, artifact);
        generate.getOutputFile().set(project.getLayout().getBuildDirectory().file("receipt.json"));
        generate.generate();
        VerifyAggregateReleaseReceiptTask verify = project.getTasks().register(
                "verifyReceipt", VerifyAggregateReleaseReceiptTask.class).get();
        configureReceiptInputs(verify, project, artifact);
        verify.getReceiptFile().set(generate.getOutputFile());
        verify.getVerificationReportFile().set(
                project.getLayout().getBuildDirectory().file("receipt-verification.json"));

        // when
        assertDoesNotThrow(verify::verify);
        Files.writeString(artifact, "second", StandardCharsets.UTF_8);

        // then
        assertThrows(GradleException.class, verify::verify);
        assertTrue(Files.readString(
                        verify.getVerificationReportFile().get().getAsFile().toPath(),
                        StandardCharsets.UTF_8)
                .contains("\"verified\":false"));
    }

    private Project project(String name) throws Exception {
        return ProjectBuilder.builder()
                .withName(name)
                .withProjectDir(Files.createDirectories(temporaryDirectory.resolve(name)).toFile())
                .build();
    }

    private GenerateJavaModuleInventoryTask moduleInventoryTask(
            Project project,
            String taskName,
            String moduleName,
            Path source,
            String outputName) {
        GenerateJavaModuleInventoryTask task = project.getTasks().register(
                taskName, GenerateJavaModuleInventoryTask.class).get();
        task.getModuleName().set(moduleName);
        task.getSourceInputs().from(source);
        task.getOutputFile().set(project.getLayout().getBuildDirectory().file(outputName));
        return task;
    }

    private static void configureReceiptInputs(
            GenerateAggregateReleaseReceiptTask task, Project project, Path artifact) {
        task.getReceiptRoot().set(project.getLayout().getProjectDirectory());
        task.getArtifacts().from(artifact);
        task.getSourceCommit().set("commit");
        task.getSourceDateEpoch().set("9");
    }

    private static void configureReceiptInputs(
            VerifyAggregateReleaseReceiptTask task, Project project, Path artifact) {
        task.getReceiptRoot().set(project.getLayout().getProjectDirectory());
        task.getArtifacts().from(artifact);
        task.getSourceCommit().set("commit");
        task.getSourceDateEpoch().set("9");
    }

    private Path write(String relativePath, String content) throws Exception {
        Path file = temporaryDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
