package blue.buildlogic;

import blue.buildlogic.tasks.GenerateJavaModuleInventoryTask;
import blue.buildlogic.tasks.VerifyJavaPackageCyclesTask;
import org.gradle.api.JavaVersion;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

/** Shared Java 8 bytecode, source/Javadoc artifact, encoding, and repository conventions. */
public final class Java8LibraryConventionsPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaLibraryPlugin.class);

        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.setSourceCompatibility(JavaVersion.VERSION_1_8);
        java.setTargetCompatibility(JavaVersion.VERSION_1_8);
        java.withSourcesJar();
        java.withJavadocJar();

        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            task.getOptions().setEncoding("UTF-8");
            task.getOptions().getRelease().set(8);
        });

        if (System.getenv("CI") == null) {
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
}
