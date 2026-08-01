package blue.buildlogic;

import blue.buildlogic.tasks.GenerateFileIdentityTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileTree;

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
    }
}
