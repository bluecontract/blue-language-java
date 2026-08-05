package blue.buildlogic.tasks;

import blue.buildlogic.support.ReleaseEvidence;
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

/** Generates a canonical receipt binding release inputs to a source commit and timestamp. */
@CacheableTask
public abstract class GenerateReleaseEvidenceTask extends DefaultTask {

    public GenerateReleaseEvidenceTask() {
        getSourceDateEpoch().convention("0");
        getMetadata().convention(Collections.emptyMap());
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
    public abstract MapProperty<String, String> getMetadata();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void generate() {
        List<Path> sourcePaths = getSourceFiles().getFiles().stream()
                .map(java.io.File::toPath)
                .collect(Collectors.toList());
        String evidence = ReleaseEvidence.create(
                getSourceRoot().get().getAsFile().toPath(),
                sourcePaths,
                getSourceCommit().get(),
                getSourceDateEpoch().get(),
                getMetadata().get());
        Path output = getOutputFile().get().getAsFile().toPath();
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, evidence, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot write release evidence: " + output, exception);
        }
    }
}
