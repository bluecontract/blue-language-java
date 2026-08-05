package blue.buildlogic.tasks;

import blue.buildlogic.support.CleanBuildEvidence;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Captures the exact source tree after the clean task in the first invocation. */
@DisableCachingByDefault(because = "Invocation metadata must always be captured")
public abstract class GenerateCleanSourceEvidenceTask extends DefaultTask {

    public GenerateCleanSourceEvidenceTask() {
        getInvocationTasks().convention(Collections.emptyList());
        getExcludedTasks().convention(Collections.emptyList());
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceFiles();

    @Internal
    public abstract DirectoryProperty getSourceRoot();

    @Input
    public abstract Property<String> getSourceCommit();

    @Input
    public abstract Property<String> getSourceDateEpoch();

    @Input
    public abstract Property<String> getCleanTaskPath();

    @Input
    public abstract ListProperty<String> getInvocationTasks();

    @Input
    public abstract ListProperty<String> getExcludedTasks();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void generate() {
        String evidence = CleanBuildEvidence.createCleanSource(
                getSourceRoot().get().getAsFile().toPath(),
                paths(),
                getSourceCommit().get(),
                getSourceDateEpoch().get(),
                getCleanTaskPath().get(),
                getInvocationTasks().get(),
                getExcludedTasks().get());
        write(getOutputFile().get().getAsFile().toPath(), evidence);
    }

    private List<Path> paths() {
        return getSourceFiles().getFiles().stream()
                .map(File::toPath)
                .collect(Collectors.toList());
    }

    private static void write(Path output, String value) {
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, value, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot write clean-source evidence: " + output, exception);
        }
    }
}
