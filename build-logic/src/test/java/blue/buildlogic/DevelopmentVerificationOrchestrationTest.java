package blue.buildlogic;

import blue.buildlogic.tasks.DevelopmentTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Guards the boundary between development gates and complete release orchestration. */
final class DevelopmentVerificationOrchestrationTest {
    @TempDir Path directory;

    @Test
    void shouldKeepFocusedGraphsNarrowAndReleaseDependenciesUntouched() {
        Project project = project();
        Task release = project.getTasks().register("releaseVerify").get();
        release.dependsOn("test");

        DevelopmentVerificationOrchestration.register(project);

        Set<String> focused = graph(project.getTasks().getByName("focusedTest"));
        assertTrue(focused.contains(":testClasses"));
        assertFalse(focused.contains(":test"));
        assertFalse(focused.contains(":developmentPreflight"));
        assertEquals(Set.of(":test"), release.getTaskDependencies().getDependencies(release)
                .stream().map(Task::getPath).collect(java.util.stream.Collectors.toSet()));
        Set<String> fast = graph(project.getTasks().getByName("fastVerify"));
        assertTrue(fast.containsAll(Set.of(":developmentPreflight", ":verifySpecificationMirrors",
                ":verifySourceDevelopment", ":verifyBuildScriptShape",
                ":languageComponentTest", ":contractsComponentTest")));
        assertFalse(fast.contains(":verifyAggregateReleaseManifest"));
        assertTrue(graph(project.getTasks().getByName("candidatePreflight"))
                .contains(":verifyAggregateReleaseManifest"));
        for (String name : new String[] {":test", ":clean", ":build", ":releaseVerify",
                ":releaseConformanceTest", ":finalQualityVerify", ":rcVerify",
                ":generateDocumentationReferences", ":stagePublications"}) {
            assertFalse(fast.contains(name), name);
        }
        Task preflight = project.getTasks().getByName("developmentPreflight");
        Task compile = project.getTasks().getByName("compileTestJava");
        assertTrue(compile.getMustRunAfter().getDependencies(compile).contains(preflight));
    }

    @Test
    void shouldGiveEveryDevelopmentTaskItsOwnReportsAndBoundedWorker() {
        Project project = project();
        DevelopmentVerificationOrchestration.register(project);
        Set<Path> outputs = new LinkedHashSet<>();
        for (DevelopmentTest task : project.getTasks().withType(DevelopmentTest.class)) {
            Path inventory = task.getInventoryFile().get().getAsFile().toPath();
            assertTrue(outputs.add(inventory));
            assertTrue(inventory.toString().contains("build/development-verification/"));
            assertFalse(inventory.toString().contains("test-results"));
            assertEquals(1, task.getMaxParallelForks());
            assertEquals("512m", task.getMaxHeapSize());
        }
        assertEquals(4, outputs.size());
    }

    @Test
    void shouldRejectChangedSpecificationMirrorWithoutRewritingEitherFile() throws Exception {
        Project project = project();
        DevelopmentVerificationOrchestration.register(project);
        String[] paths = {
                "blue-language-core/src/main/resources/specifications/blue-language-specification-1.0.md",
                "blue-conformance/src/main/resources/language/1.0/spec.md",
                "blue-contracts-core/src/main/resources/specifications/blue-contracts-and-processor-specification-1.0.md",
                "blue-conformance/src/main/resources/contract/1.0/spec.md"};
        for (String relative : paths) {
            Path path = directory.resolve(relative);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "same specification\n");
        }
        Task mirrors = project.getTasks().getByName("verifySpecificationMirrors");
        mirrors.getActions().forEach(action -> action.execute(mirrors));
        Files.writeString(directory.resolve(paths[1]), "drift\n");

        assertThrows(GradleException.class,
                () -> mirrors.getActions().forEach(action -> action.execute(mirrors)));
        assertEquals("drift\n", Files.readString(directory.resolve(paths[1])));
        assertEquals("same specification\n", Files.readString(directory.resolve(paths[0])));
    }

    private Project project() {
        Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
        project.getPluginManager().apply("java");
        for (String task : new String[] {"verifyAggregateReleaseManifest", "verifyBuildScriptShape",
                "cacheLifecycleTest", "patchSequenceDifferentialTest"}) {
            project.getTasks().register(task);
        }
        return project;
    }

    private static Set<String> graph(Task task) {
        Set<String> paths = new LinkedHashSet<>();
        visit(task, paths);
        return paths;
    }

    private static void visit(Task task, Set<String> paths) {
        if (paths.add(task.getPath())) {
            task.getTaskDependencies().getDependencies(task).forEach(child -> visit(child, paths));
        }
    }
}
