package blue.buildlogic;

import blue.buildlogic.tasks.DevelopmentTest;
import org.gradle.api.Project;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSetContainer;

/** Selection and output isolation for existing expensive test runners. */
final class CorpusDevelopmentOrchestration {
    private CorpusDevelopmentOrchestration() {}

    static void register(Project root) {
        sources(root).getByName("test").java(java -> java.srcDir(root.file("src/test-support/java")));
        selected(root, "focusedContractsConformanceTest", "blueContractsCases", "blue.contracts.fixture.cases",
                "blue.language.conformance.contracts.BlueContractsConformanceFixtureTest.selectedContractsFixtures");
        listing(root, "listContractsConformanceCases", "blueContractsCases",
                "blue.language.conformance.contracts.ContractsFixtureSelection");
        selected(root, "focusedFragmentedProcessingTest", "blueFragmentedCases", "blue.fragmented.cases",
                "blue.language.processor.FragmentedProcessingLocalityIntegrationTest.shouldVerifyExactRootAndEventFragmentsHaveIdenticalSemanticsAcrossMatrix");
        listing(root, "listFragmentedProcessingCases", "blueFragmentedCases",
                "blue.language.processor.FragmentedProcessingLocalityIntegrationTest");
        Project module = root.findProject(":blue-conformance");
        if (module != null) module.getPluginManager().withPlugin("java", ignored -> {
            sources(module).getByName("test").java(java -> java.srcDir(root.file("src/test-support/java")));
            selected(module, "focusedClosureConformanceTest", "blueClosureCases", "blue.closure.cases",
                    "blue.language.conformance.contracts.closure.DynamicClosureCorpusConformanceTest");
            listing(module, "listClosureConformanceCases", "blueClosureCases",
                    "blue.language.conformance.contracts.closure.ClosureFixtureSelection");
        });
    }

    private static SourceSetContainer sources(Project project) {
        return project.getExtensions().getByType(SourceSetContainer.class);
    }

    private static void selected(Project project, String name, String property, String system, String pattern) {
        project.getTasks().register(name, DevelopmentTest.class, task -> {
            task.setGroup("verification");
            task.setDescription("Runs selected semantic cases using -P" + property + "=ID,prefix*.");
            task.dependsOn("testClasses");
            task.setClasspath(sources(project).getByName("test").getRuntimeClasspath());
            task.setTestClassesDirs(sources(project).getByName("test").getOutput().getClassesDirs());
            task.getFilter().includeTestsMatching(pattern);
            task.systemProperty(system, project.getProviders().gradleProperty(property).orElse("").get());
            task.getTestLogging().setShowStandardStreams(true);
            var evidence = project.getLayout().getBuildDirectory().dir("development-verification/" + name + "/evidence");
            task.getEvidenceDirectory().set(evidence);
            task.systemProperty("blue.semantic.locality.evidence.dir", evidence.get().getAsFile().getAbsolutePath());
            task.systemProperty("blue.contracts.closureDiscrepancyReport", evidence.get().file("closure.json").getAsFile().getAbsolutePath());
            // External package selection is explicit and fingerprinted; never inherit an untracked environment root.
            task.environment("BLUE_CONTRACTS_CLOSURE_PACKAGE_ROOT", "");
            if (project.getProviders().gradleProperty("blueClosurePackageRoot").isPresent()) {
                var external = project.file(project.getProviders().gradleProperty("blueClosurePackageRoot").get());
                task.systemProperty("blue.contracts.closurePackageRoot", external.getAbsolutePath());
                task.getInputs().dir(external).withPropertyName("closurePackage");
            }
        });
    }

    private static void listing(Project project, String name, String property, String main) {
        project.getTasks().register(name, JavaExec.class, task -> {
            task.setGroup("verification");
            task.setDescription("Lists validated case identities; does not execute assertions.");
            task.dependsOn("testClasses");
            task.setClasspath(sources(project).getByName("test").getRuntimeClasspath());
            task.getMainClass().set(main);
            task.setMaxHeapSize("512m");
            task.environment("BLUE_CONTRACTS_CLOSURE_PACKAGE_ROOT", "");
            if (project.getProviders().gradleProperty(property).isPresent()) task.args(project.getProviders().gradleProperty(property).get());
            if (project.getProviders().gradleProperty("blueClosurePackageRoot").isPresent()) {
                task.systemProperty("blue.contracts.closurePackageRoot", project.file(project.getProviders()
                        .gradleProperty("blueClosurePackageRoot").get()).getAbsolutePath());
            }
        });
    }
}
