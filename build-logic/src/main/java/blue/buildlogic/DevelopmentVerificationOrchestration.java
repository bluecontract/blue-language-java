package blue.buildlogic;

import blue.buildlogic.tasks.DevelopmentTest;
import java.nio.file.Files;
import java.util.Arrays;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

/** Small development gates over existing test sources; release orchestration stays complete. */
final class DevelopmentVerificationOrchestration {
    private DevelopmentVerificationOrchestration() {}

    static void register(Project project) {
        CorpusDevelopmentOrchestration.register(project);
        TaskProvider<Task> mirrors = project.getTasks().register("verifySpecificationMirrors", task -> {
            task.setGroup("verification");
            task.setDescription("Checks the two specification mirrors without regenerating them.");
            String[][] paths = {
                {"blue-language-core/src/main/resources/specifications/blue-language-specification-1.0.md",
                 "blue-conformance/src/main/resources/language/1.0/spec.md"},
                {"blue-contracts-core/src/main/resources/specifications/blue-contracts-and-processor-specification-1.0.md",
                 "blue-conformance/src/main/resources/contract/1.0/spec.md"}
            };
            for (String[] pair : paths) {
                task.getInputs().files((Object[]) pair);
            }
            task.doLast(ignored -> {
                for (String[] pair : paths) {
                    try {
                        if (Files.mismatch(project.file(pair[0]).toPath(), project.file(pair[1]).toPath()) != -1) {
                            throw new GradleException("Specification mirror drift: " + Arrays.toString(pair));
                        }
                    } catch (java.io.IOException exception) {
                        throw new GradleException("Cannot check specification mirrors", exception);
                    }
                }
            });
        });
        project.getTasks().register("verifySourceDevelopment", org.gradle.api.tasks.Exec.class, task -> {
            task.setGroup("verification");
            task.setDescription("Checks source integrity and reports pending specification bindings without certifying a package.");
            task.commandLine(project.getProviders().gradleProperty("bluePythonExecutable").orElse("python3").get(),
                    "blue-conformance/src/main/tools/verify_source_development.py",
                    "--repository-root", project.getRootDir().getAbsolutePath());
        });
        project.getTasks().register("candidatePreflight", task -> {
            task.setGroup("verification");
            task.setDescription("Strict cheap candidate checks; does not replace complete release verification.");
            task.dependsOn("developmentPreflight", "verifyAggregateReleaseManifest");
        });
        TaskProvider<Task> preflight = project.getTasks().register("developmentPreflight", task -> {
            task.setGroup("verification");
            task.setDescription("Checks source integrity, specification syntax/mirrors and reports pending bindings.");
            task.dependsOn(mirrors, "verifySourceDevelopment", "verifyBuildScriptShape");
        });

        registerTest(project, "focusedTest").configure(task -> {
            task.setDescription("Runs only the required --tests selection, with development inventory.");
            task.getSelectionRequired().set(true);
        });
        registerTest(project, "focusedLanguageConformanceTest").configure(task -> {
            task.setDescription("Runs Language dynamic fixtures selected by -PblueFixtureCases (IDs or prefix*).");
            task.getFilter().includeTestsMatching("blue.language.conformance.api.BlueLanguageConformanceFixtureTest.shouldPassAllBlueLanguage10Fixtures");
            task.systemProperty("blue.fixture.cases", project.getProviders()
                    .gradleProperty("blueFixtureCases").orElse("").get());
            task.getTestLogging().setShowStandardStreams(true);
        });
        var testSources = project.getExtensions().getByType(SourceSetContainer.class).getByName("test");
        project.getTasks().register("listLanguageConformanceCases", JavaExec.class, task -> {
            task.setGroup("verification");
            task.setDescription("Lists validated Language fixture IDs without executing their semantics.");
            task.setClasspath(testSources.getRuntimeClasspath());
            task.dependsOn("testClasses");
            task.getMainClass().set("blue.language.conformance.api.LanguageFixtureSelection");
            if (project.getProviders().gradleProperty("blueFixtureCases").isPresent()) {
                task.args(project.getProviders().gradleProperty("blueFixtureCases").get());
            }
            task.setMaxHeapSize("512m");
        });

        TaskProvider<DevelopmentTest> language = registerTest(project, "languageComponentTest");
        language.configure(task -> include(task,
                "blue.language.identity.*Test",
                "blue.language.model.NodePathTest",
                "blue.language.model.wire.ParsedJsonPointerTest",
                "blue.language.conformance.api.LanguageFixtureSelectionTest"));
        TaskProvider<DevelopmentTest> contracts = registerTest(project, "contractsComponentTest");
        contracts.configure(task -> include(task,
                "blue.language.processor.RuntimeWorkSessionTest",
                "blue.language.processor.PreparedPatchSequenceTest",
                "blue.language.processor.ImmutableJsonPatchTest"));
        language.configure(task -> task.dependsOn(preflight));
        contracts.configure(task -> task.dependsOn(preflight));
        project.getTasks().register("fastVerify", task -> {
            task.setGroup("verification");
            task.setDescription("Development gate: drift preflight, identity/path and runtime-work/patch components.");
            task.dependsOn(language, contracts);
        });
        project.getTasks().register("developmentIntegrationVerify", task -> {
            task.setGroup("verification");
            task.setDescription("Development integration: fast components plus cache and patch differential families.");
            task.dependsOn("fastVerify", "cacheLifecycleTest", "patchSequenceDifferentialTest", ":examples:test");
        });
        // Dependency preflight alone does not order sibling compilation; order it explicitly.
        project.getAllprojects().forEach(module -> module.getTasks().withType(
                org.gradle.api.tasks.compile.JavaCompile.class).configureEach(task -> task.mustRunAfter(preflight)));
        project.getTasks().named("cacheLifecycleTest").configure(task -> task.mustRunAfter(preflight));
        project.getTasks().named("patchSequenceDifferentialTest").configure(task -> task.mustRunAfter(preflight));
    }

    private static TaskProvider<DevelopmentTest> registerTest(Project project, String name) {
        var sources = project.getExtensions().getByType(SourceSetContainer.class).getByName("test");
        return project.getTasks().register(name, DevelopmentTest.class, task -> {
            task.setGroup("verification");
            task.setDescription("Runs the documented development component with an exact execution inventory.");
            task.dependsOn("testClasses");
            task.setTestClassesDirs(sources.getOutput().getClassesDirs());
            task.setClasspath(sources.getRuntimeClasspath());
        });
    }

    private static void include(DevelopmentTest task, String... patterns) {
        for (String pattern : patterns) {
            task.getFilter().includeTestsMatching(pattern);
        }
    }
}
