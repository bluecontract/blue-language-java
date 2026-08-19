package blue.buildlogic;

import blue.buildlogic.support.RepositorySourceFiles;
import blue.buildlogic.support.SourceDateEpoch;
import blue.buildlogic.tasks.CompareArchiveReplicasTask;
import blue.buildlogic.tasks.GenerateFragmentedProcessingReportTask;
import blue.buildlogic.tasks.VerifyReleaseEvidenceReportTask;
import blue.buildlogic.tasks.VerifySourceReleaseArchiveTask;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Exec;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;

/** Registers the release reports and executable proofs for semantic compatibility. */
final class SemanticEvidenceOrchestration {

    private static final int JAVA_VERSION = 8;
    private static final String GROUP = BuildLogicConstants.VERIFICATION_GROUP;
    private static final List<String> LEGACY_SEMANTIC_LOCALITY_FILES =
            Collections.unmodifiableList(Arrays.asList(
                    "deep-graph-matrix.json",
                    "fragmented-matrix.json",
                    "root-only-event.json"));

    private SemanticEvidenceOrchestration() {}

    static Tasks register(
            Project project,
            List<String> publishedModules,
            List<String> requiredLocalityTests,
            List<String> requiredHostedRuntimeSuites,
            TaskProvider<Zip> sourceReleaseArchive,
            TaskProvider<CompareArchiveReplicasTask> sourceReleaseComparison,
            TaskProvider<VerifySourceReleaseArchiveTask> sourceReleaseVerification,
            TaskProvider<Task> benchmarkClasses,
            BlueSpecInputs.Registration blueSpecInputs) {
        Provider<RegularFile> aggregateJar = moduleArchive(
                project, "blue-language-java", JavaPlugin.JAR_TASK_NAME);
        Provider<RegularFile> aggregateSourcesJar = moduleArchive(
                project, "blue-language-java", "sourcesJar");
        Provider<RegularFile> aggregateJavadocJar = moduleArchive(
                project, "blue-language-java", "javadocJar");
        Provider<RegularFile> releaseConformance = moduleReport(
                project,
                "blue-conformance",
                "reports/conformance/release-conformance.json");
        Provider<RegularFile> aggregateReplicaReport = moduleReport(
                project,
                "blue-language-java",
                BuildLogicConstants.REPORT_ARCHIVE_REPLICAS);
        Provider<RegularFile> runtimeTrace = project.getLayout()
                .getBuildDirectory().file(
                        "reports/runtime-trace/runtime-work-session.json");
        Provider<RegularFile> cleanBuildEvidence = project.getLayout()
                .getBuildDirectory().file(BuildLogicConstants.REPORT_CLEAN_BUILD_EVIDENCE);
        Provider<RegularFile> semanticApiInventory = project.getLayout()
                .getBuildDirectory().file(BuildLogicConstants.REPORT_SEMANTIC_API_INVENTORY);
        Provider<RegularFile> binaryApiReport = project.getLayout()
                .getBuildDirectory().file(BuildLogicConstants.REPORT_SEMANTIC_API_MIGRATION);
        Provider<RegularFile> semanticVerification = project.getLayout()
                .getBuildDirectory().file(
                        BuildLogicConstants.REPORT_SEMANTIC_BASELINE_VERIFICATION);
        RegularFile apiBaseline = project.getLayout().getProjectDirectory()
                .file("api/blue-language-java-1.0.json");
        RegularFile semanticBaseline = project.getLayout()
                .getProjectDirectory().file("api/semantic-baseline-1.0.json");
        RegularFile migrationLedger = project.getLayout()
                .getProjectDirectory().file(
                        "api/modernization-api-migration-ledger-1.0.json");
        Directory contractsFixtures = blueSpecInputs.root().get()
                .dir("conformance/contracts/fixtures");
        Directory localityEvidence = project.getLayout()
                .getBuildDirectory().dir("reports/semantic-baseline/locality").get();
        RegularFile platformInvocationMatrix = localityEvidence.file(
                "platform-invocation-matrix.json");
        ConfigurableFileCollection legacySemanticLocalityEvidence =
                project.files();
        for (String fileName : LEGACY_SEMANTIC_LOCALITY_FILES) {
            legacySemanticLocalityEvidence.from(
                    localityEvidence.file(fileName));
        }

        TaskProvider<Jar> distributionApiJar = project.getTasks().register(
                BuildLogicConstants.TASK_SEMANTIC_DISTRIBUTION_API_JAR,
                Jar.class,
                task -> {
                    task.setGroup(GROUP);
                    task.setDescription(
                            "Assembles the logical distribution classes for legacy API proof.");
                    task.getArchiveBaseName().set("blue-language-java");
                    task.getArchiveClassifier().set("semantic-api-distribution");
                    task.getArchiveVersion().set(project.provider(
                            () -> project.getVersion().toString()));
                    task.getDestinationDirectory().set(project.getLayout().getBuildDirectory()
                            .dir("semantic-baseline/distribution-api"));
                    task.setPreserveFileTimestamps(false);
                    task.setReproducibleFileOrder(true);
                    task.setIncludeEmptyDirs(false);
                    task.setDuplicatesStrategy(DuplicatesStrategy.FAIL);
                    for (String module : publishedModules) {
                        Provider<RegularFile> archive = moduleArchive(
                                project, module, JavaPlugin.JAR_TASK_NAME);
                        task.dependsOn(":" + module + ":" + JavaPlugin.JAR_TASK_NAME);
                        task.from(project.provider(
                                        () -> project.zipTree(archive.get().getAsFile())),
                                contents -> {
                                    contents.include("**/*.class");
                                    contents.exclude(
                                            "**/module-info.class",
                                            "**/package-info.class",
                                            "META-INF/versions/**");
                                });
                    }
                });

        TaskProvider<Exec> generateApiInventory = project.getTasks().register(
                BuildLogicConstants.TASK_GENERATE_SEMANTIC_API_INVENTORY,
                Exec.class,
                task -> {
                    task.setGroup(GROUP);
                    task.setDescription(
                            "Generates the legacy JSON API inventory for semantic verification.");
                    task.dependsOn(distributionApiJar);
                    task.getInputs().file(distributionApiJar.flatMap(Jar::getArchiveFile));
                    task.getInputs().file(project.file("tools/generate_api_inventory.py"));
                    task.getInputs().file(project.file("tools/check_binary_api.py"));
                    task.getOutputs().file(semanticApiInventory);
                    task.setWorkingDir(project.getRootDir());
                    task.doFirst(ignored -> task.commandLine(
                            "python3",
                            "tools/generate_api_inventory.py",
                            project.relativePath(distributionApiJar.get().getArchiveFile()
                                    .get().getAsFile()),
                            project.relativePath(semanticApiInventory.get().getAsFile())));
                });
        TaskProvider<Exec> verifyApiMigration = project.getTasks().register(
                BuildLogicConstants.TASK_VERIFY_SEMANTIC_API_MIGRATION,
                Exec.class,
                task -> {
                    task.setGroup(GROUP);
                    task.setDescription(
                            "Checks the logical distribution against the approved API ledger.");
                    task.dependsOn(distributionApiJar);
                    task.getInputs().file(distributionApiJar.flatMap(Jar::getArchiveFile));
                    task.getInputs().file(apiBaseline);
                    task.getInputs().file(migrationLedger);
                    task.getInputs().file(project.file("tools/check_binary_api.py"));
                    task.getOutputs().file(binaryApiReport);
                    task.setWorkingDir(project.getRootDir());
                    task.doFirst(ignored -> task.commandLine(
                            "python3",
                            "tools/check_binary_api.py",
                            project.relativePath(apiBaseline.getAsFile()),
                            project.relativePath(distributionApiJar.get().getArchiveFile()
                                    .get().getAsFile()),
                            project.relativePath(binaryApiReport.get().getAsFile()),
                            project.relativePath(migrationLedger.getAsFile())));
                });

        ConfigurableFileTree allTestResults = project.fileTree(
                project.getLayout().getBuildDirectory().dir("test-results/test"));
        allTestResults.include("TEST-*.xml");
        ConfigurableFileTree focusedTestResults = project.fileTree(
                project.getLayout().getBuildDirectory()
                        .dir("test-results/fragmentedProcessingTest"));
        focusedTestResults.include("TEST-*.xml");
        ConfigurableFileTree sourceFiles = RepositorySourceFiles.create(project);
        ConfigurableFileCollection localitySources = project.files(
                "src/test/java/blue/language/provider/ExactNodeGraphFragmentsTest.java",
                "src/test/java/blue/language/processor/DeepGraphPhysicalLocalityIntegrationTest.java",
                "src/test/java/blue/language/processor/FragmentedProcessingLocalityIntegrationTest.java",
                "src/test/java/blue/language/processor/FragmentedProcessingFailureMatrixTest.java");
        Provider<String> sourceCommit = project.getProviders()
                .environmentVariable("GIT_COMMIT")
                .orElse(project.getProviders().exec(spec -> {
                    spec.setWorkingDir(project.getRootDir());
                    spec.commandLine("git", "rev-parse", "--verify", "HEAD^{commit}");
                }).getStandardOutput().getAsText().map(String::trim));
        Provider<String> sourceDateEpoch = project.getProviders()
                .environmentVariable("SOURCE_DATE_EPOCH").orElse("0")
                .map(SourceDateEpoch::normalize);
        Provider<String> gitStatus = project.getProviders().exec(spec -> {
            spec.setWorkingDir(project.getRootDir());
            spec.commandLine("git", "status", "--porcelain", "--untracked-files=all");
        }).getStandardOutput().getAsText().map(String::trim);
        Provider<String> commitAutomationDiff =
                project.getProviders().exec(spec -> {
                    spec.setWorkingDir(project.getRootDir());
                    spec.commandLine("git", "diff", "HEAD", "--", ".cz.toml");
                }).getStandardOutput().getAsText().map(String::trim);
        Provider<String> apiBaselineDiff =
                project.getProviders().exec(spec -> {
                    spec.setWorkingDir(project.getRootDir());
                    spec.commandLine(
                            "git", "diff", "HEAD", "--", "api/blue-language-java-1.0.json");
                }).getStandardOutput().getAsText().map(String::trim);
        JavaToolchainService toolchains = project.getExtensions()
                .getByType(JavaToolchainService.class);
        Provider<String> javaEightRuntime = toolchains.launcherFor(
                spec -> spec.getLanguageVersion().set(JavaLanguageVersion.of(JAVA_VERSION)))
                .map(launcher -> launcher.getMetadata().getJavaRuntimeVersion().toString());

        TaskProvider<GenerateFragmentedProcessingReportTask> fragmentedReport =
                project.getTasks().register(
                        BuildLogicConstants.TASK_FRAGMENTED_PROCESSING_REPORT,
                        GenerateFragmentedProcessingReportTask.class,
                        task -> {
                            task.setGroup(GROUP);
                            task.setDescription(
                                    "Emits machine-readable release, test, API, and locality evidence.");
                            task.getAllTestResults().from(allTestResults);
                            task.getFocusedTestResults().from(focusedTestResults);
                            task.getReleaseConformanceReport().set(releaseConformance);
                            task.getRuntimeTraceReport().set(runtimeTrace);
                            task.getPlatformInvocationMatrixReport().set(
                                    platformInvocationMatrix);
                            task.getCleanBuildEvidenceFile().set(cleanBuildEvidence);
                            task.getJarFile().set(aggregateJar);
                            task.getSourcesJarFile().set(aggregateSourcesJar);
                            task.getJavadocJarFile().set(aggregateJavadocJar);
                            task.getSourceReleaseFile().set(
                                    sourceReleaseArchive.flatMap(Zip::getArchiveFile));
                            task.getApiBaselineFile().set(apiBaseline);
                            task.getBinaryApiReportFile().set(binaryApiReport);
                            task.getJarReplicaReportFile().set(aggregateReplicaReport);
                            task.getSourceReleaseReplicaReportFile().set(
                                    sourceReleaseComparison.flatMap(
                                            CompareArchiveReplicasTask::getReportFile));
                            task.getSourceReleaseVerificationFile().set(
                                    sourceReleaseVerification.flatMap(
                                            VerifySourceReleaseArchiveTask::getReportFile));
                            task.getSourceFiles().from(sourceFiles);
                            task.getLocalitySourceFiles().from(localitySources);
                            task.getSourceRoot().set(project.getLayout().getProjectDirectory());
                            task.getSourceCommit().set(sourceCommit);
                            task.getSourceDateEpoch().set(sourceDateEpoch);
                            task.getGitStatus().set(gitStatus);
                            task.getCommitAutomationDiff().set(commitAutomationDiff);
                            task.getApiBaselineDiff().set(apiBaselineDiff);
                            task.getGradleVersion().set(project.getGradle().getGradleVersion());
                            task.getTestJavaRuntimeVersion().set(javaEightRuntime);
                            task.getRequiredLocalityTests().set(requiredLocalityTests);
                            task.getRequiredHostedRuntimeSuites().set(requiredHostedRuntimeSuites);
                            task.getBenchmarkCompilationSuccessful().set(true);
                            task.getReportFile().set(project.getLayout().getBuildDirectory()
                                    .file(BuildLogicConstants.REPORT_FRAGMENTED_PROCESSING));
                            task.getMarkdownFile().set(project.getLayout().getBuildDirectory()
                                    .file(BuildLogicConstants.REPORT_FRAGMENTED_PROCESSING_MARKDOWN));
                            task.dependsOn(
                                    project.getTasks().named(JavaPlugin.TEST_TASK_NAME),
                                    project.getTasks().named("fragmentedProcessingTest"),
                                    project.getTasks().named("releaseConformanceTest"),
                                    project.getTasks().named("runtimeTraceEvidence"),
                                    project.getTasks().named("verifyDeterministicJar"),
                                    project.getTasks().named("verifyDeterministicSourceArchives"),
                                    verifyApiMigration,
                                    benchmarkClasses,
                                    ":blue-language-java:jar",
                                    ":blue-language-java:sourcesJar",
                                    ":blue-language-java:javadocJar");
                        });
        TaskProvider<VerifyReleaseEvidenceReportTask> releaseEvidenceVerification =
                project.getTasks().register(
                        BuildLogicConstants.TASK_VERIFY_RELEASE_EVIDENCE_REPORT,
                        VerifyReleaseEvidenceReportTask.class,
                        task -> {
                            task.setGroup(GROUP);
                            task.setDescription(
                                    "Validates the release evidence schema and mandatory gates.");
                            task.getEvidenceFile().set(fragmentedReport.flatMap(
                                    GenerateFragmentedProcessingReportTask::getReportFile));
                            task.getMarkdownFile().set(fragmentedReport.flatMap(
                                    GenerateFragmentedProcessingReportTask::getMarkdownFile));
                            task.getJarFile().set(aggregateJar);
                            task.getSourcesJarFile().set(aggregateSourcesJar);
                            task.getJavadocJarFile().set(aggregateJavadocJar);
                            task.getSourceReleaseFile().set(
                                    sourceReleaseArchive.flatMap(Zip::getArchiveFile));
                            task.getMinimumTestCount().set(2078);
                            task.getSourceDateEpoch().set(sourceDateEpoch);
                            task.getVerificationReportFile().set(project.getLayout()
                                    .getBuildDirectory().file(
                                            BuildLogicConstants
                                                    .REPORT_RELEASE_EVIDENCE_VERIFICATION));
                            task.dependsOn(fragmentedReport);
                        });

        TaskProvider<Sync> semanticWorkspace = registerSemanticVerificationWorkspace(
                project, publishedModules, blueSpecInputs);
        SourceSet test = project.getExtensions().getByType(SourceSetContainer.class)
                .getByName(SourceSet.TEST_SOURCE_SET_NAME);
        TaskProvider<JavaExec> semanticBaselineVerification = project.getTasks().register(
                BuildLogicConstants.TASK_SEMANTIC_BASELINE_VERIFY,
                JavaExec.class,
                task -> {
                    task.setGroup(GROUP);
                    task.setDescription(
                            "Verifies exact semantics and the approved distribution API migration.");
                    task.dependsOn(
                            fragmentedReport,
                            semanticWorkspace,
                            generateApiInventory,
                            verifyApiMigration,
                            blueSpecInputs.verification(),
                            project.getTasks().named(JavaPlugin.TEST_CLASSES_TASK_NAME));
                    task.setClasspath(test.getRuntimeClasspath());
                    task.getMainClass().set(
                            "blue.language.conformance.SemanticBaselineVerifierCli");
                    File verificationWorkspace =
                            semanticWorkspace.get().getDestinationDir();
                    task.setWorkingDir(verificationWorkspace);
                    task.args(
                            relativeArgument(verificationWorkspace, semanticBaseline.getAsFile()),
                            relativeArgument(verificationWorkspace, releaseConformance.get()
                                    .getAsFile()),
                            relativeArgument(verificationWorkspace, fragmentedReport.get()
                                    .getReportFile().get().getAsFile()),
                            relativeArgument(verificationWorkspace, semanticApiInventory.get()
                                    .getAsFile()),
                            relativeArgument(verificationWorkspace, contractsFixtures.getAsFile()),
                            relativeArgument(verificationWorkspace, semanticVerification.get()
                                    .getAsFile()),
                            relativeArgument(verificationWorkspace, migrationLedger.getAsFile()),
                            relativeArgument(verificationWorkspace, apiBaseline.getAsFile()),
                            relativeArgument(verificationWorkspace, binaryApiReport.get()
                                    .getAsFile()));
                    for (String fileName : LEGACY_SEMANTIC_LOCALITY_FILES) {
                        task.args("build/reports/semantic-baseline/locality/"
                                + fileName);
                    }
                    task.getInputs().file(semanticBaseline);
                    task.getInputs().file(releaseConformance);
                    task.getInputs().file(fragmentedReport.flatMap(
                            GenerateFragmentedProcessingReportTask::getReportFile));
                    task.getInputs().file(semanticApiInventory);
                    task.getInputs().file(migrationLedger);
                    task.getInputs().file(apiBaseline);
                    task.getInputs().file(binaryApiReport);
                    task.getInputs().dir(contractsFixtures);
                    task.getInputs().files(legacySemanticLocalityEvidence);
                    task.getInputs().files(semanticWorkspace);
                    task.getOutputs().file(semanticVerification);
                });
        project.getTasks().register(
                BuildLogicConstants.TASK_SEMANTIC_BASELINE_CAPTURE,
                JavaExec.class,
                task -> {
                    task.setGroup(GROUP);
                    task.setDescription(
                            "Deliberately replaces the tracked semantic characterization baseline.");
                    task.dependsOn(
                            fragmentedReport,
                            generateApiInventory,
                            blueSpecInputs.verification(),
                            project.getTasks().named(JavaPlugin.TEST_CLASSES_TASK_NAME));
                    task.setClasspath(test.getRuntimeClasspath());
                    task.getMainClass().set(
                            "blue.language.conformance.SemanticBaselineCaptureCli");
                    task.setWorkingDir(project.getRootDir());
                    task.args(
                            project.relativePath(releaseConformance.get().getAsFile()),
                            project.relativePath(fragmentedReport.get().getReportFile()
                                    .get().getAsFile()),
                            project.relativePath(semanticApiInventory.get().getAsFile()),
                            project.relativePath(contractsFixtures.getAsFile()),
                            project.relativePath(semanticBaseline.getAsFile()));
                    for (String fileName : LEGACY_SEMANTIC_LOCALITY_FILES) {
                        task.args(project.relativePath(
                                localityEvidence.file(fileName).getAsFile()));
                    }
                    task.getInputs().file(releaseConformance);
                    task.getInputs().file(fragmentedReport.flatMap(
                            GenerateFragmentedProcessingReportTask::getReportFile));
                    task.getInputs().file(semanticApiInventory);
                    task.getInputs().dir(contractsFixtures);
                    task.getInputs().files(legacySemanticLocalityEvidence);
                    task.getOutputs().file(semanticBaseline);
                });
        return new Tasks(
                fragmentedReport,
                releaseEvidenceVerification,
                semanticBaselineVerification,
                platformInvocationMatrix);
    }

    private static TaskProvider<Sync> registerSemanticVerificationWorkspace(
            Project project,
            List<String> publishedModules,
            BlueSpecInputs.Registration blueSpecInputs) {
        return project.getTasks().register(
                BuildLogicConstants.TASK_PREPARE_SEMANTIC_VERIFICATION_WORKSPACE,
                Sync.class,
                task -> {
                    task.setGroup(GROUP);
                    task.setDescription(
                            "Stages module sources at the legacy semantic verifier's logical paths.");
                    task.dependsOn(
                            project.getTasks().named("fragmentedProcessingTest"),
                            blueSpecInputs.verification());
                    task.into(project.getLayout().getBuildDirectory()
                            .dir("semantic-baseline/verification-workspace"));
                    task.setIncludeEmptyDirs(false);
                    task.setDuplicatesStrategy(DuplicatesStrategy.FAIL);
                    for (String module : publishedModules) {
                        task.from(project.project(":" + module).file("src/main/java"),
                                contents -> contents.into("src/main/java"));
                    }
                    task.from(blueSpecInputs.file(
                                    "specifications/blue-language-specification-1.0.md"),
                            contents -> contents.into(
                                    "src/main/resources/specifications"));
                    task.from(blueSpecInputs.file(
                                    "specifications/blue-contracts-and-processor-specification-1.0.md"),
                            contents -> contents.into(
                                    "src/main/resources/specifications"));
                    task.from(blueSpecInputs.file(
                                    "specifications/blue-language-specification-1.0.md"),
                            contents -> {
                                contents.rename(ignored -> "spec.md");
                                contents.into("src/test/resources/language/1.0");
                            });
                    task.from(blueSpecInputs.file(
                                    "specifications/blue-contracts-and-processor-specification-1.0.md"),
                            contents -> {
                                contents.rename(ignored -> "spec.md");
                                contents.into("src/test/resources/contract/1.0");
                            });
                    task.from(project.file("docs"), contents -> contents.into("docs"));
                    task.from(project.file("README.md"));
                    task.from(project.getLayout().getBuildDirectory().dir(
                                    "reports/semantic-baseline/locality"),
                            contents -> {
                                contents.include(LEGACY_SEMANTIC_LOCALITY_FILES);
                                contents.into(
                                        "build/reports/semantic-baseline/locality");
                            });
                });
    }

    /** Exact legacy payload set retained by the frozen semantic baseline. */
    static List<String> legacySemanticLocalityEvidenceFiles() {
        return LEGACY_SEMANTIC_LOCALITY_FILES;
    }

    private static Provider<RegularFile> moduleArchive(
            Project root, String moduleName, String taskName) {
        return root.getLayout().file(root.provider(() -> ((Jar) root
                .project(":" + moduleName)
                .getTasks()
                .getByName(taskName))
                .getArchiveFile()
                .get()
                .getAsFile()));
    }

    private static String relativeArgument(File workingDirectory, File target) {
        return workingDirectory.toPath().toAbsolutePath().normalize()
                .relativize(target.toPath().toAbsolutePath().normalize())
                .toString().replace(File.separatorChar, '/');
    }

    private static Provider<RegularFile> moduleReport(
            Project root, String moduleName, String relativePath) {
        return root.getLayout().file(root.provider(() -> root
                .project(":" + moduleName)
                .getLayout()
                .getBuildDirectory()
                .file(relativePath)
                .get()
                .getAsFile()));
    }

    /** Typed providers consumed by the root release graph and aggregate receipt. */
    static final class Tasks {

        final TaskProvider<GenerateFragmentedProcessingReportTask> fragmentedReport;
        final TaskProvider<VerifyReleaseEvidenceReportTask> releaseEvidenceVerification;
        final TaskProvider<JavaExec> semanticBaselineVerification;
        final RegularFile platformInvocationMatrix;

        private Tasks(
                TaskProvider<GenerateFragmentedProcessingReportTask> fragmentedReport,
                TaskProvider<VerifyReleaseEvidenceReportTask> releaseEvidenceVerification,
                TaskProvider<JavaExec> semanticBaselineVerification,
                RegularFile platformInvocationMatrix) {
            this.fragmentedReport = fragmentedReport;
            this.releaseEvidenceVerification = releaseEvidenceVerification;
            this.semanticBaselineVerification = semanticBaselineVerification;
            this.platformInvocationMatrix = platformInvocationMatrix;
        }
    }
}
