package blue.buildlogic;

import blue.buildlogic.tasks.GenerateFileIdentityTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;

/** Provides deterministic fixture/package identity generation for conformance modules. */
public final class ConformancePackagePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        ConfigurableFileTree packageInputs = project.fileTree(project.getProjectDir());
        packageInputs.include("src/main/resources/**", "src/test/resources/**", "fixtures/**");
        packageInputs.exclude("**/.DS_Store", "**/._*");

        project.getTasks().register(
                "generateConformancePackageIdentity", GenerateFileIdentityTask.class, task -> {
                    task.setGroup("verification");
                    task.setDescription("Generates the deterministic conformance package identity.");
                    task.getInputFiles().from(packageInputs);
                    task.getRootDirectory().set(project.getLayout().getProjectDirectory());
                    task.getOutputFile().set(project.getLayout().getBuildDirectory()
                            .file("reports/conformance/package-identity.json"));
                });

        project.getPluginManager().withPlugin("java", ignored -> {
            SourceSetContainer sourceSets =
                    project.getExtensions().getByType(SourceSetContainer.class);
            JavaToolchainService toolchains =
                    project.getExtensions().getByType(JavaToolchainService.class);
            project.getTasks().register("releaseConformanceTest", JavaExec.class, task -> {
                task.setGroup(BuildLogicConstants.VERIFICATION_GROUP);
                task.setDescription(
                        "Runs the exact 153 Language and 154 Contracts release fixtures.");
                task.dependsOn(project.getTasks().named(JavaPlugin.CLASSES_TASK_NAME));
                task.setClasspath(sourceSets.getByName("main").getRuntimeClasspath());
                task.getMainClass().set(
                        "blue.language.conformance.cli.ReleaseConformanceCli");
                task.getJavaLauncher().set(toolchains.launcherFor(spec -> spec
                        .getLanguageVersion().set(JavaLanguageVersion.of(8))));
                task.args(
                        project.getLayout().getBuildDirectory().file(
                                "reports/conformance/release-conformance.json")
                                .get().getAsFile().getAbsolutePath(),
                        project.getLayout().getBuildDirectory().file(
                                "reports/conformance/release-conformance.txt")
                                .get().getAsFile().getAbsolutePath());
                task.getInputs().files(packageInputs);
                task.getOutputs().files(
                        project.getLayout().getBuildDirectory().file(
                                "reports/conformance/release-conformance.json"),
                        project.getLayout().getBuildDirectory().file(
                                "reports/conformance/release-conformance.txt"));
            });
        });
    }
}
