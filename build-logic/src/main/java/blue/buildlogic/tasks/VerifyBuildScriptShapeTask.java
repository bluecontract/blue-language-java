package blue.buildlogic.tasks;

import blue.buildlogic.support.DeterministicJson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Enforces the declarative line budgets and conventional source layout of Gradle scripts. */
@CacheableTask
public abstract class VerifyBuildScriptShapeTask extends DefaultTask {

    public VerifyBuildScriptShapeTask() {
        getRootLineLimit().convention(200);
        getModuleLineLimit().convention(150);
        getAbsoluteLineLimit().convention(999);
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getBuildScripts();

    @Internal
    public abstract DirectoryProperty getRepositoryRoot();

    @Input
    public abstract Property<Integer> getRootLineLimit();

    @Input
    public abstract Property<Integer> getModuleLineLimit();

    @Input
    public abstract Property<Integer> getAbsoluteLineLimit();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void verify() {
        Path root = getRepositoryRoot().get().getAsFile().toPath().toAbsolutePath().normalize();
        List<File> scripts = new ArrayList<>(getBuildScripts().getFiles());
        scripts.sort(Comparator.comparing(file -> relative(root, file.toPath())));
        List<Map<String, Object>> records = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        for (File script : scripts) {
            String path = relative(root, script.toPath());
            int lines = lines(script.toPath());
            int limit = limit(path);
            Map<String, Object> record = new TreeMap<>();
            record.put("lineCount", lines);
            record.put("lineLimit", limit);
            record.put("path", path);
            records.add(record);
            if (lines > limit || lines > getAbsoluteLineLimit().get()) {
                violations.add(path + " has " + lines + " lines (limit " + limit + ")");
            }
            if (!path.equals("build.gradle") && !path.startsWith("build-logic/")
                    && redirectsToRootSources(script.toPath())) {
                violations.add(path + " redirects a module source set to root src/**");
            }
        }
        Map<String, Object> report = new TreeMap<>();
        report.put("schema", "blue-build-script-shape/1.0");
        report.put("scripts", records);
        report.put("valid", violations.isEmpty());
        report.put("violations", violations);
        write(DeterministicJson.write(report));
        if (!violations.isEmpty()) {
            throw new GradleException("Invalid Gradle script shape: " + String.join("; ", violations));
        }
    }

    private int limit(String path) {
        if (path.equals("build.gradle") || path.equals("build.gradle.kts")) {
            return getRootLineLimit().get();
        }
        if (path.startsWith("build-logic/")) {
            return getAbsoluteLineLimit().get();
        }
        return getModuleLineLimit().get();
    }

    private static boolean redirectsToRootSources(Path script) {
        try {
            String value = Files.readString(script, StandardCharsets.UTF_8)
                    .replace('\\', '/');
            return value.contains("../src/main") || value.contains("rootProject.file('src/")
                    || value.contains("rootProject.file(\"src/");
        } catch (IOException exception) {
            throw new GradleException("Cannot read build script " + script, exception);
        }
    }

    private static int lines(Path path) {
        try (java.util.stream.Stream<String> stream = Files.lines(path, StandardCharsets.UTF_8)) {
            return (int) stream.count();
        } catch (IOException exception) {
            throw new GradleException("Cannot count build script lines in " + path, exception);
        }
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace(File.separatorChar, '/');
    }

    private void write(String value) {
        Path output = getReportFile().get().getAsFile().toPath();
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, value, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot write build script shape report " + output, exception);
        }
    }
}
