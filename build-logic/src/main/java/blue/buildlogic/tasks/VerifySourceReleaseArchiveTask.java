package blue.buildlogic.tasks;

import blue.buildlogic.support.SourceReleaseArchiveVerifier;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Requires the source ZIP to contain exactly the declared source inputs and no debris. */
@CacheableTask
public abstract class VerifySourceReleaseArchiveTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getArchiveFile();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceFiles();

    @Internal
    public abstract DirectoryProperty getSourceRoot();

    @Input
    public abstract Property<String> getRootPrefix();

    @Input
    public abstract Property<String> getGeneratedMetadataEntry();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void verify() {
        Path root = getSourceRoot().get().getAsFile().toPath().toAbsolutePath().normalize();
        String prefix = getRootPrefix().get();
        List<String> expected = new ArrayList<>();
        for (File file : getSourceFiles().getFiles()) {
            if (!file.isFile()) {
                continue;
            }
            Path path = file.toPath().toAbsolutePath().normalize();
            if (!path.startsWith(root)) {
                throw new GradleException("Source-release input is outside repository: " + path);
            }
            expected.add(prefix + "/" + root.relativize(path).toString()
                    .replace(file.toPath().getFileSystem().getSeparator(), "/"));
        }
        expected.add(prefix + "/" + getGeneratedMetadataEntry().get());
        SourceReleaseArchiveVerifier.Result result = SourceReleaseArchiveVerifier.verify(
                getArchiveFile().get().getAsFile().toPath(), expected, prefix);
        write(getReportFile().get().getAsFile().toPath(), result.toJson());
        if (!result.isValid()) {
            throw new GradleException("Source-release archive is invalid: "
                    + String.join(", ", result.getViolations()));
        }
    }

    private static void write(Path output, String value) {
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, value, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot write source-release verification: " + output,
                    exception);
        }
    }
}
