package blue.buildlogic.tasks;

import blue.buildlogic.support.JavaModuleInventory;
import blue.buildlogic.support.ModuleStructureVerifier;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Verifies package ownership and the acyclic allowed graph of generated module inventories. */
@CacheableTask
public abstract class VerifyJavaModuleStructureTask extends DefaultTask {

    public VerifyJavaModuleStructureTask() {
        getAllowedEdges().convention(java.util.Collections.emptyList());
        getEnforceAllowedEdges().convention(false);
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getModuleInventories();

    @Input
    public abstract ListProperty<String> getAllowedEdges();

    @Input
    public abstract Property<Boolean> getEnforceAllowedEdges();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void verify() {
        List<File> files = new ArrayList<>(getModuleInventories().getFiles());
        files.sort(Comparator.comparing(File::getName).thenComparing(File::getAbsolutePath));
        List<JavaModuleInventory.Inventory> inventories = new ArrayList<>();
        files.forEach(file -> inventories.add(JavaModuleInventory.read(file.toPath())));
        ModuleStructureVerifier.Result result = ModuleStructureVerifier.analyze(
                inventories, getAllowedEdges().get(), getEnforceAllowedEdges().get());
        write(result.toJson());
        if (!result.isValid()) {
            throw new GradleException("Invalid Java module structure: "
                    + result.getSplitPackageCount() + " split package(s), "
                    + result.getCycleCount() + " module cycle(s), and "
                    + result.getUndeclaredEdgeCount() + " undeclared edge(s); see "
                    + getReportFile().get().getAsFile());
        }
    }

    private void write(String report) {
        Path output = getReportFile().get().getAsFile().toPath();
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, report, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot write Java module structure report: " + output, exception);
        }
    }
}
