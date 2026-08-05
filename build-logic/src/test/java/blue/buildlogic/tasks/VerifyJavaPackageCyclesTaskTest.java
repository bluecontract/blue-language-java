package blue.buildlogic.tasks;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.buildlogic.support.TestJavaCompiler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class VerifyJavaPackageCyclesTaskTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldWritePassingMachineReadableReport() throws Exception {
        // given
        Path compiled = compile(
                "passing",
                "package first; public final class First { public second.Second next; }",
                "package second; public final class Second {}");
        VerifyJavaPackageCyclesTask task = task("passing-task", compiled);

        // when
        assertDoesNotThrow(task::verify);
        String report = Files.readString(
                task.getReportFile().get().getAsFile().toPath(),
                StandardCharsets.UTF_8);

        // then
        assertTrue(report.contains("\"acyclic\":true"));
        assertTrue(report.contains("\"cycleCount\":0"));
        assertTrue(report.contains(
                "\"edges\":[{\"source\":\"first\","
                        + "\"target\":\"second\"}]"));
    }

    @Test
    void shouldWriteFailingReportBeforeRejectingPackageCycle()
            throws Exception {
        // given
        Path compiled = compile(
                "failing",
                "package first; public final class First { public second.Second next; }",
                "package second; public final class Second { public first.First next; }");
        VerifyJavaPackageCyclesTask task = task("failing-task", compiled);

        // when
        GradleException failure = assertThrows(GradleException.class, task::verify);
        String report = Files.readString(
                task.getReportFile().get().getAsFile().toPath(),
                StandardCharsets.UTF_8);

        // then
        assertTrue(failure.getMessage().contains("1 cycle(s) [[first, second]]"));
        assertTrue(report.contains("\"acyclic\":false"));
        assertTrue(report.contains("\"cycleCount\":1"));
        assertTrue(report.contains(
                "\"cycles\":[[\"first\",\"second\"]]"));
    }

    private Path compile(String name, String firstSource, String secondSource)
            throws Exception {
        Path root = temporaryDirectory.resolve(name);
        Path first = TestJavaCompiler.source(
                root.resolve("src"), "first/First.java", firstSource);
        Path second = TestJavaCompiler.source(
                root.resolve("src"), "second/Second.java", secondSource);
        Path output = root.resolve("classes");
        TestJavaCompiler.compile(output, first, second);
        return output;
    }

    private VerifyJavaPackageCyclesTask task(String name, Path compiled)
            throws Exception {
        Project project = ProjectBuilder.builder()
                .withName(name)
                .withProjectDir(Files.createDirectories(
                        temporaryDirectory.resolve(name + "-project")).toFile())
                .build();
        VerifyJavaPackageCyclesTask task = project.getTasks().register(
                "verifyCycles", VerifyJavaPackageCyclesTask.class).get();
        task.getCompiledInputs().from(compiled);
        task.getReportFile().set(project.getLayout().getBuildDirectory()
                .file("reports/package-cycles.json"));
        return task;
    }
}
