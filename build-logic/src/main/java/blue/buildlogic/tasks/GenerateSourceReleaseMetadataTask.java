package blue.buildlogic.tasks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

/** Copies .cz.toml for an archive while replacing only its version declaration. */
@CacheableTask
public abstract class GenerateSourceReleaseMetadataTask extends DefaultTask {

    private static final Pattern VERSION =
            Pattern.compile("(?m)^version\\s*=\\s*\"[^\"]+\"\\s*$");

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getSourceFile();

    @Input
    public abstract Property<String> getReleaseVersion();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void generate() {
        Path source = getSourceFile().get().getAsFile().toPath();
        Path output = getOutputFile().get().getAsFile().toPath();
        try {
            String value = Files.readString(source, StandardCharsets.UTF_8);
            Matcher matcher = VERSION.matcher(value);
            if (!matcher.find()) {
                throw new GradleException(".cz.toml has no version declaration");
            }
            String replacement = "version = \"" + getReleaseVersion().get() + "\"";
            String updated = matcher.replaceFirst(Matcher.quoteReplacement(replacement));
            if (VERSION.matcher(updated).results().count() != 1L) {
                throw new GradleException(".cz.toml must contain exactly one version declaration");
            }
            Files.createDirectories(output.getParent());
            Files.writeString(output, updated, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot generate source-release .cz.toml", exception);
        }
    }
}
