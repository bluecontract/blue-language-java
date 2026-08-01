package blue.buildlogic.tasks;

import blue.buildlogic.support.AggregateReleaseReceipt;
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
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Generates one deterministic receipt for release artifacts, tests, fixtures, and API evidence. */
@CacheableTask
public abstract class GenerateAggregateReleaseReceiptTask extends DefaultTask {

    public GenerateAggregateReleaseReceiptTask() {
        getSourceDateEpoch().convention("0");
        getMetadata().convention(Collections.emptyMap());
    }

    @Internal
    public abstract DirectoryProperty getReceiptRoot();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getArtifacts();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getTestEvidence();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getFixtureEvidence();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getApiEvidence();

    @Input
    public abstract Property<String> getSourceCommit();

    @Input
    public abstract Property<String> getSourceDateEpoch();

    @Input
    public abstract MapProperty<String, String> getMetadata();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void generate() {
        String receipt = AggregateReleaseReceipt.create(
                getReceiptRoot().get().getAsFile().toPath(),
                paths(getArtifacts()),
                paths(getTestEvidence()),
                paths(getFixtureEvidence()),
                paths(getApiEvidence()),
                getSourceCommit().get(),
                getSourceDateEpoch().get(),
                getMetadata().get());
        write(getOutputFile().get().getAsFile().toPath(), receipt);
    }

    private static List<Path> paths(ConfigurableFileCollection files) {
        return files.getFiles().stream().map(File::toPath).collect(Collectors.toList());
    }

    private static void write(Path output, String value) {
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, value, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot write aggregate release receipt: " + output, exception);
        }
    }
}
