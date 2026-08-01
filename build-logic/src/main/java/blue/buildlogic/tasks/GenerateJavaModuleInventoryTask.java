package blue.buildlogic.tasks;

import blue.buildlogic.support.JavaModuleInventory;
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

/** Generates module package/class/reference ownership from compiled artifacts or Java sources. */
@CacheableTask
public abstract class GenerateJavaModuleInventoryTask extends DefaultTask {

    @Input
    public abstract Property<String> getModuleName();

    @Classpath
    public abstract ConfigurableFileCollection getCompiledInputs();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceInputs();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void generate() {
        JavaModuleInventory.Inventory inventory = JavaModuleInventory.inspect(
                getModuleName().get(), paths(getCompiledInputs()), paths(getSourceInputs()));
        Path output = getOutputFile().get().getAsFile().toPath();
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, inventory.write(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot write Java module inventory: " + output, exception);
        }
    }

    private static List<Path> paths(ConfigurableFileCollection files) {
        return files.getFiles().stream().map(File::toPath).collect(Collectors.toList());
    }
}
