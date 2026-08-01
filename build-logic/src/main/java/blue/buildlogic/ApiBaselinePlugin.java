package blue.buildlogic;

import blue.buildlogic.tasks.CompareApiBaselineTask;
import blue.buildlogic.tasks.GenerateJavaApiInventoryTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

/** Adds the module-local, line-oriented public API baseline comparison task. */
public final class ApiBaselinePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        TaskProvider<GenerateJavaApiInventoryTask> moduleInventory = project.getTasks().register(
                BuildLogicConstants.TASK_GENERATE_PUBLIC_API_INVENTORY,
                GenerateJavaApiInventoryTask.class,
                task -> {
                    task.setGroup(BuildLogicConstants.VERIFICATION_GROUP);
                    task.setDescription("Inventories the module's compiled public Java API.");
                    task.getModuleName().convention(project.getName());
                    task.getOutputFile().convention(project.getLayout().getBuildDirectory()
                            .file(BuildLogicConstants.REPORT_API_CURRENT));
                });
        project.getPluginManager().withPlugin("java", ignored -> {
            SourceSetContainer sourceSets =
                    project.getExtensions().getByType(SourceSetContainer.class);
            moduleInventory.configure(task -> {
                task.getCompiledInputs().from(
                        sourceSets.getByName("main").getOutput().getClassesDirs());
                task.dependsOn(project.getTasks().named("classes"));
            });
        });

        project.getTasks().register(
                BuildLogicConstants.TASK_GENERATE_PUBLIC_API_UNION,
                GenerateJavaApiInventoryTask.class,
                task -> {
                    task.setGroup(BuildLogicConstants.VERIFICATION_GROUP);
                    task.setDescription("Unions configured module API inventories deterministically.");
                    task.getModuleName().convention(project.getName() + "-union");
                    task.getUnionInputs().from(moduleInventory.flatMap(
                            GenerateJavaApiInventoryTask::getOutputFile));
                    task.getOutputFile().convention(project.getLayout().getBuildDirectory()
                            .file(BuildLogicConstants.REPORT_API_UNION));
                    task.dependsOn(moduleInventory);
                });

        project.getTasks().register(
                BuildLogicConstants.TASK_API_BASELINE_DIFF,
                CompareApiBaselineTask.class,
                task -> {
                    task.setGroup(BuildLogicConstants.VERIFICATION_GROUP);
                    task.setDescription(
                            "Compares the generated module API with its checked-in baseline.");
                    task.getBaselineFile().convention(project.getLayout().getProjectDirectory()
                            .file("api/public-api.txt"));
                    task.getCurrentApiFile().convention(moduleInventory.flatMap(
                            GenerateJavaApiInventoryTask::getOutputFile));
                    task.getReportFile().convention(project.getLayout().getBuildDirectory()
                            .file(BuildLogicConstants.REPORT_API_BASELINE_DIFF));
                    task.dependsOn(moduleInventory);
                });
    }
}
