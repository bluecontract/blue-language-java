package blue.buildlogic;

import blue.buildlogic.tasks.GenerateAggregateReleaseReceiptTask;
import blue.buildlogic.tasks.GenerateReleaseEvidenceTask;
import blue.buildlogic.tasks.VerifyAggregateReleaseReceiptTask;
import blue.buildlogic.tasks.VerifyInputIdentityTask;
import blue.buildlogic.tasks.VerifyJavaModuleStructureTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;

/** Adds generation and stale-input verification for deterministic release evidence. */
public final class ReleaseEvidencePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        ConfigurableFileTree sourceInputs = project.fileTree(project.getRootDir());
        sourceInputs.include("**/*");
        sourceInputs.exclude(
                "**/.git/**",
                "**/.gradle/**",
                "**/build/**",
                "**/.DS_Store",
                "**/._*",
                "**/*.jfr",
                "**/*.hprof",
                "**/*.heapdump",
                "**/*.db",
                "**/*.sqlite*",
                "**/node_modules/**",
                "**/__pycache__/**",
                "**/*.pyc",
                "**/*.pyo",
                "**/*.zip",
                "**/*.tar",
                "**/*.tar.gz",
                "**/*.tgz");

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
                .file("reports/release-evidence/source-input.json");
        Provider<RegularFile> aggregateReceiptFile = project.getLayout().getBuildDirectory()
                .file(BuildLogicConstants.REPORT_AGGREGATE_RELEASE_RECEIPT);

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
}
