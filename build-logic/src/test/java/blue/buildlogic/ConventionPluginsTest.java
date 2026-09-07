package blue.buildlogic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import blue.buildlogic.tasks.CompareApiBaselineTask;
import blue.buildlogic.tasks.CompareArchiveReplicasTask;
import blue.buildlogic.tasks.GenerateAggregateReleaseReceiptTask;
import blue.buildlogic.tasks.GenerateCleanBuildEvidenceTask;
import blue.buildlogic.tasks.GenerateCleanSourceEvidenceTask;
import blue.buildlogic.tasks.GenerateFileIdentityTask;
import blue.buildlogic.tasks.GenerateJavaApiInventoryTask;
import blue.buildlogic.tasks.GenerateJavaModuleInventoryTask;
import blue.buildlogic.tasks.GenerateReleaseEvidenceTask;
import blue.buildlogic.tasks.VerifyAggregateReleaseReceiptTask;
import blue.buildlogic.tasks.VerifyCleanBuildEvidenceTask;
import blue.buildlogic.tasks.VerifyInputIdentityTask;
import blue.buildlogic.tasks.VerifyJavaPackageCyclesTask;
import blue.buildlogic.tasks.VerifyJavaModuleStructureTask;
import blue.buildlogic.tasks.VerifyReleaseEnvironmentTask;
import blue.buildlogic.tasks.VerifyReproducibleArchivesTask;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import me.champeau.jmh.JmhParameters;
import org.gradle.api.JavaVersion;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions;
import org.gradle.external.javadoc.StandardJavadocDocletOptions;
import org.gradle.testfixtures.ProjectBuilder;
import org.gradle.plugins.signing.SigningExtension;
import org.junit.jupiter.api.Test;
import org.gradle.api.InvalidUserDataException;
import org.junit.jupiter.api.io.TempDir;

final class ConventionPluginsTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldConfigureJavaEightCompilationAndDocumentation() {
        // given
        Project project = ProjectBuilder.builder()
                .withProjectDir(temporaryDirectory.toFile())
                .build();

        // when
        project.getPluginManager().apply(Java8LibraryConventionsPlugin.class);

        // then
        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        JavaCompile compileJava = (JavaCompile) project.getTasks().getByName("compileJava");
        Javadoc javadoc = (Javadoc) project.getTasks().getByName("javadoc");
        StandardJavadocDocletOptions javadocOptions =
                (StandardJavadocDocletOptions) javadoc.getOptions();
        assertEquals(JavaVersion.VERSION_1_8, java.getSourceCompatibility());
        assertEquals(JavaVersion.VERSION_1_8, java.getTargetCompatibility());
        assertEquals(8, compileJava.getOptions().getRelease().get());
        assertEquals("UTF-8", compileJava.getOptions().getEncoding());
        assertEquals("UTF-8", javadocOptions.getEncoding());
        assertEquals("UTF-8", javadocOptions.getCharSet());
        assertEquals("UTF-8", javadocOptions.getDocEncoding());
        assertTrue(javadocOptions.isNoTimestamp());
        assertTrue(javadoc.isFailOnError());
    }

    @Test
    void shouldProvideJUnitFiveWithoutImposingMockito() {
        // given
        Project project = ProjectBuilder.builder()
                .withProjectDir(temporaryDirectory.toFile())
                .build();

        // when
        project.getPluginManager().apply(Java8LibraryConventionsPlugin.class);

        // then
        Set<String> testImplementation = dependencyCoordinates(
                project.getConfigurations().getByName("testImplementation"));
        Set<String> testRuntimeOnly = dependencyCoordinates(
                project.getConfigurations().getByName("testRuntimeOnly"));
        assertTrue(testImplementation.contains("org.junit:junit-bom:5.10.2"));
        assertTrue(testImplementation.contains("org.junit.jupiter:junit-jupiter"));
        assertTrue(testRuntimeOnly.contains("org.junit.platform:junit-platform-launcher"));
        assertTrue(project.getConfigurations().stream()
                .flatMap(configuration -> configuration.getDependencies().stream())
                .noneMatch(dependency -> "mockito-core".equals(dependency.getName())));
    }

    @Test
    void shouldConfigureDeterministicJUnitPlatformExecution() {
        // given
        Project project = ProjectBuilder.builder()
                .withProjectDir(temporaryDirectory.toFile())
                .build();

        // when
        project.getPluginManager().apply(Java8LibraryConventionsPlugin.class);

        // then
        org.gradle.api.tasks.testing.Test test =
                (org.gradle.api.tasks.testing.Test) project.getTasks().getByName("test");
        assertTrue(test.getOptions() instanceof JUnitPlatformOptions);
        assertEquals(8, test.getJavaLauncher().get()
                .getMetadata().getLanguageVersion().asInt());
        assertEquals("UTF-8", test.getDefaultCharacterEncoding());
        assertEquals(1, test.getMaxParallelForks());
        assertEquals(0L, test.getForkEvery());
        assertFalse(test.getFailFast());
        assertEquals("false", test.getSystemProperties()
                .get("junit.jupiter.execution.parallel.enabled"));
        assertTrue(test.getReports().getHtml().getRequired().get());
        assertTrue(test.getReports().getJunitXml().getRequired().get());
        assertTrue(test.getReports().getJunitXml().isOutputPerTestCase());
        assertFalse(test.getReports().getJunitXml().getMergeReruns().get());
        assertFalse(test.getReports().getJunitXml().getIncludeSystemOutLog().get());
        assertFalse(test.getReports().getJunitXml().getIncludeSystemErrLog().get());
    }

    @Test
    void shouldRegisterAndWireThreeDeterministicArchiveReplicas() {
        // given
        Project project = ProjectBuilder.builder()
                .withProjectDir(temporaryDirectory.toFile())
                .build();

        // when
        project.getPluginManager().apply(Java8LibraryConventionsPlugin.class);
        project.getPluginManager().apply(ReproducibleArchivesPlugin.class);

        // then
        Jar jar = (Jar) project.getTasks().getByName("jar");
        Jar jarReplica = (Jar) project.getTasks().getByName("jarReplica");
        CompareArchiveReplicasTask comparison = (CompareArchiveReplicasTask)
                project.getTasks().getByName("compareArchiveReplicas");
        assertFalse(jar.isPreserveFileTimestamps());
        assertTrue(jar.isReproducibleFileOrder());
        assertFalse(jarReplica.isPreserveFileTimestamps());
        assertTrue(jarReplica.isReproducibleFileOrder());
        assertEquals(jar.getArchiveFileName().get(), jarReplica.getArchiveFileName().get());
        assertEquals(3, comparison.getReferenceArchives().getFiles().size());
        assertEquals(3, comparison.getReplicaArchives().getFiles().size());
        assertTrue(project.getTasks().getByName("verifyReproducibleArchives")
                instanceof VerifyReproducibleArchivesTask);
        assertNotNull(project.getTasks().findByName("sourcesJarReplica"));
        assertNotNull(project.getTasks().findByName("javadocJarReplica"));
        assertTrue(project.getTasks().getByName("check")
                .getTaskDependencies()
                .getDependencies(null)
                .stream()
                .anyMatch(task -> task.getName().equals("compareArchiveReplicas")));
    }

    @Test
    void shouldRegisterJavaArchitectureVerificationTasks() {
        // given
        Project project = ProjectBuilder.builder()
                .withProjectDir(temporaryDirectory.toFile())
                .build();

        // when
        project.getPluginManager().apply(Java8LibraryConventionsPlugin.class);

        // then
        assertTrue(project.getTasks().getByName("generateModuleStructureInventory")
                instanceof GenerateJavaModuleInventoryTask);
        assertTrue(project.getTasks().getByName("verifyJavaPackageCycles")
                instanceof VerifyJavaPackageCyclesTask);
        assertTrue(project.getTasks().getByName("check")
                .getTaskDependencies()
                .getDependencies(null)
                .stream()
                .anyMatch(task -> task.getName().equals("verifyJavaPackageCycles")));
    }

    @Test
    void shouldRegisterTypedVerificationTasksWithoutExecutingThem() {
        // given
        Project project = ProjectBuilder.builder()
                .withProjectDir(temporaryDirectory.toFile())
                .build();

        // when
        project.getPluginManager().apply(ApiBaselinePlugin.class);
        project.getPluginManager().apply(ConformancePackagePlugin.class);
        project.getPluginManager().apply(ReleaseEvidencePlugin.class);

        // then
        assertTrue(project.getTasks().getByName("apiBaselineDiff")
                instanceof CompareApiBaselineTask);
        assertTrue(project.getTasks().getByName("generatePublicApiInventory")
                instanceof GenerateJavaApiInventoryTask);
        assertTrue(project.getTasks().getByName("generatePublicApiUnion")
                instanceof GenerateJavaApiInventoryTask);
        assertTrue(project.getTasks().getByName("generateConformancePackageIdentity")
                instanceof GenerateFileIdentityTask);
        assertTrue(project.getTasks().getByName("generateReleaseEvidence")
                instanceof GenerateReleaseEvidenceTask);
        assertTrue(project.getTasks().getByName("verifyReleaseEvidenceInputs")
                instanceof VerifyInputIdentityTask);
        assertTrue(project.getTasks().getByName("generateCleanSourceEvidence")
                instanceof GenerateCleanSourceEvidenceTask);
        assertTrue(project.getTasks().getByName("generateCleanBuildEvidence")
                instanceof GenerateCleanBuildEvidenceTask);
        assertTrue(project.getTasks().getByName("verifyCleanBuildEvidence")
                instanceof VerifyCleanBuildEvidenceTask);
        assertTrue(project.getTasks().getByName("generateAggregateReleaseReceipt")
                instanceof GenerateAggregateReleaseReceiptTask);
        assertTrue(project.getTasks().getByName("verifyAggregateReleaseReceipt")
                instanceof VerifyAggregateReleaseReceiptTask);
        assertTrue(project.getTasks().getByName("verifyModuleStructure")
                instanceof VerifyJavaModuleStructureTask);
        assertNotNull(project.getTasks().getByName("generateReleaseEvidence")
                .getGroup());
    }

    @Test
    void shouldApplyThePinnedJmhPluginThroughItsConvention() throws Exception {
        // given
        Project project = ProjectBuilder.builder()
                .withProjectDir(Files.createDirectories(
                        temporaryDirectory.resolve("jmh-project")).toFile())
                .build();

        // when
        project.getPluginManager().apply(JmhConventionsPlugin.class);

        // then
        assertTrue(project.getPluginManager().hasPlugin("me.champeau.jmh"));
        assertNotNull(project.getTasks().findByName("jmh"));
        JmhParameters parameters = (JmhParameters)
                project.getExtensions().getByName("jmh");
        assertTrue(parameters.getIncludeTests().get());
        assertTrue(parameters.getFailOnError().get());
    }

    @Test
    void shouldIsolateCompatibilitySourcesToTestsAndBenchmarks()
            throws Exception {
        // given
        Project project = ProjectBuilder.builder()
                .withProjectDir(Files.createDirectories(
                        temporaryDirectory.resolve("compat-project")).toFile())
                .build();
        project.getPluginManager().apply(JavaPlugin.class);
        project.getPluginManager().apply("me.champeau.jmh");
        SourceSetContainer sourceSets = project.getExtensions()
                .getByType(SourceSetContainer.class);

        // when
        RootOrchestrationPlugin.configureCompatibilitySources(
                project, sourceSets);

        // then
        Path compatibilityDirectory = project.file("src/compat/java")
                .toPath().toAbsolutePath().normalize();
        assertTrue(sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME)
                .getJava().getSrcDirs().stream()
                .map(file -> file.toPath().toAbsolutePath().normalize())
                .anyMatch(compatibilityDirectory::equals));
        assertTrue(sourceSets.getByName("jmh")
                .getJava().getSrcDirs().stream()
                .map(file -> file.toPath().toAbsolutePath().normalize())
                .anyMatch(compatibilityDirectory::equals));
        assertFalse(sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
                .getJava().getSrcDirs().stream()
                .map(file -> file.toPath().toAbsolutePath().normalize())
                .anyMatch(compatibilityDirectory::equals));
    }

    @Test
    void shouldSeparateAggregateTestsFromCompatibilityBenchmarks()
            throws Exception {
        // given
        Project project = ProjectBuilder.builder()
                .withName("root")
                .withProjectDir(Files.createDirectories(
                        temporaryDirectory.resolve("dependency-project"))
                        .toFile())
                .build();
        List<String> implementationModules = Arrays.asList(
                "blue-language-model",
                "blue-language-core",
                "blue-language-mapping",
                "blue-language-ipfs",
                "blue-contracts-core");
        for (String module : implementationModules) {
            childProject(project, module);
        }
        childProject(project, "blue-language-java");
        project.getPluginManager().apply(JmhConventionsPlugin.class);

        // when
        RootOrchestrationPlugin.configureCompatibilityDependencies(
                project, project.getDependencies());

        // then
        assertEquals(Collections.singleton("blue-language-java"),
                dependencyNames(project.getConfigurations()
                        .getByName("testImplementation")));
        assertEquals(new LinkedHashSet<>(implementationModules),
                dependencyNames(project.getConfigurations()
                        .getByName("jmhImplementation")));
        assertFalse(dependencyNames(project.getConfigurations()
                        .getByName("jmhImplementation"))
                .contains("blue-language-java"));
        assertTrue(project.getConfigurations()
                .getByName("jmhRuntimeClasspath")
                .getExcludeRules().stream()
                .anyMatch(rule -> "blue-language-java"
                        .equals(rule.getModule())));
    }

    @Test
    void shouldParseTypedJmhIncludeFiltersDeterministically() {
        // given
        String filters = "DeepGraph.*processSelectedLeaf, ReferenceBlueId.*,DeepGraph.*processSelectedLeaf";

        // when
        java.util.List<String> parsed = JmhConventionsPlugin.parseIncludes(filters);

        // then
        assertEquals(java.util.Collections.singletonList(
                "(?:DeepGraph.*processSelectedLeaf)|(?:ReferenceBlueId.*)"), parsed);
        Pattern combined = Pattern.compile(parsed.get(0));
        assertTrue(combined.matcher(
                "DeepGraphPhysicalLocalityBenchmark.processSelectedLeaf").matches());
        assertTrue(combined.matcher(
                "ReferenceBlueIdValidationBenchmark.resolve").matches());
    }

    @Test
    void shouldPassFinalQualitySmokeBenchmarksAsOneExactJmhRegex() {
        // given
        List<String> benchmarkNames = Arrays.asList(
                "blue.language.ReferenceBlueIdValidationBenchmark."
                        + "resolveDeepValidReferenceDocument",
                "blue.language.ProcessingSelectionCacheBenchmark."
                        + "processWarmSameNode",
                "blue.language.processor.DeepGraphPhysicalLocalityBenchmark."
                        + "processPlatformCommit");

        // when
        List<String> includes =
                FinalQualityOrchestration.requiredSmokeIncludes();
        Pattern combined = Pattern.compile(includes.get(0));

        // then
        assertEquals(1, includes.size());
        for (String benchmarkName : benchmarkNames) {
            assertTrue(combined.matcher(benchmarkName).matches());
        }
        assertFalse(combined.matcher(
                "blue.language.ProcessingSelectionCacheBenchmark."
                        + "processWarmClone").matches());
        assertFalse(combined.matcher(
                "blue.language.processor.DeepGraphPhysicalLocalityBenchmark."
                        + "processSelectedLeaf").matches());
        assertFalse(combined.matcher(
                "blue.language.processor.DeepGraphPhysicalLocalityBenchmark."
                        + "processPlatformCommitIncludingSetup").find());
        assertFalse(combined.matcher(
                "blue.language.processor.DeepGraphPhysicalLocalityBenchmark."
                        + "processPlatformCommitUnexpectedSuffix").find());
    }

    @Test
    void shouldRejectEmptyOrMalformedJmhIncludeFilters() {
        // given / when / then
        assertThrows(InvalidUserDataException.class,
                () -> JmhConventionsPlugin.parseIncludes(""));
        assertThrows(InvalidUserDataException.class,
                () -> JmhConventionsPlugin.parseIncludes("first,,second"));
        assertThrows(InvalidUserDataException.class,
                () -> JmhConventionsPlugin.parseIncludes("[unterminated"));
    }

    @Test
    void shouldConfigureAndGuardJavaLibraryPublication() throws Exception {
        // given
        Project project = ProjectBuilder.builder()
                .withName("blue-language-core")
                .withProjectDir(Files.createDirectories(
                        temporaryDirectory.resolve("jreleaser-project")).toFile())
                .build();
        project.setGroup("blue.language");
        project.setVersion("1.0.0");
        project.setDescription("Blue Language semantic core");

        // when
        project.getPluginManager().apply(Java8LibraryConventionsPlugin.class);
        project.getPluginManager().apply(JReleaserPublishingPlugin.class);

        // then
        PublishingExtension publishing =
                project.getExtensions().getByType(PublishingExtension.class);
        MavenPublication publication = (MavenPublication)
                publishing.getPublications().getByName("mavenJava");
        MavenArtifactRepository staging = (MavenArtifactRepository)
                publishing.getRepositories().getByName("staging");
        assertFalse(project.getPluginManager().hasPlugin("org.jreleaser"));
        assertTrue(project.getPluginManager().hasPlugin("maven-publish"));
        assertTrue(project.getPluginManager().hasPlugin("signing"));
        assertEquals("blue.language", publication.getGroupId());
        assertEquals("blue-language-core", publication.getArtifactId());
        assertEquals("1.0.0", publication.getVersion());
        assertEquals("Blue Language Core Java Library", publication.getPom().getName().get());
        assertEquals("Blue Language semantic core", publication.getPom().getDescription().get());
        assertEquals("https://timeline.blue", publication.getPom().getUrl().get());
        assertEquals(project.getLayout().getBuildDirectory().dir("staging-deploy")
                .get().getAsFile().toURI(), staging.getUrl());
        assertTrue(staging.getAuthentication().isEmpty());
        assertNotNull(project.getExtensions().getByType(SigningExtension.class));
        assertNotNull(project.getTasks().findByName("signMavenJavaPublication"));
        assertTrue(project.getTasks().getByName("verifyReleaseEnvironment")
                instanceof VerifyReleaseEnvironmentTask);
        assertTrue(project.getTasks()
                .getByName("publishMavenJavaPublicationToStagingRepository")
                .getTaskDependencies()
                .getDependencies(null)
                .stream()
                .anyMatch(task -> task.getName().equals("verifyReleaseEnvironment")));
        assertTrue(project.getTasks().getByName("signMavenJavaPublication")
                .getTaskDependencies()
                .getDependencies(null)
                .stream()
                .anyMatch(task -> task.getName().equals("verifyReleaseEnvironment")));
    }

    private static Set<String> dependencyCoordinates(Configuration configuration) {
        return configuration.getDependencies().stream()
                .map(dependency -> dependency.getGroup() + ":" + dependency.getName()
                        + (dependency.getVersion() == null ? "" : ":" + dependency.getVersion()))
                .collect(Collectors.toSet());
    }

    private Project childProject(Project parent, String name)
            throws Exception {
        return ProjectBuilder.builder()
                .withName(name)
                .withParent(parent)
                .withProjectDir(Files.createDirectories(
                        temporaryDirectory.resolve("dependency-project")
                                .resolve(name)).toFile())
                .build();
    }

    private static Set<String> dependencyNames(Configuration configuration) {
        return configuration.getDependencies().stream()
                .map(org.gradle.api.artifacts.Dependency::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
