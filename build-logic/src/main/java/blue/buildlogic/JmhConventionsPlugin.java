package blue.buildlogic;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.compile.JavaCompile;

/** Applies JMH and keeps generated benchmark bytecode compatible with Java 8 consumers. */
public final class JmhConventionsPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("me.champeau.jmh");
        project.getTasks().withType(JavaCompile.class)
                .matching(task -> task.getName().toLowerCase(java.util.Locale.ROOT).contains("jmh"))
                .configureEach(task -> {
                    task.getOptions().setEncoding("UTF-8");
                    task.getOptions().getRelease().set(8);
                });
    }
}
