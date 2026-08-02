package blue.buildlogic;

import blue.buildlogic.tasks.GenerateFinalQualityReportTask;
import blue.buildlogic.tasks.GenerateJavaApiInventoryTask;
import blue.buildlogic.tasks.VerifyFinalQualityReportTask;
import blue.buildlogic.tasks.VerifyJavaModuleStructureTask;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import me.champeau.jmh.JMHTask;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;

/** Registers the one final quality report and enforcement task over completed release gates. */
final class FinalQualityOrchestration {

    private static final List<String> REQUIRED_SMOKE_BENCHMARKS =
            Collections.unmodifiableList(Arrays.asList(
                    "blue.language.ReferenceBlueIdValidationBenchmark.resolveDeepValidReferenceDocument",
                    "blue.language.ProcessingSelectionCacheBenchmark.processWarmSameNode"));
    private static final Map<String, String> CLASS_SIZE_RATIONALES = classSizeRationales();

    private FinalQualityOrchestration() {}

    static Tasks register(
            Project project,
            List<String> publishedModules,
            TaskProvider<Task> releaseVerify,
            TaskProvider<Task> benchmarkClasses,
            TaskProvider<GenerateJavaApiInventoryTask> apiUnion,
            TaskProvider<VerifyJavaModuleStructureTask> moduleStructure,
            DocumentationQualityOrchestration.Tasks documentation) {
        ConfigurableFileTree productionSources = project.fileTree(project.getRootDir(), tree -> {
            for (String module : publishedModules) {
                tree.include(module + "/src/main/java/**/*.java");
            }
        });
        ConfigurableFileTree apiInventories = project.fileTree(project.getRootDir(), tree ->
                tree.include("blue-*/build/reports/api/current-api.txt"));
        ConfigurableFileTree tests = project.fileTree(project.getRootDir(), tree -> tree.include(
                "build/test-results/**/*.xml",
                "blue-*/build/test-results/**/*.xml",
                "examples/build/test-results/**/*.xml"));
        ConfigurableFileTree packageCycles = project.fileTree(project.getRootDir(), tree ->
                tree.include("blue-*/build/reports/architecture/package-cycles.json"));
        ConfigurableFileCollection moduleArtifacts = project.files();
        project.getGradle().projectsEvaluated(ignored -> {
            for (String moduleName : publishedModules) {
                Project module = project.project(":" + moduleName);
                moduleArtifacts.from(module.getTasks().named("jar", Jar.class)
                        .flatMap(Jar::getArchiveFile));
            }
        });

        TaskProvider<JMHTask> jmh = project.getTasks().named("jmh", JMHTask.class);
        if (isFinalQualityInvocation(project)) {
            jmh.configure(task -> {
                task.getIncludes().set(requiredSmokeIncludes());
                task.getWarmupIterations().set(0);
                task.getIterations().set(1);
                task.getFork().set(1);
                task.getTimeOnIteration().set("25ms");
                task.getFailOnError().set(true);
                task.getResultFormat().set("JSON");
                task.getResultsFile().set(project.getLayout().getBuildDirectory()
                        .file("reports/benchmarks/required-smoke.json"));
            });
        }

        Provider<String> sourceCommit = project.getProviders()
                .environmentVariable("GIT_COMMIT")
                .orElse(project.getProviders().exec(spec -> {
                    spec.setWorkingDir(project.getRootDir());
                    spec.commandLine("git", "rev-parse", "--verify", "HEAD^{commit}");
                }).getStandardOutput().getAsText().map(String::trim));
        Provider<org.gradle.api.file.RegularFile> conformance = project.project(":blue-conformance")
                .getLayout().getBuildDirectory()
                .file("reports/conformance/release-conformance.json");
        List<String> excludedTasks = new ArrayList<>(
                project.getGradle().getStartParameter().getExcludedTaskNames());
        Collections.sort(excludedTasks);

        TaskProvider<GenerateFinalQualityReportTask> report = project.getTasks().register(
                BuildLogicConstants.TASK_GENERATE_FINAL_QUALITY_REPORT,
                GenerateFinalQualityReportTask.class,
                task -> {
                    task.setGroup(BuildLogicConstants.VERIFICATION_GROUP);
                    task.setDescription(
                            "Generates the complete machine-readable final release quality decision.");
                    task.getRepositoryRoot().set(project.getLayout().getProjectDirectory());
                    task.getProductionSources().from(productionSources);
                    task.getApiInventories().from(apiInventories);
                    task.getModuleArtifacts().from(moduleArtifacts);
                    task.getTestResults().from(tests);
                    task.getPackageCycleReports().from(packageCycles);
                    task.getReleaseConformanceReport().set(conformance);
                    task.getDocumentationReport().set(documentation.analysis.flatMap(
                            blue.buildlogic.tasks.GenerateDocumentationVerificationReportTask::getReportFile));
                    task.getModuleStructureReport().set(moduleStructure.flatMap(
                            VerifyJavaModuleStructureTask::getReportFile));
                    task.getLanguageSpecification().set(project.getLayout().getProjectDirectory()
                            .file("blue-conformance/src/main/resources/language/1.0/spec.md"));
                    task.getContractsSpecification().set(project.getLayout().getProjectDirectory()
                            .file("blue-conformance/src/main/resources/contract/1.0/spec.md"));
                    task.getBenchmarkResults().set(jmh.flatMap(JMHTask::getResultsFile));
                    task.getPublishedRepositoryReport().set(project.getLayout().getBuildDirectory()
                            .file(BuildLogicConstants.REPORT_PUBLISHED_REPOSITORY));
                    task.getPublishedSmokeReport().set(project.getLayout().getBuildDirectory()
                            .file("reports/published-smoke/verification.json"));
                    task.getSourceCommit().set(sourceCommit);
                    task.getExcludedTasks().set(excludedTasks);
                    task.getClassSizeRationales().set(CLASS_SIZE_RATIONALES);
                    task.getRequiredSmokeBenchmarks().set(REQUIRED_SMOKE_BENCHMARKS);
                    task.getExpectedModuleCount().set(publishedModules.size());
                    task.getJavadocsSuccessful().set(true);
                    task.getExamplesCompiled().set(true);
                    task.getBenchmarksCompiled().set(true);
                    task.getReportFile().set(project.getLayout().getBuildDirectory()
                            .file(BuildLogicConstants.REPORT_FINAL_QUALITY));
                    task.dependsOn(
                            releaseVerify,
                            apiUnion,
                            moduleStructure,
                            documentation.analysis,
                            documentation.allJavadocs,
                            benchmarkClasses,
                            jmh,
                            ":examples:check");
                });

        TaskProvider<VerifyFinalQualityReportTask> verification = project.getTasks().register(
                BuildLogicConstants.TASK_FINAL_QUALITY_VERIFY,
                VerifyFinalQualityReportTask.class,
                task -> {
                    task.setGroup(BuildLogicConstants.VERIFICATION_GROUP);
                    task.setDescription("Runs and enforces every final Language 1.0 release gate.");
                    task.getQualityReport().set(report.flatMap(
                            GenerateFinalQualityReportTask::getReportFile));
                    task.getVerificationReport().set(project.getLayout().getBuildDirectory()
                            .file(BuildLogicConstants.REPORT_FINAL_QUALITY_VERIFICATION));
                    task.dependsOn(report, releaseVerify, documentation.verification);
                });
        documentation.verification.configure(task -> task.mustRunAfter(report));
        return new Tasks(report, verification);
    }

    private static boolean isFinalQualityInvocation(Project project) {
        for (String requested : project.getGradle().getStartParameter().getTaskNames()) {
            String name = requested.substring(requested.lastIndexOf(':') + 1);
            if (name.equals(BuildLogicConstants.TASK_FINAL_QUALITY_VERIFY)
                    || name.equals(BuildLogicConstants.TASK_GENERATE_FINAL_QUALITY_REPORT)) {
                return true;
            }
        }
        return false;
    }

    /** Returns one exact alternation regex while retaining two report requirements. */
    static List<String> requiredSmokeIncludes() {
        List<String> exactPatterns = new ArrayList<>();
        for (String benchmark : REQUIRED_SMOKE_BENCHMARKS) {
            exactPatterns.add(Pattern.quote(benchmark));
        }
        return JmhConventionsPlugin.combineIncludePatterns(
                exactPatterns);
    }

    private static Map<String, String> classSizeRationales() {
        Map<String, String> rationales = new LinkedHashMap<>();
        rationales.put(
                "blue-conformance/src/main/java/blue/language/conformance/contracts/"
                        + "ContractsFixtureHarness.java",
                "Closed 154-fixture Contracts oracle; one ordered harness keeps fixture semantics "
                        + "and trace comparison auditable against the release package.");
        rationales.put(
                "blue-conformance/src/main/java/blue/language/conformance/api/"
                        + "BlueConformanceSuiteRunner.java",
                "Closed 153-fixture Language runner; one ordered dispatcher keeps operation and "
                        + "vector accounting auditable against the release package.");
        return Collections.unmodifiableMap(rationales);
    }

    /** Providers exposed for receipt or future release aliases. */
    static final class Tasks {
        final TaskProvider<GenerateFinalQualityReportTask> report;
        final TaskProvider<VerifyFinalQualityReportTask> verification;

        private Tasks(
                TaskProvider<GenerateFinalQualityReportTask> report,
                TaskProvider<VerifyFinalQualityReportTask> verification) {
            this.report = report;
            this.verification = verification;
        }
    }
}
