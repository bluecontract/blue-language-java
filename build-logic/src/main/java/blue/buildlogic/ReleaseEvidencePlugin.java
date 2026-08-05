package blue.buildlogic;

import blue.buildlogic.tasks.GenerateAggregateReleaseReceiptTask;
import blue.buildlogic.tasks.GenerateCleanBuildEvidenceTask;
import blue.buildlogic.tasks.GenerateCleanSourceEvidenceTask;
import blue.buildlogic.tasks.GenerateReleaseEvidenceTask;
import blue.buildlogic.tasks.VerifyAggregateReleaseReceiptTask;
import blue.buildlogic.tasks.VerifyCleanBuildEvidenceTask;
import blue.buildlogic.tasks.VerifyInputIdentityTask;
import blue.buildlogic.tasks.VerifyJavaModuleStructureTask;
import blue.buildlogic.support.RepositorySourceFiles;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import java.util.ArrayList;
import java.util.Collections;

/** Adds generation and stale-input verification for deterministic release evidence. */
public final class ReleaseEvidencePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        ConfigurableFileTree sourceInputs = RepositorySourceFiles.create(project);

        Provider<String> gitCommit = project.getProviders()
                .environmentVariable("GIT_COMMIT")
                .orElse(project.getProviders().exec(spec -> {
                    spec.setWorkingDir(project.getRootDir());
                    spec.commandLine("git", "rev-parse", "--verify", "HEAD^{commit}");
                }).getStandardOutput().getAsText().map(String::trim));
        Provider<String> sourceDateEpoch = project.getProviders()
                .environmentVariable("SOURCE_DATE_EPOCH")
                .orElse("0");
        Provider<RegularFile> evidenceFile = project.getLayout().getBuildDirectory()
                .file(BuildLogicConstants.REPORT_SOURCE_INPUT_EVIDENCE);
        Provider<RegularFile> aggregateReceiptFile = project.getLayout().getBuildDirectory()
                .file(BuildLogicConstants.REPORT_AGGREGATE_RELEASE_RECEIPT);
        Provider<RegularFile> cleanSourceEvidenceFile = project.getLayout().getBuildDirectory()
                .file(BuildLogicConstants.REPORT_CLEAN_SOURCE_EVIDENCE);
        Provider<RegularFile> cleanBuildEvidenceFile = project.getLayout().getBuildDirectory()
                .file(BuildLogicConstants.REPORT_CLEAN_BUILD_EVIDENCE);
        java.util.List<String> invocationTasks =
                new ArrayList<>(project.getGradle().getStartParameter().getTaskNames());
        java.util.List<String> excludedTasks =
                new ArrayList<>(project.getGradle().getStartParameter().getExcludedTaskNames());
        Collections.sort(excludedTasks);

        ConfigurableFileTree artifactInputs = project.fileTree(project.getRootDir());
        artifactInputs.include("**/build/libs/*.jar", "**/build/libs/*.zip");
        ConfigurableFileTree testEvidenceInputs = project.fileTree(project.getRootDir());
        testEvidenceInputs.include(
                "**/build/test-results/**/*.xml", "**/build/reports/tests/**/*.json");
        ConfigurableFileTree fixtureEvidenceInputs = project.fileTree(project.getRootDir());
        fixtureEvidenceInputs.include(
                "**/build/reports/conformance/**/*.json",
                "**/build/reports/fixtures/**/*.json");
        ConfigurableFileTree apiEvidenceInputs = project.fileTree(project.getRootDir());
        apiEvidenceInputs.include(
                "**/api/public-api.txt",
                "**/build/reports/api/current-api*.txt",
                "**/build/reports/api/*.json");
        ConfigurableFileTree moduleInventories = project.fileTree(project.getRootDir());
        moduleInventories.include("**/build/reports/module/module-inventory.txt");
        ConfigurableFileTree verificationEvidenceInputs = project.fileTree(project.getRootDir());
        verificationEvidenceInputs.include(
                "**/build/reports/architecture/**/*.json",
                "**/build/reports/reproducibility/**/*.json",
                "**/build/reports/published-repository/**/*.json",
                "**/build/reports/published-smoke/**/*.json",
                "**/build/reports/runtime-trace/**/*.json");

        project.getTasks().register("generateReleaseEvidence", GenerateReleaseEvidenceTask.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription("Generates deterministic source input release evidence.");
                    task.getSourceFiles().from(sourceInputs);
                    task.getSourceRoot().set(project.getRootProject().getLayout()
                            .getProjectDirectory());
                    task.getSourceCommit().convention(gitCommit);
                    task.getSourceDateEpoch().convention(sourceDateEpoch);
                    task.getMetadata().put("projectPath", project.getPath());
                    task.getMetadata().put("projectVersion", project.provider(
                            () -> project.getVersion().toString()));
                    task.getOutputFile().set(evidenceFile);
                });

        project.getTasks().register("verifyReleaseEvidenceInputs", VerifyInputIdentityTask.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription("Fails when release evidence no longer matches source inputs.");
                    task.getSourceFiles().from(sourceInputs);
                    task.getSourceRoot().set(project.getRootProject().getLayout()
                            .getProjectDirectory());
                    task.getEvidenceFile().set(evidenceFile);
                });

        TaskProvider<GenerateCleanSourceEvidenceTask> generateCleanSource =
                project.getTasks().register(
                BuildLogicConstants.TASK_GENERATE_CLEAN_SOURCE_EVIDENCE,
                GenerateCleanSourceEvidenceTask.class,
                task -> {
                    task.setGroup(BuildLogicConstants.VERIFICATION_GROUP);
                    task.setDescription("Captures source identity immediately after root clean.");
                    task.getSourceFiles().from(sourceInputs);
                    task.getSourceRoot().set(project.getRootProject().getLayout()
                            .getProjectDirectory());
                    task.getSourceCommit().convention(gitCommit);
                    task.getSourceDateEpoch().convention(sourceDateEpoch);
                    task.getCleanTaskPath().set(BuildLogicConstants.ROOT_CLEAN_TASK_PATH);
                    task.getInvocationTasks().set(invocationTasks);
                    task.getExcludedTasks().set(excludedTasks);
                    task.getOutputFile().set(cleanSourceEvidenceFile);
                });

        TaskProvider<GenerateCleanBuildEvidenceTask> generateCleanBuild =
                project.getTasks().register(
                BuildLogicConstants.TASK_GENERATE_CLEAN_BUILD_EVIDENCE,
                GenerateCleanBuildEvidenceTask.class,
                task -> {
                    task.setGroup(BuildLogicConstants.VERIFICATION_GROUP);
                    task.setDescription(
                            "Records a successful exclusion-free build over captured clean source.");
                    task.getSourceFiles().from(sourceInputs);
                    task.getSourceRoot().set(project.getRootProject().getLayout()
                            .getProjectDirectory());
                    task.getCleanSourceEvidenceFile().set(cleanSourceEvidenceFile);
                    task.getSourceCommit().convention(gitCommit);
                    task.getSourceDateEpoch().convention(sourceDateEpoch);
                    task.getCleanTaskPath().set(BuildLogicConstants.ROOT_CLEAN_TASK_PATH);
                    task.getBuildTaskPath().set(BuildLogicConstants.ROOT_BUILD_TASK_PATH);
                    task.getInvocationTasks().set(invocationTasks);
                    task.getExcludedTasks().set(excludedTasks);
                    task.getOutputFile().set(cleanBuildEvidenceFile);
                });

        configureCleanBuildLifecycle(project, generateCleanSource, generateCleanBuild);

        project.getTasks().register(
                BuildLogicConstants.TASK_VERIFY_CLEAN_BUILD_EVIDENCE,
                VerifyCleanBuildEvidenceTask.class,
                task -> {
                    task.setGroup(BuildLogicConstants.VERIFICATION_GROUP);
                    task.setDescription(
                            "Verifies a prior clean build against current source and epoch.");
                    task.getSourceFiles().from(sourceInputs);
                    task.getSourceRoot().set(project.getRootProject().getLayout()
                            .getProjectDirectory());
                    task.getEvidenceFile().set(cleanBuildEvidenceFile);
                    task.getSourceCommit().convention(gitCommit);
                    task.getSourceDateEpoch().convention(sourceDateEpoch);
                    task.getCleanTaskPath().set(BuildLogicConstants.ROOT_CLEAN_TASK_PATH);
                    task.getBuildTaskPath().set(BuildLogicConstants.ROOT_BUILD_TASK_PATH);
                    task.getReportFile().set(project.getLayout().getBuildDirectory()
                            .file(BuildLogicConstants.REPORT_CLEAN_BUILD_VERIFICATION));
                });

        project.getTasks().register(
                BuildLogicConstants.TASK_GENERATE_AGGREGATE_RELEASE_RECEIPT,
                GenerateAggregateReleaseReceiptTask.class,
                task -> {
                    task.setGroup(BuildLogicConstants.VERIFICATION_GROUP);
                    task.setDescription(
                            "Generates aggregate artifact, test, fixture, and API release evidence.");
                    task.getReceiptRoot().set(project.getRootProject().getLayout()
                            .getProjectDirectory());
                    task.getArtifacts().from(artifactInputs);
                    task.getTestEvidence().from(testEvidenceInputs);
                    task.getFixtureEvidence().from(fixtureEvidenceInputs);
                    task.getApiEvidence().from(apiEvidenceInputs);
                    task.getVerificationEvidence().from(verificationEvidenceInputs);
                    task.getSourceCommit().convention(gitCommit);
                    task.getSourceDateEpoch().convention(sourceDateEpoch);
                    task.getMetadata().put("projectPath", project.getPath());
                    task.getMetadata().put("projectVersion", project.provider(
                            () -> project.getVersion().toString()));
                    task.getOutputFile().set(aggregateReceiptFile);
                });

        project.getTasks().register(
                BuildLogicConstants.TASK_VERIFY_AGGREGATE_RELEASE_RECEIPT,
                VerifyAggregateReleaseReceiptTask.class,
                task -> {
                    task.setGroup(BuildLogicConstants.VERIFICATION_GROUP);
                    task.setDescription("Verifies that aggregate release evidence is current.");
                    task.getReceiptRoot().set(project.getRootProject().getLayout()
                            .getProjectDirectory());
                    task.getArtifacts().from(artifactInputs);
                    task.getTestEvidence().from(testEvidenceInputs);
                    task.getFixtureEvidence().from(fixtureEvidenceInputs);
                    task.getApiEvidence().from(apiEvidenceInputs);
                    task.getVerificationEvidence().from(verificationEvidenceInputs);
                    task.getSourceCommit().convention(gitCommit);
                    task.getSourceDateEpoch().convention(sourceDateEpoch);
                    task.getMetadata().put("projectPath", project.getPath());
                    task.getMetadata().put("projectVersion", project.provider(
                            () -> project.getVersion().toString()));
                    task.getReceiptFile().set(aggregateReceiptFile);
                    task.getVerificationReportFile().set(project.getLayout().getBuildDirectory()
                            .file(BuildLogicConstants.REPORT_AGGREGATE_RELEASE_VERIFICATION));
                });

        project.getTasks().register(
                BuildLogicConstants.TASK_VERIFY_MODULE_STRUCTURE,
                VerifyJavaModuleStructureTask.class,
                task -> {
                    task.setGroup(BuildLogicConstants.VERIFICATION_GROUP);
                    task.setDescription("Verifies split packages and acyclic module dependencies.");
                    task.getModuleInventories().from(moduleInventories);
                    task.getReportFile().set(project.getLayout().getBuildDirectory()
                            .file(BuildLogicConstants.REPORT_MODULE_STRUCTURE));
                });
    }

    private static void configureCleanBuildLifecycle(
            Project project,
            TaskProvider<GenerateCleanSourceEvidenceTask> cleanSource,
            TaskProvider<GenerateCleanBuildEvidenceTask> cleanBuild) {
        project.getPluginManager().withPlugin("base", ignored -> {
            TaskProvider<Task> clean = project.getTasks().named(
                    LifecycleBasePlugin.CLEAN_TASK_NAME);
            TaskProvider<Task> build = project.getTasks().named(
                    LifecycleBasePlugin.BUILD_TASK_NAME);
            clean.configure(task -> task.finalizedBy(cleanSource));
            cleanSource.configure(task -> task.mustRunAfter(clean));
            build.configure(task -> {
                task.mustRunAfter(clean, cleanSource);
                task.finalizedBy(cleanBuild);
            });
            cleanBuild.configure(task -> {
                task.mustRunAfter(build);
                task.getCleanTaskExecuted().set(project.provider(() -> {
                    Task cleanTask = clean.get();
                    return project.getGradle().getTaskGraph().hasTask(cleanTask)
                            && cleanTask.getState().getExecuted()
                            && cleanTask.getState().getFailure() == null;
                }));
                task.getBuildTaskSuccessful().set(project.provider(() -> {
                    Task buildTask = build.get();
                    return project.getGradle().getTaskGraph().hasTask(buildTask)
                            && buildTask.getState().getExecuted()
                            && buildTask.getState().getFailure() == null;
                }));
            });
            project.getGradle().getTaskGraph().whenReady(graph -> {
                if (graph.hasTask(build.get())) {
                    project.delete(cleanBuild.get().getOutputFile().get().getAsFile());
                }
            });
        });
    }
}
