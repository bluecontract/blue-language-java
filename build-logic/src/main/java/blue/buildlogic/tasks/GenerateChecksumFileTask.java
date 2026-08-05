package blue.buildlogic.tasks;

import blue.buildlogic.support.DeterministicHashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Writes a conventional lowercase SHA-256 checksum sidecar for one file. */
@CacheableTask
public abstract class GenerateChecksumFileTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract RegularFileProperty getInputFile();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void generate() {
        Path input = getInputFile().get().getAsFile().toPath();
        Path output = getOutputFile().get().getAsFile().toPath();
        String identity = DeterministicHashing.sha256(input);
        String checksum = identity.substring("sha256:".length())
                + "  " + input.getFileName() + "\n";
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, checksum, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot write SHA-256 checksum: " + output, exception);
        }
    }
}
