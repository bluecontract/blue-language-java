package blue.buildlogic.tasks;

import blue.buildlogic.support.ArchiveReplicaComparison;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Compares two independently built archive sets byte for byte and writes a hash receipt. */
@CacheableTask
public abstract class CompareArchiveReplicasTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract ConfigurableFileCollection getReferenceArchives();

    @InputFiles
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract ConfigurableFileCollection getReplicaArchives();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void compare() {
        ArchiveReplicaComparison.Result result = ArchiveReplicaComparison.compare(
                paths(getReferenceArchives()), paths(getReplicaArchives()));
        write(result.toJson());
        if (!result.isIdentical()) {
            throw new GradleException(
                    "Archive replicas differ byte for byte; see " + getReportFile().get().getAsFile());
        }
    }

    private void write(String report) {
        Path output = getReportFile().get().getAsFile().toPath();
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, report, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot write archive replica report: " + output, exception);
        }
    }

    private static List<Path> paths(ConfigurableFileCollection files) {
        return files.getFiles().stream().map(File::toPath).collect(Collectors.toList());
    }
}
