package blue.buildlogic.tasks;

import blue.buildlogic.support.DocumentationReferences;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Generates checked-reference candidates from API, source, fixture, gas, and module evidence. */
@CacheableTask
public abstract class GenerateDocumentationReferencesTask extends DefaultTask {

    @Internal
    public abstract DirectoryProperty getRepositoryRoot();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getApiInventories();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getProductionSources();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getGasManifest();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getReleaseConformanceReport();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getModuleStructureReport();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @TaskAction
    public void generate() {
        Path root = getRepositoryRoot().get().getAsFile().toPath();
        Map<String, String> rendered = DocumentationReferences.render(
                root,
                paths(getApiInventories()),
                paths(getProductionSources()),
                getGasManifest().get().getAsFile().toPath(),
                getReleaseConformanceReport().get().getAsFile().toPath(),
                getModuleStructureReport().get().getAsFile().toPath());
        Path output = getOutputDirectory().get().getAsFile().toPath();
        clear(output);
        for (Map.Entry<String, String> entry : rendered.entrySet()) {
            Path target = output.resolve(entry.getKey());
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new GradleException("Cannot write generated documentation " + target, exception);
            }
        }
    }

    private static List<Path> paths(ConfigurableFileCollection files) {
        List<Path> paths = new ArrayList<>();
        for (File file : files.getFiles()) {
            paths.add(file.toPath());
        }
        return paths;
    }

    private static void clear(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new GradleException(
                            "Cannot clear generated documentation output " + path, exception);
                }
            });
        } catch (IOException exception) {
            throw new GradleException(
                    "Cannot inspect generated documentation output " + directory, exception);
        }
    }
}
