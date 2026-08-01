package blue.buildlogic.tasks;

import blue.buildlogic.support.JavaPackageCycleAnalyzer;
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
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/** Verifies that configured compiled artifacts contain no cross-package cycle. */
@CacheableTask
public abstract class VerifyJavaPackageCyclesTask extends DefaultTask {

    /** Class directories and JARs that jointly own the analyzed package graph. */
    @Classpath
    public abstract ConfigurableFileCollection getCompiledInputs();

    /** Deterministic JSON report written for both passing and failing graphs. */
    @OutputFile
    public abstract RegularFileProperty getReportFile();

    /** Builds the bytecode graph, records its SCCs, and rejects every nontrivial SCC. */
    @TaskAction
    public void verify() {
        List<Path> inputs = getCompiledInputs().getFiles().stream()
                .map(File::toPath)
                .collect(Collectors.toList());
        JavaPackageCycleAnalyzer.Result result =
                JavaPackageCycleAnalyzer.analyze(inputs);
        write(result.toJson());
        if (!result.isAcyclic()) {
            throw new GradleException(
                    "Java package graph contains " + result.getCycleCount()
                            + " cycle(s) " + result.getCycles() + "; see "
                            + getReportFile().get().getAsFile());
        }
    }

    private void write(String report) {
        Path output = getReportFile().get().getAsFile().toPath();
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, report, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException(
                    "Cannot write Java package-cycle report: " + output,
                    exception);
        }
    }
}
