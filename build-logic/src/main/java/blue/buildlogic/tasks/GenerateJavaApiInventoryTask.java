package blue.buildlogic.tasks;

import blue.buildlogic.support.JavaPublicApiInventory;
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
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Generates a deterministic public API inventory from classes, JARs, and module inventories. */
@CacheableTask
public abstract class GenerateJavaApiInventoryTask extends DefaultTask {

    @Classpath
    public abstract ConfigurableFileCollection getCompiledInputs();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getUnionInputs();

    @Input
    public abstract Property<String> getModuleName();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void generate() {
        List<Path> compiled = paths(getCompiledInputs());
        List<Path> unions = paths(getUnionInputs());
        List<String> generated = JavaPublicApiInventory.inspect(compiled);
        List<String> entries = JavaPublicApiInventory.union(generated, unions);
        write(JavaPublicApiInventory.write(getModuleName().get(), entries));
    }

    private void write(String inventory) {
        Path output = getOutputFile().get().getAsFile().toPath();
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, inventory, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot write Java API inventory: " + output, exception);
        }
    }

    private static List<Path> paths(ConfigurableFileCollection files) {
        return files.getFiles().stream().map(File::toPath).collect(Collectors.toList());
    }
}
