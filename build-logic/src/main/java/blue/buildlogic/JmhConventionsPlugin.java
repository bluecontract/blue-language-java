package blue.buildlogic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import me.champeau.jmh.JmhParameters;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;

/** Applies JMH and keeps generated benchmark bytecode compatible with Java 8 consumers. */
public final class JmhConventionsPlugin implements Plugin<Project> {

    public static final String INCLUDES_PROPERTY = "blueJmhIncludes";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("me.champeau.jmh");
        JmhParameters parameters = (JmhParameters) project.getExtensions().getByName("jmh");
        parameters.getIncludes().set(project.getProviders()
                .gradleProperty(INCLUDES_PROPERTY)
                .map(JmhConventionsPlugin::parseIncludes)
                .orElse(Collections.emptyList()));
        project.getTasks().withType(JavaCompile.class)
                .matching(task -> task.getName().toLowerCase(java.util.Locale.ROOT).contains("jmh"))
                .configureEach(task -> {
                    task.getOptions().setEncoding("UTF-8");
                    task.getOptions().getRelease().set(8);
                });
        project.getTasks().withType(Jar.class)
                .matching(task -> task.getName().toLowerCase(java.util.Locale.ROOT).contains("jmh"))
                .configureEach(task -> task.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE));
    }

    /** Parses, validates, and de-duplicates comma-separated JMH include regexes. */
    static List<String> parseIncludes(String rawValue) {
        if (rawValue == null || rawValue.trim().isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> includes = new LinkedHashSet<>();
        for (String rawInclude : rawValue.split(",", -1)) {
            String include = rawInclude.trim();
            if (include.isEmpty()) {
                throw new InvalidUserDataException(
                        "-P" + INCLUDES_PROPERTY + " contains an empty JMH include regex");
            }
            try {
                Pattern.compile(include);
            } catch (PatternSyntaxException exception) {
                throw new InvalidUserDataException(
                        "Invalid -P" + INCLUDES_PROPERTY + " regex '" + include + "'",
                        exception);
            }
            includes.add(include);
        }
        return Collections.unmodifiableList(new ArrayList<>(includes));
    }
}
