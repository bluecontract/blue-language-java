package blue.buildlogic.tasks;

import blue.buildlogic.support.CleanBuildEvidence;
import blue.buildlogic.support.DeterministicHashing;
import blue.buildlogic.support.SourceDateEpoch;
import blue.buildlogic.support.SourceSnapshot;
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
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Writes a success marker only after an exclusion-free clean and successful build. */
@DisableCachingByDefault(because = "The task invalidates evidence for ordinary non-clean builds")
public abstract class GenerateCleanBuildEvidenceTask extends DefaultTask {

    public GenerateCleanBuildEvidenceTask() {
        getInvocationTasks().convention(Collections.emptyList());
        getExcludedTasks().convention(Collections.emptyList());
        getCleanTaskExecuted().convention(false);
        getBuildTaskSuccessful().convention(false);
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceFiles();

    @Internal
    public abstract DirectoryProperty getSourceRoot();

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getCleanSourceEvidenceFile();

    @Input
    public abstract Property<String> getSourceCommit();

    @Input
    public abstract Property<String> getSourceDateEpoch();

    @Input
    public abstract Property<String> getCleanTaskPath();

    @Input
    public abstract Property<String> getBuildTaskPath();

    @Input
    public abstract ListProperty<String> getInvocationTasks();

    @Input
    public abstract ListProperty<String> getExcludedTasks();

    @Internal
    public abstract Property<Boolean> getCleanTaskExecuted();

    @Internal
    public abstract Property<Boolean> getBuildTaskSuccessful();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void generate() {
        Path output = getOutputFile().get().getAsFile().toPath();
        delete(output);
        if (!getCleanTaskExecuted().get()
                || !getBuildTaskSuccessful().get()
                || !getExcludedTasks().get().isEmpty()) {
            return;
        }
        Path cleanSource = getCleanSourceEvidenceFile().get().getAsFile().toPath();
        if (!Files.isRegularFile(cleanSource)) {
            throw new GradleException("Clean build has no clean-source input marker");
        }
        CleanBuildEvidence.Marker marker;
        try {
            marker = CleanBuildEvidence.parse(Files.readString(cleanSource, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new GradleException("Cannot read clean-source input marker", exception);
        }
        SourceSnapshot current = DeterministicHashing.snapshot(
                getSourceRoot().get().getAsFile().toPath(), paths());
        String epoch = SourceDateEpoch.normalize(getSourceDateEpoch().get());
        if (!CleanBuildEvidence.CLEAN_SOURCE_SCHEMA.equals(marker.getSchema())
                || !getCleanTaskPath().get().equals(marker.getCleanTask())
                || !getSourceCommit().get().trim().equals(marker.getSourceCommit())
                || !epoch.equals(marker.getSourceDateEpoch())
                || !marker.getExcludedTasks().isEmpty()
                || !current.getIdentity().equals(marker.getSourceInputIdentity())
                || current.getEntries().size() != marker.getSourceFileCount()) {
            throw new GradleException(
                    "Source inputs changed between clean and successful build completion");
        }
        String evidence = CleanBuildEvidence.createCleanBuild(
                getSourceRoot().get().getAsFile().toPath(),
                paths(),
                getSourceCommit().get(),
                epoch,
                getCleanTaskPath().get(),
                getBuildTaskPath().get(),
                getInvocationTasks().get(),
                getExcludedTasks().get());
        write(output, evidence);
    }

    private List<Path> paths() {
        return getSourceFiles().getFiles().stream()
                .map(File::toPath)
                .collect(Collectors.toList());
    }

    private static void delete(Path output) {
        try {
            Files.deleteIfExists(output);
        } catch (IOException exception) {
            throw new GradleException("Cannot invalidate clean-build evidence: " + output, exception);
        }
    }

    private static void write(Path output, String value) {
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, value, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot write clean-build evidence: " + output, exception);
        }
    }
}
