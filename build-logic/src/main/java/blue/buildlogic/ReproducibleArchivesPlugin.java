package blue.buildlogic;

import blue.buildlogic.tasks.CompareArchiveReplicasTask;
import blue.buildlogic.tasks.VerifyReproducibleArchivesTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskCollection;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.api.tasks.TaskProvider;

/** Configures deterministic archives and exposes one structural verification task. */
public final class ReproducibleArchivesPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        TaskProvider<VerifyReproducibleArchivesTask> verification = project.getTasks().register(
                "verifyReproducibleArchives",
                VerifyReproducibleArchivesTask.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription("Verifies deterministic archive ordering and timestamps.");
                });

        project.getTasks().register(
                BuildLogicConstants.TASK_COMPARE_ARCHIVE_REPLICAS,
                CompareArchiveReplicasTask.class,
                task -> {
                    task.setGroup(BuildLogicConstants.VERIFICATION_GROUP);
                    task.setDescription(
                            "Compares configured independent archive replicas byte for byte.");
                    task.getReportFile().convention(project.getLayout().getBuildDirectory()
                            .file(BuildLogicConstants.REPORT_ARCHIVE_REPLICAS));
                });

        TaskCollection<AbstractArchiveTask> archives =
                project.getTasks().withType(AbstractArchiveTask.class);
        verification.configure(task -> {
            task.getArchives().from(archives);
            task.dependsOn(archives);
        });
        archives.configureEach(archive -> {
            archive.setPreserveFileTimestamps(false);
            archive.setReproducibleFileOrder(true);
        });
    }
}
