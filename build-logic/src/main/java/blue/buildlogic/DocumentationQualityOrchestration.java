package blue.buildlogic;

import blue.buildlogic.tasks.GenerateDocumentationReferencesTask;
import blue.buildlogic.tasks.GenerateDocumentationVerificationReportTask;
import blue.buildlogic.tasks.GenerateJavaApiInventoryTask;
import blue.buildlogic.tasks.VerifyDocumentationReportTask;
import blue.buildlogic.tasks.VerifyJavaModuleStructureTask;
import java.util.List;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.TaskProvider;

/** Owns generated references and documentation quality without bloating the root build script. */
final class DocumentationQualityOrchestration {

    private DocumentationQualityOrchestration() {}

    static Tasks register(
            Project project,
            List<String> publishedModules,
            TaskProvider<GenerateJavaApiInventoryTask> apiUnion,
            TaskProvider<VerifyJavaModuleStructureTask> moduleStructure) {
        ConfigurableFileTree apiInventories = project.fileTree(project.getRootDir(), tree ->
                tree.include("blue-*/build/reports/api/current-api.txt"));
        ConfigurableFileTree productionSources = project.fileTree(project.getRootDir(), tree -> {
            for (String module : publishedModules) {
                tree.include(module + "/src/main/java/**/*.java");
            }
        });
        ConfigurableFileTree documentationInputs = project.fileTree(project.getRootDir(), tree ->
                tree.include(
                        "README.md",
                        "CONTRIBUTING.md",
                        "ARCHITECTURE.md",
                        "build.gradle",
                        "docs/**/*.md",
                        "api/**/*.json",
                        "architecture/**/*.json"));
        ConfigurableFileTree exampleSources = project.fileTree(project.getRootDir(), tree ->
                tree.include("examples/src/main/java/**/*.java"));
        ConfigurableFileTree exampleTests = project.fileTree(project.getRootDir(), tree ->
                tree.include("examples/src/test/java/**/*.java"));

        org.gradle.api.provider.Provider<org.gradle.api.file.RegularFile> conformanceReport =
                project.project(":blue-conformance").getLayout().getBuildDirectory()
                        .file("reports/conformance/release-conformance.json");
        TaskProvider<GenerateDocumentationReferencesTask> references = project.getTasks().register(
                BuildLogicConstants.TASK_GENERATE_DOCUMENTATION_REFERENCES,
                GenerateDocumentationReferencesTask.class,
                task -> {
                    task.setGroup("documentation");
                    task.setDescription(
                            "Generates API, package, SPI, Contracts, metric, fixture, and module references.");
                    task.getRepositoryRoot().set(project.getLayout().getProjectDirectory());
                    task.getApiInventories().from(apiInventories);
                    task.getProductionSources().from(productionSources);
                    task.getGasManifest().set(project.getLayout().getProjectDirectory().file(
                            "blue-contracts-core/src/main/resources/blue/language/processor/"
                                    + "contracts-gas-1.0.yaml"));
                    task.getReleaseConformanceReport().set(conformanceReport);
                    task.getModuleStructureReport().set(moduleStructure.flatMap(
                            VerifyJavaModuleStructureTask::getReportFile));
                    task.getOutputDirectory().set(project.getLayout().getBuildDirectory()
                            .dir(BuildLogicConstants.DIRECTORY_GENERATED_DOCUMENTATION));
                    task.dependsOn(
                            apiUnion,
                            moduleStructure,
                            project.project(":blue-conformance").getTasks().named(
                                    "releaseConformanceTest"));
                });

        TaskProvider<Copy> updateReferences = project.getTasks().register(
                BuildLogicConstants.TASK_UPDATE_DOCUMENTATION_REFERENCES,
                Copy.class,
                task -> {
                    task.setGroup("documentation");
                    task.setDescription(
                            "Copies deterministic generated references into tracked docs/ paths.");
                    task.from(references.flatMap(
                            GenerateDocumentationReferencesTask::getOutputDirectory));
                    task.into(project.getLayout().getProjectDirectory().dir("docs"));
                    task.dependsOn(references);
                });

        TaskProvider<GenerateDocumentationVerificationReportTask> analysis =
                project.getTasks().register(
                        BuildLogicConstants.TASK_GENERATE_DOCUMENTATION_REPORT,
                        GenerateDocumentationVerificationReportTask.class,
                        task -> {
                            task.setGroup("documentation");
                            task.setDescription(
                                    "Analyzes required docs, links, snippets, identities, terminology, and drift.");
                            task.getRepositoryRoot().set(project.getLayout().getProjectDirectory());
                            task.getDocumentationFiles().from(documentationInputs);
                            task.getGeneratedDocumentationDirectory().set(references.flatMap(
                                    GenerateDocumentationReferencesTask::getOutputDirectory));
                            task.getProductionSources().from(productionSources);
                            task.getExampleSources().from(exampleSources);
                            task.getExampleTests().from(exampleTests);
                            task.getReleaseConformanceReport().set(conformanceReport);
                            task.getLanguageSpecification().set(project.getLayout()
                                    .getProjectDirectory().file(
                                            "blue-conformance/src/main/resources/language/1.0/spec.md"));
                            task.getContractsSpecification().set(project.getLayout()
                                    .getProjectDirectory().file(
                                            "blue-conformance/src/main/resources/contract/1.0/spec.md"));
                            task.getRelocationLedger().set(project.getLayout()
                                    .getProjectDirectory().file(
                                            "api/module-api-relocation-ledger-1.0.json"));
                            task.getReportFile().set(project.getLayout().getBuildDirectory()
                                    .file(BuildLogicConstants.REPORT_DOCUMENTATION_ANALYSIS));
                            task.dependsOn(references);
                        });

        TaskProvider<Task> allJavadocs = project.getTasks().register(
                "allJavadocs", task -> {
                    task.setGroup("documentation");
                    task.setDescription("Generates Javadocs for every published Java module.");
                });
        project.getGradle().projectsEvaluated(ignored -> {
            for (String module : publishedModules) {
                allJavadocs.configure(task -> task.dependsOn(
                        project.project(":" + module).getTasks().named("javadoc")));
            }
        });

        TaskProvider<VerifyDocumentationReportTask> verify = project.getTasks().register(
                BuildLogicConstants.TASK_DOCUMENTATION_VERIFY,
                VerifyDocumentationReportTask.class,
                task -> {
                    task.setGroup(BuildLogicConstants.VERIFICATION_GROUP);
                    task.setDescription(
                            "Fails on stale, broken, uncompilable, or incomplete documentation.");
                    task.getAnalysisFile().set(analysis.flatMap(
                            GenerateDocumentationVerificationReportTask::getReportFile));
                    task.getVerificationFile().set(project.getLayout().getBuildDirectory()
                            .file(BuildLogicConstants.REPORT_DOCUMENTATION_VERIFICATION));
                    task.dependsOn(
                            analysis,
                            allJavadocs,
                            project.project(":examples").getTasks().named("check"));
                });
        return new Tasks(references, updateReferences, analysis, verify, allJavadocs);
    }

    /** Providers used by the final quality orchestration. */
    static final class Tasks {
        final TaskProvider<GenerateDocumentationReferencesTask> references;
        final TaskProvider<Copy> updateReferences;
        final TaskProvider<GenerateDocumentationVerificationReportTask> analysis;
        final TaskProvider<VerifyDocumentationReportTask> verification;
        final TaskProvider<Task> allJavadocs;

        private Tasks(
                TaskProvider<GenerateDocumentationReferencesTask> references,
                TaskProvider<Copy> updateReferences,
                TaskProvider<GenerateDocumentationVerificationReportTask> analysis,
                TaskProvider<VerifyDocumentationReportTask> verification,
                TaskProvider<Task> allJavadocs) {
            this.references = references;
            this.updateReferences = updateReferences;
            this.analysis = analysis;
            this.verification = verification;
            this.allJavadocs = allJavadocs;
        }
    }
}
