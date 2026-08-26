package blue.buildlogic;

import blue.buildlogic.tasks.AssembleImmutableStagedRepositoryTask;
import blue.buildlogic.tasks.CompareArchiveReplicasTask;
import blue.buildlogic.tasks.GenerateAggregateReleaseReceiptTask;
import blue.buildlogic.tasks.GenerateChecksumFileTask;
import blue.buildlogic.tasks.GenerateJavaApiInventoryTask;
import blue.buildlogic.tasks.GenerateJavaModuleInventoryTask;
import blue.buildlogic.tasks.GenerateSourceReleaseMetadataTask;
import blue.buildlogic.tasks.VerifyAggregateReleaseReceiptTask;
import blue.buildlogic.tasks.VerifyBuildScriptShapeTask;
import blue.buildlogic.tasks.VerifyJavaModuleStructureTask;
import blue.buildlogic.tasks.VerifyPublishedRepositoryTask;
import blue.buildlogic.tasks.VerifySourceReleaseArchiveTask;
import blue.buildlogic.support.RepositorySourceFiles;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.Delete;
import org.gradle.api.tasks.GradleBuild;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

/** Configures the root as a verification-only orchestrator over the published modules. */
public final class RootOrchestrationPlugin implements Plugin<Project> {

    private static final int JAVA_VERSION = 8;
    private static final int EXECUTABLE_FILE_MODE = 0755;
    private static final int REGULAR_FILE_MODE = 0644;
    private static final String COMPATIBILITY_SOURCE_DIRECTORY =
            "src/compat/java";
    private static final String GROUP = BuildLogicConstants.VERIFICATION_GROUP;
    private static final String DISTRIBUTION_GROUP = "distribution";
    private static final String AGGREGATE_MODULE = "blue-language-java";
    private static final String SOURCE_RELEASE_BASE_NAME = AGGREGATE_MODULE;
    private static final String SOURCE_RELEASE_CLASSIFIER = "source-release";
    private static final String SOURCE_RELEASE_METADATA_FILE = ".cz.toml";
    private static final List<String> PUBLISHED_MODULES = Collections.unmodifiableList(Arrays.asList(
            "blue-language-model",
            "blue-language-core",
            "blue-language-mapping",
            "blue-language-ipfs",
            "blue-contracts-core",
            "blue-conformance",
            AGGREGATE_MODULE));
    private static final List<String> API_BASELINE_MODULES = Collections.unmodifiableList(Arrays.asList(
            "blue-language-model",
            "blue-language-core",
            "blue-language-mapping",
            "blue-language-ipfs",
            "blue-contracts-core",
            AGGREGATE_MODULE));
    private static final List<String> COMPATIBILITY_RUNTIME_MODULES =
            Collections.unmodifiableList(Arrays.asList(
                    "blue-language-model",
                    "blue-language-core",
                    "blue-language-mapping",
                    "blue-language-ipfs",
                    "blue-contracts-core"));
    private static final List<String> REQUIRED_LOCALITY_TESTS =
            Collections.unmodifiableList(Arrays.asList(
                    "blue.language.processor.FragmentedProcessingLocalityIntegrationTest#"
                            + "shouldVerifyExactRootAndEventFragmentsHaveIdenticalSemanticsAcrossMatrix",
                    "blue.language.processor.DeepGraphPhysicalLocalityIntegrationTest#"
                            + "shouldVerifyDeepGraphHasSemanticParityAndPhysicalLocalityAcrossRepresentationsAndProviders",
                    "blue.language.processor.DeepGraphPhysicalLocalityIntegrationTest#"
                            + "shouldVerifyPublicPlatformCommitMatrixPreservesSemanticsAndStrictLocality",
                    "blue.language.provider.ExactNodeGraphFragmentsTest#"
                            + "shouldSplitOnlySelectedCutsAndTheirAncestorSpine",
                    "blue.language.processor.FragmentedProcessingFailureMatrixTest#"
                            + "shouldVerifySelectedBodyUnavailableSuspendsWithoutPortableGasAndRetryMatches"));
    private static final List<String> REQUIRED_HOSTED_RUNTIME_SUITES =
            Collections.unmodifiableList(Arrays.asList(
                    "RuntimeWorkSessionTest",
                    "RuntimeWorkSessionProcessorPhaseIntegrationTest",
                    "SemanticOutputBoundaryTest",
                    "DocumentProcessorHandlerFailureTest",
                    "ExternalChannelDependencyContextTest",
                    "SubtypeAssignablePredicateTest",
                    "ContractContributionResolverTest",
                    "SelectedExecutableBodyCapabilityTest",
                    "ExternalChannelHostedOutputAdmissionTest"));
    private static final List<String> ALLOWED_MODULE_EDGES = Collections.unmodifiableList(Arrays.asList(
            "blue-language-core->blue-language-model",
            "blue-language-mapping->blue-language-model",
            "blue-language-mapping->blue-language-core",
            "blue-language-ipfs->blue-language-core",
            "blue-contracts-core->blue-language-model",
            "blue-contracts-core->blue-language-core",
            "blue-contracts-core->blue-language-mapping",
            "blue-conformance->blue-language-model",
            "blue-conformance->blue-language-core",
            "blue-conformance->blue-language-mapping",
            "blue-conformance->blue-contracts-core",
            "blue-language-java->blue-language-model",
            "blue-language-java->blue-language-core",
            "blue-language-java->blue-language-mapping",
            "blue-language-java->blue-language-ipfs",
            "blue-language-java->blue-contracts-core"));

    @Override
    public void apply(Project project) {
        requireRoot(project);
        project.getPluginManager().apply(JavaPlugin.class);
        project.getPluginManager().apply(JmhConventionsPlugin.class);
        project.getPluginManager().apply(ReleaseEvidencePlugin.class);
        configureRootJava(project);
        configureDependencies(project);
        SourceReleaseTasks sourceRelease = registerSourceReleaseTasks(project);

        TaskProvider<Task> moduleCheck = lifecycle(project, "moduleCheck",
                "Runs checks for every module and the root compatibility tests.");
        TaskProvider<Task> moduleArchiveVerify = lifecycle(project, "moduleArchiveVerify",
                "Verifies deterministic archives and independent replicas for every publication.");
        TaskProvider<Task> moduleApiVerify = lifecycle(project, "moduleApiVerify",
                "Generates module API inventories and checks tracked module baselines.");
        TaskProvider<Task> stagePublications = lifecycle(project, "stagePublications",
                "Stages all seven Maven publications in the root repository.");
        TaskProvider<Task> benchmarkClasses = lifecycle(project, "benchmarkClasses",
                "Compiles root and module-specific JMH entry points without running benchmarks.");

        TaskProvider<VerifyJavaModuleStructureTask> moduleStructure = project.getTasks().named(
                BuildLogicConstants.TASK_VERIFY_MODULE_STRUCTURE,
                VerifyJavaModuleStructureTask.class,
                task -> {
                    task.setGroup(GROUP);
                    task.setDescription(
                            "Rejects split packages, module cycles, and undeclared module edges.");
                    task.getModuleInventories().setFrom(Collections.emptyList());
                    task.getAllowedEdges().set(ALLOWED_MODULE_EDGES);
                    task.getEnforceAllowedEdges().set(true);
                    task.getReportFile().set(project.getLayout().getBuildDirectory()
                            .file(BuildLogicConstants.REPORT_MODULE_STRUCTURE));
                });
        TaskProvider<GenerateJavaApiInventoryTask> apiUnion = project.getTasks().register(
                BuildLogicConstants.TASK_GENERATE_PUBLIC_API_UNION,
                GenerateJavaApiInventoryTask.class,
                task -> {
                    task.setGroup(GROUP);
                    task.setDescription("Unions the public APIs of all published modules.");
                    task.getModuleName().set("blue-language-java-distribution");
                    task.getOutputFile().set(project.getLayout().getBuildDirectory()
                            .file(BuildLogicConstants.REPORT_API_UNION));
                });
        moduleApiVerify.configure(task -> task.dependsOn(apiUnion));

        TaskProvider<VerifyBuildScriptShapeTask> scriptShape = project.getTasks().register(
                BuildLogicConstants.TASK_VERIFY_BUILD_SCRIPT_SHAPE,
                VerifyBuildScriptShapeTask.class,
                task -> {
                    task.setGroup(GROUP);
                    task.setDescription("Enforces compact declarative Gradle build scripts.");
                    task.getRepositoryRoot().set(project.getLayout().getProjectDirectory());
                    task.getBuildScripts().from(project.fileTree(project.getRootDir(), tree -> {
                        tree.include("**/build.gradle", "**/build.gradle.kts");
                        tree.exclude("**/build/**");
                    }));
                    task.getReportFile().set(project.getLayout().getBuildDirectory()
                            .file(BuildLogicConstants.REPORT_BUILD_SCRIPT_SHAPE));
                });

        TaskProvider<Delete> prepareStaging = project.getTasks().register(
                "prepareStagingRepository", Delete.class, task -> {
                    task.setGroup("build");
                    task.setDescription("Clears the invocation-owned staged Maven repository.");
                    task.delete(project.getLayout().getBuildDirectory().dir("staging-deploy"));
                });
        Provider<String> sourceCommit = project.getProviders()
                .environmentVariable("GIT_COMMIT")
                .orElse(project.getProviders().exec(spec -> {
                    spec.setWorkingDir(project.getRootDir());
                    spec.commandLine("git", "rev-parse", "--verify", "HEAD^{commit}");
                }).getStandardOutput().getAsText().map(String::trim));
        Provider<Directory> immutableRepository = project.getLayout().dir(
                project.getProviders().gradleProperty("stagedDependencyRepository")
                        .map(path -> project.file(path)))
                .orElse(project.getLayout().getBuildDirectory()
                        .dir("staged-dependency-repository"));
        TaskProvider<AssembleImmutableStagedRepositoryTask> assembleRepository =
                project.getTasks().register(
                        BuildLogicConstants.TASK_ASSEMBLE_IMMUTABLE_STAGED_REPOSITORY,
                        AssembleImmutableStagedRepositoryTask.class,
                        task -> {
                            task.setGroup(GROUP);
                            task.setDescription(
                                    "Exports exact publications into a non-overwriting repository.");
                            task.dependsOn(stagePublications);
                            task.getSourceRepository().set(project.getLayout()
                                    .getBuildDirectory().dir("staging-deploy"));
                            task.getOutputRepository().set(immutableRepository);
                            task.getVersionValue().set(project.provider(
                                    () -> project.getVersion().toString()));
                            task.getExpectedArtifacts().set(PUBLISHED_MODULES);
                            task.getSourceCommit().set(sourceCommit);
                            task.getContractsSpecification().set(project.getLayout()
                                    .getProjectDirectory().file(
                                            "blue-contracts-core/src/main/resources/"
                                                    + "specifications/blue-contracts-and-"
                                                    + "processor-specification-1.0.md"));
                            task.getContractsReleaseManifest().set(project.getLayout()
                                    .getProjectDirectory().file(
                                            "blue-conformance/src/main/resources/"
                                                    + "blue-contracts-closure-1.0/"
                                                    + "release-manifest.yaml"));
                        });
        TaskProvider<VerifyPublishedRepositoryTask> publishedRepository =
                project.getTasks().register(
                        BuildLogicConstants.TASK_VERIFY_PUBLISHED_REPOSITORY,
                        VerifyPublishedRepositoryTask.class,
                        task -> {
                            task.setGroup(GROUP);
                            task.setDescription(
                                    "Verifies all staged coordinates, POMs, and Java 8 bytecode.");
                            task.dependsOn(assembleRepository);
                            task.getRepositoryDirectory().set(immutableRepository);
                            task.getVersionValue().set(project.provider(
                                    () -> project.getVersion().toString()));
                            task.getExpectedArtifacts().set(PUBLISHED_MODULES);
                            task.getAllowedModuleEdges().set(ALLOWED_MODULE_EDGES);
                            task.getSourceCommit().set(sourceCommit);
                            task.getContractsSpecification().set(
                                    assembleRepository.flatMap(
                                            AssembleImmutableStagedRepositoryTask::
                                                    getContractsSpecification));
                            task.getContractsReleaseManifest().set(
                                    assembleRepository.flatMap(
                                            AssembleImmutableStagedRepositoryTask::
                                                    getContractsReleaseManifest));
                            task.getReportFile().set(project.getLayout().getBuildDirectory()
                                    .file(BuildLogicConstants.REPORT_PUBLISHED_REPOSITORY));
                        });
        TaskProvider<GradleBuild> publishedSmoke = project.getTasks().register(
                "publishedArtifactSmoke", GradleBuild.class, task -> {
                    task.setGroup(GROUP);
                    task.setDescription(
                            "Resolves and executes an independent staged-coordinate consumer.");
                    task.dependsOn(publishedRepository);
                    task.setDir(project.file("smoke-tests/published"));
                    task.setTasks(Collections.singletonList("cleanPublishedSmoke"));
                    task.getStartParameter().setRefreshDependencies(true);
                    task.getStartParameter().setProjectProperties(new TreeMapBuilder()
                            .put("stagingRepository", immutableRepository.get()
                                    .getAsFile().getAbsolutePath())
                            .put("blueVersion", project.provider(
                                    () -> project.getVersion().toString()).get())
                            .put("smokeReport", project.getLayout().getBuildDirectory()
                                    .file("reports/published-smoke/verification.json")
                                    .get().getAsFile().getAbsolutePath())
                            .build());
                    task.getInputs().dir(immutableRepository);
                    task.getInputs().property("blueVersion", project.provider(
                            () -> project.getVersion().toString()));
                    task.getOutputs().file(project.getLayout().getBuildDirectory()
                            .file("reports/published-smoke/verification.json"));
                });
        TaskProvider<GenerateAggregateReleaseReceiptTask> generateReceipt =
                project.getTasks().named(
                        BuildLogicConstants.TASK_GENERATE_AGGREGATE_RELEASE_RECEIPT,
                        GenerateAggregateReleaseReceiptTask.class);
        TaskProvider<VerifyAggregateReleaseReceiptTask> verifyReceipt =
                project.getTasks().named(
                        BuildLogicConstants.TASK_VERIFY_AGGREGATE_RELEASE_RECEIPT,
                        VerifyAggregateReleaseReceiptTask.class);

        registerFocusedTests(project);
        registerEvidenceExecutions(project);
        registerCompatibilityAliases(
                project, moduleApiVerify, moduleArchiveVerify, sourceRelease);
        SemanticEvidenceOrchestration.Tasks semanticEvidence =
                SemanticEvidenceOrchestration.register(
                        project,
                        PUBLISHED_MODULES,
                        REQUIRED_LOCALITY_TESTS,
                        REQUIRED_HOSTED_RUNTIME_SUITES,
                        sourceRelease.primary,
                        sourceRelease.comparison,
                        sourceRelease.verification,
                        benchmarkClasses);
        DocumentationQualityOrchestration.Tasks documentation =
                DocumentationQualityOrchestration.register(
                        project,
                        PUBLISHED_MODULES,
                        apiUnion,
                        moduleStructure);

        project.getGradle().projectsEvaluated(gradle -> configureModuleGraph(
                project,
                moduleCheck,
                moduleArchiveVerify,
                moduleApiVerify,
                stagePublications,
                prepareStaging,
                benchmarkClasses,
                moduleStructure,
                apiUnion,
                generateReceipt,
                verifyReceipt,
                scriptShape,
                publishedRepository,
                publishedSmoke,
                immutableRepository,
                sourceRelease,
                semanticEvidence));

        TaskProvider<Task> releaseVerify = lifecycle(project, "releaseVerify",
                "Runs all modular release-candidate gates and emits aggregate evidence.");
        releaseVerify.configure(task -> task.dependsOn(
                scriptShape,
                moduleCheck,
                moduleArchiveVerify,
                moduleApiVerify,
                moduleStructure,
                benchmarkClasses,
                publishedSmoke,
                project.getTasks().named("releaseConformanceTest"),
                project.getTasks().named("runtimeTraceEvidence"),
                project.getTasks().named("fragmentedProcessingTest"),
                project.getTasks().named(
                        BuildLogicConstants.TASK_VERIFY_CLEAN_BUILD_EVIDENCE),
                sourceRelease.checksum,
                sourceRelease.comparison,
                sourceRelease.verification,
                semanticEvidence.releaseEvidenceVerification,
                semanticEvidence.semanticBaselineVerification,
                verifyReceipt));
        FinalQualityOrchestration.register(
                project,
                PUBLISHED_MODULES,
                releaseVerify,
                benchmarkClasses,
                apiUnion,
                moduleStructure,
                documentation);
        lifecycle(project, "rcVerify", "Alias for releaseVerify.")
                .configure(task -> task.dependsOn(releaseVerify));
    }

    private static void configureRootJava(Project project) {
        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.setSourceCompatibility(org.gradle.api.JavaVersion.VERSION_1_8);
        java.setTargetCompatibility(org.gradle.api.JavaVersion.VERSION_1_8);
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME).getJava().setSrcDirs(Collections.emptyList());
        sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME).getResources()
                .setSrcDirs(Collections.emptyList());
        configureCompatibilitySources(project, sourceSets);
        project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class)
                .configure(task -> task.setEnabled(false));
        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            task.getOptions().setEncoding("UTF-8");
            task.getOptions().getRelease().set(JAVA_VERSION);
        });
        JavaToolchainService toolchains =
                project.getExtensions().getByType(JavaToolchainService.class);
        org.gradle.api.provider.Provider<JavaLauncher> javaEight = toolchains.launcherFor(
                spec -> spec.getLanguageVersion().set(JavaLanguageVersion.of(JAVA_VERSION)));
        project.getTasks().withType(Test.class).configureEach(task -> {
            task.getJavaLauncher().set(javaEight);
            task.useJUnitPlatform();
            task.systemProperty("junit.jupiter.execution.parallel.enabled", "false");
            task.getReports().getJunitXml().getRequired().set(true);
            task.getReports().getHtml().getRequired().set(true);
        });
        project.getTasks().withType(JavaExec.class).configureEach(task ->
                task.getJavaLauncher().set(javaEight));
    }

    /**
     * Compiles the legacy facade only with root characterization tests and
     * benchmarks. Published module sources continue to expose the thin facade.
     */
    static void configureCompatibilitySources(
            Project project,
            SourceSetContainer sourceSets) {
        Object compatibilitySources = project.file(
                COMPATIBILITY_SOURCE_DIRECTORY);
        sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME)
                .getJava().srcDir(compatibilitySources);
        sourceSets.getByName("jmh")
                .getJava().srcDir(compatibilitySources);
    }

    private static void configureDependencies(Project project) {
        if (System.getenv("CI") == null
                && Boolean.parseBoolean(String.valueOf(
                        project.findProperty("blue.allowMavenLocal")))) {
            project.getRepositories().mavenLocal();
        }
        project.getRepositories().mavenCentral();
        DependencyHandler dependencies = project.getDependencies();
        configureCompatibilityDependencies(project, dependencies);
        dependencies.add(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME,
                project.project(":blue-conformance"));
        dependencies.add(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME,
                dependencies.platform("org.junit:junit-bom:5.10.2"));
        dependencies.add(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME,
                "org.junit.jupiter:junit-jupiter");
        dependencies.add(JavaPlugin.TEST_RUNTIME_ONLY_CONFIGURATION_NAME,
                "org.junit.platform:junit-platform-launcher");
        dependencies.add(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME,
                "org.mockito:mockito-core:3.12.4");
        dependencies.add(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME,
                "com.fasterxml.jackson.core:jackson-databind:2.15.2");
        dependencies.add(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME,
                "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2");
        dependencies.add(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME,
                "org.apache.httpcomponents:httpclient:4.5.14");
        dependencies.add(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME,
                "org.reflections:reflections:0.10.2");
        dependencies.add(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME,
                "io.github.erdtman:java-json-canonicalization:1.1");
    }

    /** Separates aggregate test coverage from the compatibility JMH runtime. */
    static void configureCompatibilityDependencies(
            Project project,
            DependencyHandler dependencies) {
        dependencies.add(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME,
                project.project(":" + AGGREGATE_MODULE));
        // Compatibility benchmarks compile their own Blue facade, so their
        // shaded runtime uses its implementation modules without the thin one.
        for (String module : COMPATIBILITY_RUNTIME_MODULES) {
            dependencies.add("jmhImplementation",
                    project.project(":" + module));
        }
        project.getConfigurations().named("jmhRuntimeClasspath")
                .configure(configuration -> configuration.exclude(
                        Collections.singletonMap(
                                "module", AGGREGATE_MODULE)));
    }

    private static SourceReleaseTasks registerSourceReleaseTasks(Project project) {
        ConfigurableFileTree sourceFiles = RepositorySourceFiles.createForSourceRelease(project);
        org.gradle.api.provider.Provider<String> releaseVersion = project.provider(
                () -> project.getVersion().toString());
        org.gradle.api.provider.Provider<String> rootPrefix = releaseVersion.map(
                version -> SOURCE_RELEASE_BASE_NAME + "-" + version);
        org.gradle.api.provider.Provider<String> archiveName = releaseVersion.map(
                version -> SOURCE_RELEASE_BASE_NAME + "-" + version + "-"
                        + SOURCE_RELEASE_CLASSIFIER + ".zip");

        TaskProvider<GenerateSourceReleaseMetadataTask> metadata = project.getTasks().register(
                BuildLogicConstants.TASK_GENERATE_SOURCE_RELEASE_METADATA,
                GenerateSourceReleaseMetadataTask.class,
                task -> {
                    task.setGroup(DISTRIBUTION_GROUP);
                    task.setDescription(
                            "Creates release metadata without modifying the tracked .cz.toml.");
                    task.getSourceFile().set(project.getLayout().getProjectDirectory()
                            .file(SOURCE_RELEASE_METADATA_FILE));
                    task.getReleaseVersion().set(releaseVersion);
                    task.getOutputFile().set(project.getLayout().getBuildDirectory()
                            .file(BuildLogicConstants.DIRECTORY_SOURCE_RELEASE_METADATA
                                    + "/" + SOURCE_RELEASE_METADATA_FILE));
                });

        TaskProvider<Zip> primary = registerSourceReleaseArchive(
                project,
                BuildLogicConstants.TASK_SOURCE_RELEASE_ARCHIVE,
                "Creates the complete deterministic source-release ZIP.",
                BuildLogicConstants.DIRECTORY_SOURCE_RELEASE,
                sourceFiles,
                metadata,
                releaseVersion,
                rootPrefix);
        TaskProvider<Zip> replica = registerSourceReleaseArchive(
                project,
                BuildLogicConstants.TASK_SOURCE_RELEASE_ARCHIVE_REPLICA,
                "Independently creates the source-release ZIP repeatability replica.",
                BuildLogicConstants.DIRECTORY_SOURCE_RELEASE_REPLICA,
                sourceFiles,
                metadata,
                releaseVersion,
                rootPrefix);

        TaskProvider<GenerateChecksumFileTask> checksum = project.getTasks().register(
                BuildLogicConstants.TASK_GENERATE_SOURCE_RELEASE_CHECKSUM,
                GenerateChecksumFileTask.class,
                task -> {
                    task.setGroup(DISTRIBUTION_GROUP);
                    task.setDescription("Writes the source-release ZIP SHA-256 sidecar.");
                    task.getInputFile().set(primary.flatMap(Zip::getArchiveFile));
                    task.getOutputFile().set(project.getLayout().getBuildDirectory().file(
                            archiveName.map(name -> BuildLogicConstants.DIRECTORY_SOURCE_RELEASE
                                    + "/" + name + ".sha256")));
                });
        primary.configure(task -> task.finalizedBy(checksum));

        TaskProvider<CompareArchiveReplicasTask> comparison = project.getTasks().register(
                BuildLogicConstants.TASK_COMPARE_SOURCE_RELEASE_REPLICA,
                CompareArchiveReplicasTask.class,
                task -> {
                    task.setGroup(GROUP);
                    task.setDescription(
                            "Requires independently assembled source-release ZIPs to match.");
                    task.getReferenceArchives().from(primary.flatMap(Zip::getArchiveFile));
                    task.getReplicaArchives().from(replica.flatMap(Zip::getArchiveFile));
                    task.getReportFile().set(project.getLayout().getBuildDirectory()
                            .file(BuildLogicConstants.REPORT_SOURCE_RELEASE_REPLICA));
                    task.dependsOn(primary, replica);
                });
        TaskProvider<VerifySourceReleaseArchiveTask> verification =
                project.getTasks().register(
                        BuildLogicConstants.TASK_VERIFY_SOURCE_RELEASE_ARCHIVE,
                        VerifySourceReleaseArchiveTask.class,
                        task -> {
                            task.setGroup(GROUP);
                            task.setDescription(
                                    "Checks the source-release ZIP for exact inputs and no debris.");
                            task.getArchiveFile().set(primary.flatMap(Zip::getArchiveFile));
                            task.getSourceFiles().from(sourceFiles);
                            task.getSourceRoot().set(project.getLayout().getProjectDirectory());
                            task.getRootPrefix().set(rootPrefix);
                            task.getGeneratedMetadataEntry().set(SOURCE_RELEASE_METADATA_FILE);
                            task.getReportFile().set(project.getLayout().getBuildDirectory()
                                    .file(BuildLogicConstants.REPORT_SOURCE_RELEASE_VERIFICATION));
                            task.dependsOn(primary);
                        });
        return new SourceReleaseTasks(primary, checksum, comparison, verification);
    }

    private static TaskProvider<Zip> registerSourceReleaseArchive(
            Project project,
            String taskName,
            String description,
            String destination,
            ConfigurableFileTree sourceFiles,
            TaskProvider<GenerateSourceReleaseMetadataTask> metadata,
            org.gradle.api.provider.Provider<String> releaseVersion,
            org.gradle.api.provider.Provider<String> rootPrefix) {
        return project.getTasks().register(taskName, Zip.class, task -> {
            task.setGroup(DISTRIBUTION_GROUP);
            task.setDescription(description);
            task.getArchiveBaseName().set(SOURCE_RELEASE_BASE_NAME);
            task.getArchiveVersion().set(releaseVersion);
            task.getArchiveClassifier().set(SOURCE_RELEASE_CLASSIFIER);
            task.getDestinationDirectory().set(project.getLayout().getBuildDirectory()
                    .dir(destination));
            task.setPreserveFileTimestamps(false);
            task.setReproducibleFileOrder(true);
            task.setIncludeEmptyDirs(false);
            task.setDuplicatesStrategy(DuplicatesStrategy.FAIL);
            task.dependsOn(metadata);
            task.into(rootPrefix, contents -> {
                contents.from(sourceFiles);
                contents.from(metadata.flatMap(GenerateSourceReleaseMetadataTask::getOutputFile));
            });
            task.eachFile(details -> details.permissions(permissions -> permissions.unix(
                    details.getPath().endsWith("/gradlew")
                            || details.getPath().endsWith(".sh")
                            ? EXECUTABLE_FILE_MODE : REGULAR_FILE_MODE)));
        });
    }

    private static void configureModuleGraph(
            Project root,
            TaskProvider<Task> moduleCheck,
            TaskProvider<Task> moduleArchiveVerify,
            TaskProvider<Task> moduleApiVerify,
            TaskProvider<Task> stagePublications,
            TaskProvider<Delete> prepareStaging,
            TaskProvider<Task> benchmarkClasses,
            TaskProvider<VerifyJavaModuleStructureTask> moduleStructure,
            TaskProvider<GenerateJavaApiInventoryTask> apiUnion,
            TaskProvider<GenerateAggregateReleaseReceiptTask> generateReceipt,
            TaskProvider<VerifyAggregateReleaseReceiptTask> verifyReceipt,
            TaskProvider<VerifyBuildScriptShapeTask> scriptShape,
            TaskProvider<VerifyPublishedRepositoryTask> publishedRepository,
            TaskProvider<GradleBuild> publishedSmoke,
            Provider<Directory> immutableRepository,
            SourceReleaseTasks sourceRelease,
            SemanticEvidenceOrchestration.Tasks semanticEvidence) {
        for (String name : PUBLISHED_MODULES) {
            Project module = root.project(":" + name);
            moduleCheck.configure(task -> task.dependsOn(module.getTasks().named("check")));
            moduleArchiveVerify.configure(task -> task.dependsOn(
                    module.getTasks().named(BuildLogicConstants.TASK_VERIFY_REPRODUCIBLE_ARCHIVES),
                    module.getTasks().named(BuildLogicConstants.TASK_COMPARE_ARCHIVE_REPLICAS)));
            TaskProvider<GenerateJavaModuleInventoryTask> inventory = module.getTasks().named(
                    BuildLogicConstants.TASK_GENERATE_MODULE_STRUCTURE_INVENTORY,
                    GenerateJavaModuleInventoryTask.class);
            moduleStructure.configure(task -> {
                task.getModuleInventories().from(inventory.flatMap(
                        GenerateJavaModuleInventoryTask::getOutputFile));
                task.dependsOn(inventory);
            });
            TaskProvider<GenerateJavaApiInventoryTask> api = module.getTasks().named(
                    BuildLogicConstants.TASK_GENERATE_PUBLIC_API_INVENTORY,
                    GenerateJavaApiInventoryTask.class);
            apiUnion.configure(task -> {
                task.getUnionInputs().from(api.flatMap(GenerateJavaApiInventoryTask::getOutputFile));
                task.dependsOn(api);
            });
            stagePublications.configure(task -> task.dependsOn(module.getTasks().named(
                    "publishMavenJavaPublicationToStagingRepository")));
            module.getTasks().named("publishMavenJavaPublicationToStagingRepository")
                    .configure(task -> task.dependsOn(prepareStaging));
            if (module.getTasks().findByName("jmhClasses") != null) {
                benchmarkClasses.configure(task -> task.dependsOn(
                        module.getTasks().named("jmhClasses")));
            }
        }
        moduleCheck.configure(task -> task.dependsOn(root.getTasks().named("test"),
                root.project(":examples").getTasks().named("check")));
        for (String name : API_BASELINE_MODULES) {
            Project module = root.project(":" + name);
            moduleApiVerify.configure(task -> task.dependsOn(
                    module.getTasks().named(BuildLogicConstants.TASK_API_BASELINE_DIFF)));
        }
        benchmarkClasses.configure(task -> task.dependsOn(root.getTasks().named("jmhClasses")));
        root.getTasks().named(LifecycleBasePlugin.BUILD_TASK_NAME).configure(task -> {
            for (String name : PUBLISHED_MODULES) {
                task.dependsOn(root.project(":" + name).getTasks().named("build"));
            }
            task.dependsOn(root.project(":examples").getTasks().named("build"));
        });
        root.getTasks().named(LifecycleBasePlugin.CLEAN_TASK_NAME).configure(task -> {
            for (Project module : root.getSubprojects()) {
                task.dependsOn(module.getTasks().named("clean"));
            }
        });
        configureAggregateReceipt(
                root,
                generateReceipt,
                verifyReceipt,
                moduleCheck,
                moduleArchiveVerify,
                moduleApiVerify,
                moduleStructure,
                scriptShape,
                publishedRepository,
                publishedSmoke,
                immutableRepository,
                sourceRelease,
                semanticEvidence);
    }

    private static void configureAggregateReceipt(
            Project root,
            TaskProvider<GenerateAggregateReleaseReceiptTask> generateReceipt,
            TaskProvider<VerifyAggregateReleaseReceiptTask> verifyReceipt,
            TaskProvider<Task> moduleCheck,
            TaskProvider<Task> moduleArchiveVerify,
            TaskProvider<Task> moduleApiVerify,
            TaskProvider<VerifyJavaModuleStructureTask> moduleStructure,
            TaskProvider<VerifyBuildScriptShapeTask> scriptShape,
            TaskProvider<VerifyPublishedRepositoryTask> publishedRepository,
            TaskProvider<GradleBuild> publishedSmoke,
            Provider<Directory> immutableRepository,
            SourceReleaseTasks sourceRelease,
            SemanticEvidenceOrchestration.Tasks semanticEvidence) {
        java.util.List<Object> api = new java.util.ArrayList<>();
        java.util.List<Object> verification = new java.util.ArrayList<>();
        for (String name : PUBLISHED_MODULES) {
            Project module = root.project(":" + name);
            api.add(module.getTasks().named(
                    BuildLogicConstants.TASK_GENERATE_PUBLIC_API_INVENTORY));
            verification.add(module.getTasks().named(
                    BuildLogicConstants.TASK_COMPARE_ARCHIVE_REPLICAS));
            verification.add(module.getTasks().named(
                    BuildLogicConstants.TASK_VERIFY_JAVA_PACKAGE_CYCLES));
        }
        java.util.List<Object> tests = Arrays.asList(
                root.getTasks().named("test"),
                root.getTasks().named("identityDifferentialTest"),
                root.getTasks().named("patchSequenceDifferentialTest"),
                root.getTasks().named("memoryIntegrationTest"),
                root.getTasks().named("cacheLifecycleTest"),
                root.getTasks().named("fragmentedProcessingTest"));
        ConfigurableFileTree artifacts = root.fileTree(immutableRepository);
        artifacts.include("**/*.jar", "**/*.pom", "**/*.sha256", "artifact-manifest.json");
        ConfigurableFileTree testEvidence = root.fileTree(root.getRootDir());
        testEvidence.include(
                "build/test-results/**/*.xml",
                "blue-*/build/test-results/**/*.xml",
                "examples/build/test-results/**/*.xml");
        java.util.List<Object> fixtures = Arrays.asList(
                root.project(":blue-conformance").getTasks().named(
                        "releaseConformanceTest"),
                root.project(":blue-conformance").getTasks().named(
                        "generateConformancePackageIdentity"));
        verification.add(moduleStructure);
        verification.add(scriptShape);
        verification.add(publishedRepository);
        verification.add(publishedSmoke);
        verification.add(root.getTasks().named("runtimeTraceEvidence"));
        verification.add(root.getTasks().named("generateReleaseEvidence"));
        verification.add(root.getTasks().named(
                BuildLogicConstants.TASK_VERIFY_CLEAN_BUILD_EVIDENCE));
        verification.add(sourceRelease.comparison);
        verification.add(sourceRelease.verification);
        verification.add(semanticEvidence.fragmentedReport);
        verification.add(semanticEvidence.platformInvocationMatrix);
        verification.add(semanticEvidence.releaseEvidenceVerification);
        verification.add(semanticEvidence.semanticBaselineVerification);
        root.getTasks().named("verifyReleaseEvidenceInputs").configure(task ->
                task.dependsOn(root.getTasks().named("generateReleaseEvidence")));

        generateReceipt.configure(task -> {
            task.getArtifacts().setFrom(artifacts);
            task.getArtifacts().from(
                    sourceRelease.primary.flatMap(Zip::getArchiveFile),
                    sourceRelease.checksum.flatMap(GenerateChecksumFileTask::getOutputFile));
            task.getTestEvidence().setFrom(testEvidence);
            task.getFixtureEvidence().setFrom(fixtures);
            task.getApiEvidence().setFrom(api);
            task.getVerificationEvidence().setFrom(verification);
            task.dependsOn(
                    moduleCheck,
                    moduleArchiveVerify,
                    moduleApiVerify,
                    moduleStructure,
                    scriptShape,
                    publishedSmoke,
                    root.getTasks().named(
                            BuildLogicConstants.TASK_VERIFY_CLEAN_BUILD_EVIDENCE),
                    sourceRelease.checksum,
                    sourceRelease.comparison,
                    sourceRelease.verification,
                    semanticEvidence.releaseEvidenceVerification,
                    semanticEvidence.semanticBaselineVerification,
                    root.getTasks().named("releaseConformanceTest"),
                    root.getTasks().named("runtimeTraceEvidence"),
                    root.getTasks().named("verifyReleaseEvidenceInputs"));
            task.dependsOn(tests);
        });
        verifyReceipt.configure(task -> {
            task.getArtifacts().setFrom(artifacts);
            task.getArtifacts().from(
                    sourceRelease.primary.flatMap(Zip::getArchiveFile),
                    sourceRelease.checksum.flatMap(GenerateChecksumFileTask::getOutputFile));
            task.getTestEvidence().setFrom(testEvidence);
            task.getFixtureEvidence().setFrom(fixtures);
            task.getApiEvidence().setFrom(api);
            task.getVerificationEvidence().setFrom(verification);
            task.dependsOn(generateReceipt);
        });
    }

    private static void registerFocusedTests(Project project) {
        registerFocusedTest(project, "identityDifferentialTest",
                "Runs identity, Base58, and canonical digest differential coverage.", task -> {
                    include(task, "blue.language.identity.Base58Test",
                            "blue.language.identity.Base58Sha256ProviderTest",
                            "blue.language.identity.DirectBlueIdCalculatorTest",
                            "blue.language.snapshot.FrozenNodeTest",
                            "blue.language.snapshot.FrozenNodeStructuralInternerTest",
                            "blue.language.snapshot.FrozenCanonicalDigesterTest");
                });
        registerFocusedTest(project, "patchSequenceDifferentialTest",
                "Runs deterministic patch-sequence differential coverage.", task -> include(task,
                        "blue.language.processor.PatchSequenceRandomizedDifferentialTest",
                        "blue.language.processor.SequentialPatchPlanningSessionTest",
                        "blue.language.processor.PreparedPatchSequenceTest",
                        "blue.language.processor.DocumentProcessorBatchPatchTest"));
        registerFocusedTest(project, "memoryIntegrationTest",
                "Runs bounded retention and weak-reference integration coverage.", task -> {
                    task.setMaxHeapSize("512m");
                    task.setForkEvery(1L);
                    include(task, "blue.language.processor.PatchSequenceRetentionStressTest");
                });
        registerFocusedTest(project, "cacheLifecycleTest",
                "Runs cache ownership, weight, and lifecycle contracts.", task -> include(task,
                        "blue.language.BlueCacheLifecycleTest",
                        "blue.language.BlueCachePolicyTest",
                        "blue.language.runtime.WeightedLruCacheTest",
                        "blue.language.processor.ProcessorOwnedCacheLifecycleTest",
                        "blue.language.snapshot.FrozenNodeRetainedWeightTest",
                        "blue.language.merge.ResolvedReferenceCacheContractTest",
                        "blue.language.matching.FrozenTypeMatcherCachePolicyTest"));
        registerFocusedTest(project, "fragmentedProcessingTest",
                "Runs provider-fragment admission and deterministic locality coverage.", task -> {
                    task.getOutputs().dir(project.getLayout().getBuildDirectory().dir(
                            "reports/semantic-baseline/locality"));
                    task.systemProperty("blue.semantic.locality.evidence.dir",
                            project.getLayout().getBuildDirectory().dir(
                                    "reports/semantic-baseline/locality").get().getAsFile()
                                    .getAbsolutePath());
                    task.getFilter().includeTestsMatching("blue.language.provider.*FragmentsTest");
                    task.getFilter().includeTestsMatching("blue.language.processor.*Locality*Test");
                    task.getFilter().includeTestsMatching("blue.language.processor.*LogicalDelivery*Test");
                    task.getFilter().includeTestsMatching("blue.language.processor.*Routing*Test");
                    task.getFilter().includeTestsMatching("blue.language.processor.EffectiveFragmentationCatalogTest");
                    task.getFilter().includeTestsMatching("blue.language.processor.ProcessingInputAdmissionTest");
                    task.getFilter().includeTestsMatching(
                            "blue.language.processor.FragmentedProcessingFailureMatrixTest");
                });
    }

    private static TaskProvider<Test> registerFocusedTest(
            Project project, String name, String description, Action<Test> configuration) {
        SourceSet testSourceSet = project.getExtensions().getByType(SourceSetContainer.class)
                .getByName(SourceSet.TEST_SOURCE_SET_NAME);
        return project.getTasks().register(name, Test.class, task -> {
            task.setGroup(GROUP);
            task.setDescription(description);
            task.dependsOn(project.getTasks().named(JavaPlugin.TEST_CLASSES_TASK_NAME));
            task.setTestClassesDirs(testSourceSet.getOutput().getClassesDirs());
            task.setClasspath(testSourceSet.getRuntimeClasspath());
            task.useJUnitPlatform();
            configuration.execute(task);
        });
    }

    private static void include(Test task, String... tests) {
        for (String test : tests) {
            task.getFilter().includeTestsMatching(test);
        }
    }

    private static void registerEvidenceExecutions(Project project) {
        SourceSet test = project.getExtensions().getByType(SourceSetContainer.class)
                .getByName(SourceSet.TEST_SOURCE_SET_NAME);
        project.getTasks().register("releaseConformanceTest", DefaultTask.class, task -> {
            task.setGroup(GROUP);
            task.setDescription("Root alias for the exact conformance module release gate.");
            task.dependsOn(":blue-conformance:releaseConformanceTest");
        });
        project.getTasks().register("runtimeTraceEvidence", JavaExec.class, task -> {
            task.setGroup(GROUP);
            task.setDescription("Records ordered RuntimeWorkSession trace evidence.");
            task.dependsOn(project.getTasks().named(JavaPlugin.TEST_CLASSES_TASK_NAME));
            task.setClasspath(test.getRuntimeClasspath());
            task.getMainClass().set("blue.language.processor.RuntimeTraceEvidenceCli");
            task.args(project.getLayout().getBuildDirectory().file(
                    "reports/runtime-trace/runtime-work-session.json")
                    .get().getAsFile().getAbsolutePath());
            task.getOutputs().file(project.getLayout().getBuildDirectory().file(
                    "reports/runtime-trace/runtime-work-session.json"));
        });
    }

    private static void registerCompatibilityAliases(
            Project project,
            TaskProvider<Task> moduleApiVerify,
            TaskProvider<Task> moduleArchiveVerify,
            SourceReleaseTasks sourceRelease) {
        lifecycle(project, "verifyFinalApiBaseline",
                "Checks all tracked module API baselines.")
                .configure(task -> task.dependsOn(moduleApiVerify));
        lifecycle(project, "verifyDeterministicJar",
                "Checks every published module archive and replica.")
                .configure(task -> task.dependsOn(moduleArchiveVerify));
        lifecycle(project, "verifyDeterministicSourceArchives",
                "Checks every published sources archive and replica.")
                .configure(task -> task.dependsOn(
                        moduleArchiveVerify,
                        sourceRelease.comparison,
                        sourceRelease.verification));
    }

    private static TaskProvider<Task> lifecycle(Project project, String name, String description) {
        return project.getTasks().register(name, task -> {
            task.setGroup(GROUP);
            task.setDescription(description);
        });
    }

    private static void requireRoot(Project project) {
        if (project != project.getRootProject()) {
            throw new org.gradle.api.GradleException(
                    "blue.root-orchestration may only be applied to the root project");
        }
    }

    /** Providers for the independently assembled source-release outputs and gates. */
    private static final class SourceReleaseTasks {

        private final TaskProvider<Zip> primary;
        private final TaskProvider<GenerateChecksumFileTask> checksum;
        private final TaskProvider<CompareArchiveReplicasTask> comparison;
        private final TaskProvider<VerifySourceReleaseArchiveTask> verification;

        private SourceReleaseTasks(
                TaskProvider<Zip> primary,
                TaskProvider<GenerateChecksumFileTask> checksum,
                TaskProvider<CompareArchiveReplicasTask> comparison,
                TaskProvider<VerifySourceReleaseArchiveTask> verification) {
            this.primary = primary;
            this.checksum = checksum;
            this.comparison = comparison;
            this.verification = verification;
        }
    }

    /** Small insertion-ordered map builder that keeps GradleBuild properties explicit. */
    private static final class TreeMapBuilder {

        private final java.util.Map<String, String> values = new java.util.TreeMap<>();

        private TreeMapBuilder put(String key, String value) {
            values.put(key, value);
            return this;
        }

        private java.util.Map<String, String> build() {
            return values;
        }
    }
}
