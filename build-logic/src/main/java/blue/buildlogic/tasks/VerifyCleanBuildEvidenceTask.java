package blue.buildlogic.tasks;

import blue.buildlogic.support.CleanBuildEvidence;
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
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Checks that the prior clean-build invocation still describes the exact current source. */
@DisableCachingByDefault(because = "Verification must always compare current source state")
public abstract class VerifyCleanBuildEvidenceTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceFiles();

    @Internal
    public abstract DirectoryProperty getSourceRoot();

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getEvidenceFile();

    @Input
    public abstract Property<String> getSourceCommit();

    @Input
    public abstract Property<String> getSourceDateEpoch();

    @Input
    public abstract Property<String> getCleanTaskPath();

    @Input
    public abstract Property<String> getBuildTaskPath();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void verify() {
        CleanBuildEvidence.Verification result = CleanBuildEvidence.verify(
                getEvidenceFile().get().getAsFile().toPath(),
                getSourceRoot().get().getAsFile().toPath(),
                paths(),
                getSourceCommit().get(),
                getSourceDateEpoch().get(),
                getCleanTaskPath().get(),
                getBuildTaskPath().get());
        write(getReportFile().get().getAsFile().toPath(), result.getReport());
        if (!result.isVerified()) {
            throw new GradleException(
                    "Clean-build evidence is not current: " + result.getReason());
        }
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
            throw new GradleException("Cannot write clean-build verification: " + output, exception);
        }
    }
}
