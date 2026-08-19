package blue.buildlogic;

import blue.buildlogic.tasks.VerifyBlueSpecInputsTask;
import java.io.File;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

/** Resolves and verifies the separately versioned canonical Blue specification tree. */
final class BlueSpecInputs {

    static final String ROOT_PROPERTY = "blueSpecRoot";
    static final String ROOT_ENVIRONMENT = "BLUE_SPEC_ROOT";
    static final String DEFAULT_ROOT = "../blue-spec/latest";
    static final String ROOT_PROVIDER_EXTRA_PROPERTY =
            "blueSpecRootDirectoryProvider";

    private BlueSpecInputs() {}

    static Registration register(Project project) {
        Provider<String> configuredRoot = project.getProviders()
                .gradleProperty(ROOT_PROPERTY)
                .orElse(project.getProviders().environmentVariable(ROOT_ENVIRONMENT))
                .orElse(DEFAULT_ROOT);
        Provider<Directory> root = project.getLayout().dir(
                configuredRoot.map(project::file));
        RegularFile lock = project.getLayout().getProjectDirectory()
                .file("gradle/blue-spec-inputs.lock.json");
        TaskProvider<VerifyBlueSpecInputsTask> verification =
                project.getTasks().register(
                        BuildLogicConstants.TASK_VERIFY_BLUE_SPEC_INPUTS,
                        VerifyBlueSpecInputsTask.class,
                        task -> {
                            task.setGroup(BuildLogicConstants.VERIFICATION_GROUP);
                            task.setDescription(
                                    "Verifies the canonical external Blue specification inputs.");
                            task.getSpecRoot().set(root);
                            task.getLockFile().set(lock);
                        });
        project.getExtensions().getExtraProperties().set(
                ROOT_PROVIDER_EXTRA_PROPERTY, root);
        return new Registration(root, verification);
    }

    /** Typed providers shared by root orchestration tasks. */
    static final class Registration {
        private final Provider<Directory> root;
        private final TaskProvider<VerifyBlueSpecInputsTask> verification;

        private Registration(
                Provider<Directory> root,
                TaskProvider<VerifyBlueSpecInputsTask> verification) {
            this.root = root;
            this.verification = verification;
        }

        Provider<Directory> root() {
            return root;
        }

        TaskProvider<VerifyBlueSpecInputsTask> verification() {
            return verification;
        }

        File file(String relativePath) {
            return root.get().file(relativePath).getAsFile();
        }
    }
}
