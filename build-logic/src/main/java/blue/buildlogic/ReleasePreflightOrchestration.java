package blue.buildlogic;

import blue.buildlogic.support.FinalQualityEvidence;
import blue.buildlogic.support.JavaSourceQuality;
import blue.buildlogic.tasks.GenerateFinalQualityReportTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.Exec;
import org.gradle.api.tasks.TaskProvider;

/** The inexpensive checks that must precede release corpora, with unchanged policy. */
final class ReleasePreflightOrchestration {
    private ReleasePreflightOrchestration() {}

    static TaskProvider<Task> register(Project project, FileCollection sources, TaskProvider<Task> release) {
        var size = project.getTasks().register("verifyClassSizePreflight", task -> {
            task.setGroup("verification");
            task.setDescription("Checks the exact final-quality class-size policy before tests.");
            task.getInputs().files(sources);
            task.doLast(ignored -> {
                List<String> blockers = new ArrayList<>();
                Map<String, Object> result = FinalQualityEvidence.classes(JavaSourceQuality.analyze(
                                project.getRootDir().toPath(), sources.getFiles().stream()
                                        .map(java.io.File::toPath).collect(Collectors.toList())),
                        FinalQualityOrchestration.classSizeRationales(),
                        GenerateFinalQualityReportTask.DEFAULT_MAXIMUM_ORDINARY_CLASS_LINES, blockers);
                task.getLogger().lifecycle("CLASS_SIZE_PREFLIGHT limit={} violations={} staleRationales={}",
                        result.get("lineLimit"), result.get("unallowlistedOverLimit"), result.get("staleAllowlistEntries"));
                if (!blockers.isEmpty()) throw new GradleException("Final class-size policy failed; unchanged limit "
                        + result.get("lineLimit") + ": " + result.get("unallowlistedOverLimit")
                        + "; stale rationales=" + result.get("staleAllowlistEntries"));
            });
        });
        var inputs = project.getTasks().register("verifyPackageGeneratorInputs", Exec.class, task -> {
            task.setGroup("verification");
            task.setDescription("Checks complete lifecycle generator inputs without staging or execution.");
            task.commandLine(project.getProviders().gradleProperty("bluePythonExecutable").orElse("python3").get(),
                    "blue-conformance/src/main/tools/regenerate_package.py", "--validate-inputs-only",
                    "--package-root", "blue-conformance/src/main/resources/blue-contracts-closure-1.0",
                    "--repository-root", project.getRootDir().getAbsolutePath(),
                    "--fixture-source-root", "blue-conformance/src/main/fixture-sources/full-lifecycle");
        });
        var early = project.getTasks().register("releasePreflight", task -> {
            task.setGroup("verification");
            task.setDescription("Fail early on candidate bindings, sentinel audit, class size and lifecycle inputs.");
            task.dependsOn("candidatePreflight", "verifyEmptySentinelAudit", size, inputs);
        });
        release.configure(task -> task.dependsOn(early));
        project.getAllprojects().forEach(module -> {
            module.getTasks().withType(org.gradle.api.tasks.testing.Test.class).configureEach(task -> task.mustRunAfter(early));
            module.getTasks().withType(org.gradle.api.tasks.compile.JavaCompile.class).configureEach(task -> task.mustRunAfter(early));
            module.getTasks().withType(org.gradle.api.tasks.JavaExec.class).configureEach(task -> task.mustRunAfter(early));
        });
        project.getTasks().named("jmh").configure(task -> task.mustRunAfter(early));
        return early;
    }
}
