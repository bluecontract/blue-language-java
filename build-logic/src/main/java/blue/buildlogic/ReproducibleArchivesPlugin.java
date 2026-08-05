package blue.buildlogic;

import blue.buildlogic.tasks.CompareArchiveReplicasTask;
import blue.buildlogic.tasks.VerifyReproducibleArchivesTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.TaskCollection;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

/** Configures deterministic archives and byte-identical, independently assembled replicas. */
public final class ReproducibleArchivesPlugin implements Plugin<Project> {

    private static final String CHARACTER_ENCODING_UTF_8 = "UTF-8";
    private static final String JAVA_PLUGIN = "java";
    private static final String BASE_PLUGIN = "base";
    private static final String SOURCES_JAR_TASK = "sourcesJar";
    private static final String JAVADOC_JAR_TASK = "javadocJar";

    @Override
    public void apply(Project project) {
        TaskProvider<VerifyReproducibleArchivesTask> verification = project.getTasks().register(
                BuildLogicConstants.TASK_VERIFY_REPRODUCIBLE_ARCHIVES,
                VerifyReproducibleArchivesTask.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription("Verifies deterministic archive ordering and timestamps.");
                });

        TaskProvider<CompareArchiveReplicasTask> comparison = project.getTasks().register(
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
        project.getTasks().withType(Jar.class).configureEach(archive -> {
            archive.setMetadataCharset(CHARACTER_ENCODING_UTF_8);
            archive.setManifestContentCharset(CHARACTER_ENCODING_UTF_8);
        });

        project.getPluginManager().withPlugin(JAVA_PLUGIN, ignored -> {
            JavaPluginExtension java =
                    project.getExtensions().getByType(JavaPluginExtension.class);
            java.withSourcesJar();
            java.withJavadocJar();
            configureReplica(
                    project,
                    comparison,
                    JavaPlugin.JAR_TASK_NAME,
                    BuildLogicConstants.TASK_JAR_REPLICA);
            configureReplica(
                    project,
                    comparison,
                    SOURCES_JAR_TASK,
                    BuildLogicConstants.TASK_SOURCES_JAR_REPLICA);
            configureReplica(
                    project,
                    comparison,
                    JAVADOC_JAR_TASK,
                    BuildLogicConstants.TASK_JAVADOC_JAR_REPLICA);
        });
        project.getPluginManager().withPlugin(BASE_PLUGIN, ignored -> project.getTasks()
                .named(LifecycleBasePlugin.CHECK_TASK_NAME)
                .configure(task -> task.dependsOn(verification, comparison)));
    }

    /** Registers a second Jar task over the reference task's inputs and pairs their outputs. */
    private static void configureReplica(
            Project project,
            TaskProvider<CompareArchiveReplicasTask> comparison,
            String referenceTaskName,
            String replicaTaskName) {
        TaskProvider<Jar> reference =
                project.getTasks().named(referenceTaskName, Jar.class);
        TaskProvider<Jar> replica = project.getTasks().register(
                replicaTaskName,
                Jar.class,
                task -> configureReplicaTask(project, reference.get(), task));
        comparison.configure(task -> {
            task.getReferenceArchives().from(reference.flatMap(Jar::getArchiveFile));
            task.getReplicaArchives().from(replica.flatMap(Jar::getArchiveFile));
            task.dependsOn(reference, replica);
        });
    }

    /** Reuses source specifications, not produced bytes, and writes to an isolated directory. */
    private static void configureReplicaTask(Project project, Jar reference, Jar replica) {
        replica.setGroup(BasePlugin.BUILD_GROUP);
        replica.setDescription("Independently assembles a byte-comparison replica of "
                + reference.getName() + ".");
        replica.getArchiveFileName().set(reference.getArchiveFileName());
        replica.getDestinationDirectory().set(project.getLayout().getBuildDirectory()
                .dir(BuildLogicConstants.DIRECTORY_ARCHIVE_REPLICAS));
        replica.with(reference);
        replica.getManifest().from(reference.getManifest());
        replica.setDuplicatesStrategy(reference.getDuplicatesStrategy());
        replica.setEntryCompression(reference.getEntryCompression());
        replica.setZip64(reference.isZip64());
    }
}
