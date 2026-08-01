package blue.buildlogic;

import blue.buildlogic.tasks.CompareApiBaselineTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

/** Adds the module-local, line-oriented public API baseline comparison task. */
public final class ApiBaselinePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getTasks().register("apiBaselineDiff", CompareApiBaselineTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Compares the generated module API with its checked-in baseline.");
            task.getBaselineFile().convention(project.getLayout().getProjectDirectory()
                    .file("api/public-api.txt"));
            task.getCurrentApiFile().convention(project.getLayout().getBuildDirectory()
                    .file("reports/api/current-api.txt"));
            task.getReportFile().convention(project.getLayout().getBuildDirectory()
                    .file("reports/api/baseline-diff.json"));
        });
    }
}
