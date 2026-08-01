package blue.buildlogic.tasks;

import blue.buildlogic.support.ApiDiff;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Compares two line-oriented API descriptions and emits a deterministic diff report. */
@CacheableTask
public abstract class CompareApiBaselineTask extends DefaultTask {

    public CompareApiBaselineTask() {
        getFailOnRemoval().convention(true);
    }

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getBaselineFile();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getCurrentApiFile();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @Input
    public abstract Property<Boolean> getFailOnRemoval();

    @TaskAction
    public void compare() {
        ApiDiff diff = ApiDiff.compare(read(getBaselineFile()), read(getCurrentApiFile()));
        Path report = getReportFile().get().getAsFile().toPath();
        try {
            Files.createDirectories(report.getParent());
            Files.writeString(report, diff.toJson(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot write API baseline report: " + report, exception);
        }
        if (getFailOnRemoval().get() && !diff.getRemoved().isEmpty()) {
            throw new GradleException(
                    "Public API baseline has " + diff.getRemoved().size() + " removed entries; see "
                            + report);
        }
    }

    private static List<String> read(RegularFileProperty property) {
        Path path = property.get().getAsFile().toPath();
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot read API description: " + path, exception);
        }
    }
}
