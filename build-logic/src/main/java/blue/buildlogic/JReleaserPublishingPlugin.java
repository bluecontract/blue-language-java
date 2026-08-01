package blue.buildlogic;

import blue.buildlogic.tasks.VerifyReleaseEnvironmentTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

/** Applies JReleaser and guards every publication entry point with release validation. */
public final class JReleaserPublishingPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("org.jreleaser");
        TaskProvider<VerifyReleaseEnvironmentTask> verification = project.getTasks().register(
                "verifyReleaseEnvironment", VerifyReleaseEnvironmentTask.class, task -> {
                    task.setGroup("verification");
                    task.setDescription("Validates release channel, version, and SOURCE_DATE_EPOCH.");
                    task.getVersionValue().convention(project.provider(
                            () -> project.getVersion().toString()));
                    task.getReleaseChannel().convention(project.getProviders()
                            .environmentVariable("BLUE_RELEASE_CHANNEL"));
                    task.getSourceDateEpoch().convention(project.getProviders()
                            .environmentVariable("SOURCE_DATE_EPOCH").orElse("0"));
                });
        project.getTasks().matching(task -> task.getName().startsWith("jreleaser"))
                .configureEach(task -> task.dependsOn(verification));
    }
}
