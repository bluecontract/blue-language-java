package blue.buildlogic;

import blue.buildlogic.tasks.GenerateJavaModuleInventoryTask;
import blue.buildlogic.tasks.VerifyJavaPackageCyclesTask;
import org.gradle.api.JavaVersion;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.logging.TestExceptionFormat;
import org.gradle.api.tasks.testing.logging.TestLogEvent;
import org.gradle.external.javadoc.StandardJavadocDocletOptions;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

/** Shared Java 8 bytecode, JUnit 5, source/Javadoc artifact, and repository conventions. */
public final class Java8LibraryConventionsPlugin implements Plugin<Project> {

    private static final int JAVA_LANGUAGE_VERSION = 8;
    private static final int SINGLE_TEST_FORK = 1;
    private static final long REUSE_TEST_PROCESS = 0L;
    private static final String CHARACTER_ENCODING_UTF_8 = "UTF-8";
    private static final String JUNIT_BOM_COORDINATE = "org.junit:junit-bom:5.10.2";
    private static final String JUNIT_JUPITER_COORDINATE =
            "org.junit.jupiter:junit-jupiter";
    private static final String JUNIT_LAUNCHER_COORDINATE =
            "org.junit.platform:junit-platform-launcher";
    private static final String JUNIT_PARALLEL_EXECUTION_PROPERTY =
            "junit.jupiter.execution.parallel.enabled";
    private static final String JUNIT_PARALLEL_EXECUTION_DISABLED = "false";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaLibraryPlugin.class);

        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.setSourceCompatibility(JavaVersion.VERSION_1_8);
        java.setTargetCompatibility(JavaVersion.VERSION_1_8);
        java.withSourcesJar();
        java.withJavadocJar();

        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            task.getOptions().setEncoding(CHARACTER_ENCODING_UTF_8);
            task.getOptions().getRelease().set(JAVA_LANGUAGE_VERSION);
        });
        configureJavadocs(project);
        configureTesting(project);

        if (System.getenv("CI") == null
                && Boolean.parseBoolean(String.valueOf(
                        project.findProperty("blue.allowMavenLocal")))) {
            project.getRepositories().mavenLocal();
        }
        project.getRepositories().mavenCentral();

        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        project.getTasks().register(
                BuildLogicConstants.TASK_GENERATE_MODULE_STRUCTURE_INVENTORY,
                GenerateJavaModuleInventoryTask.class,
                task -> {
                    task.setGroup(BuildLogicConstants.VERIFICATION_GROUP);
                    task.setDescription("Inventories this module's compiled packages and references.");
                    task.getModuleName().convention(project.getName());
                    task.getCompiledInputs().from(
                            sourceSets.getByName("main").getOutput().getClassesDirs());
                    task.getOutputFile().convention(project.getLayout().getBuildDirectory()
                            .file(BuildLogicConstants.REPORT_MODULE_INVENTORY));
                    task.dependsOn(project.getTasks().named("classes"));
                });

        TaskProvider<VerifyJavaPackageCyclesTask> packageCycles =
                project.getTasks().register(
                        BuildLogicConstants.TASK_VERIFY_JAVA_PACKAGE_CYCLES,
                        VerifyJavaPackageCyclesTask.class,
                        task -> {
                            task.setGroup(BuildLogicConstants.VERIFICATION_GROUP);
                            task.setDescription(
                                    "Rejects strongly connected components in this module's "
                                            + "compiled Java package graph.");
                            task.getCompiledInputs().from(
                                    sourceSets.getByName("main")
                                            .getOutput().getClassesDirs());
                            task.getReportFile().convention(
                                    project.getLayout().getBuildDirectory()
                                            .file(BuildLogicConstants.REPORT_PACKAGE_CYCLES));
                            task.dependsOn(project.getTasks().named("classes"));
                        });
        project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME)
                .configure(task -> task.dependsOn(packageCycles));
    }

    /** Adds the shared test stack without imposing optional mocking libraries on consumers. */
    private static void configureTesting(Project project) {
        DependencyHandler dependencies = project.getDependencies();
        dependencies.add(
                JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME,
                dependencies.platform(JUNIT_BOM_COORDINATE));
        dependencies.add(
                JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME,
                JUNIT_JUPITER_COORDINATE);
        dependencies.add(
                JavaPlugin.TEST_RUNTIME_ONLY_CONFIGURATION_NAME,
                JUNIT_LAUNCHER_COORDINATE);

        JavaToolchainService toolchains =
                project.getExtensions().getByType(JavaToolchainService.class);
        org.gradle.api.provider.Provider<JavaLauncher> javaEightLauncher =
                toolchains.launcherFor(spec -> spec.getLanguageVersion()
                        .set(JavaLanguageVersion.of(JAVA_LANGUAGE_VERSION)));
        project.getTasks().withType(Test.class).configureEach(task -> {
            task.getJavaLauncher().convention(javaEightLauncher);
            task.useJUnitPlatform();
            task.setDefaultCharacterEncoding(CHARACTER_ENCODING_UTF_8);
            task.setFailFast(false);
            task.setForkEvery(REUSE_TEST_PROCESS);
            task.setMaxParallelForks(SINGLE_TEST_FORK);
            task.systemProperty(
                    JUNIT_PARALLEL_EXECUTION_PROPERTY,
                    JUNIT_PARALLEL_EXECUTION_DISABLED);

            task.getReports().getHtml().getRequired().set(true);
            task.getReports().getJunitXml().getRequired().set(true);
            task.getReports().getJunitXml().setOutputPerTestCase(true);
            task.getReports().getJunitXml().getMergeReruns().set(false);
            task.getReports().getJunitXml().getIncludeSystemOutLog().set(false);
            task.getReports().getJunitXml().getIncludeSystemErrLog().set(false);

            task.getTestLogging().setEvents(
                    java.util.Arrays.asList(TestLogEvent.FAILED, TestLogEvent.SKIPPED));
            task.getTestLogging().setExceptionFormat(TestExceptionFormat.FULL);
            task.getTestLogging().setShowExceptions(true);
            task.getTestLogging().setShowCauses(true);
            task.getTestLogging().setShowStackTraces(true);
            task.getTestLogging().setShowStandardStreams(false);
        });
    }

    /** Normalizes generated Javadocs so their archive contents are host-independent. */
    private static void configureJavadocs(Project project) {
        project.getTasks().withType(Javadoc.class).configureEach(task -> {
            task.setFailOnError(false);
            task.getOptions().setEncoding(CHARACTER_ENCODING_UTF_8);
            if (task.getOptions() instanceof StandardJavadocDocletOptions) {
                StandardJavadocDocletOptions options =
                        (StandardJavadocDocletOptions) task.getOptions();
                options.setCharSet(CHARACTER_ENCODING_UTF_8);
                options.setDocEncoding(CHARACTER_ENCODING_UTF_8);
                options.setNoTimestamp(true);
                options.addBooleanOption("Xdoclint:none", true);
            }
        });
    }
}
