package blue.buildlogic.tasks;

import blue.buildlogic.BuildLogicConstants;
import blue.buildlogic.support.DeterministicJson;
import blue.buildlogic.support.DocumentationVerification;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Writes complete documentation diagnostics without suppressing evidence on a red gate. */
@CacheableTask
public abstract class GenerateDocumentationVerificationReportTask extends DefaultTask {

    public GenerateDocumentationVerificationReportTask() {
        getExpectedLanguageFixtures()
                .convention(BuildLogicConstants.EXPECTED_LANGUAGE_FIXTURE_COUNT);
        getExpectedContractsFixtures()
                .convention(BuildLogicConstants.EXPECTED_CONTRACTS_FIXTURE_COUNT);
    }

    @Internal
    public abstract DirectoryProperty getRepositoryRoot();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getDocumentationFiles();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getGeneratedDocumentationDirectory();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getProductionSources();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getExampleSources();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getExampleTests();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getReleaseConformanceReport();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getLanguageSpecification();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getContractsSpecification();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getRelocationLedger();

    @Input
    public abstract Property<Integer> getExpectedLanguageFixtures();

    @Input
    public abstract Property<Integer> getExpectedContractsFixtures();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void generate() {
        DocumentationVerification.Inputs inputs = new DocumentationVerification.Inputs(
                getRepositoryRoot().get().getAsFile().toPath(),
                paths(getDocumentationFiles()),
                getGeneratedDocumentationDirectory().get().getAsFile().toPath(),
                paths(getProductionSources()),
                paths(getExampleSources()),
                paths(getExampleTests()),
                getReleaseConformanceReport().get().getAsFile().toPath(),
                getLanguageSpecification().get().getAsFile().toPath(),
                getContractsSpecification().get().getAsFile().toPath(),
                getRelocationLedger().get().getAsFile().toPath(),
                getExpectedLanguageFixtures().get(),
                getExpectedContractsFixtures().get());
        Map<String, Object> report = DocumentationVerification.analyze(inputs);
        write(DeterministicJson.write(report));
    }

    private static Collection<Path> paths(ConfigurableFileCollection files) {
        List<Path> paths = new ArrayList<>();
        for (File file : files.getFiles()) {
            paths.add(file.toPath());
        }
        return paths;
    }

    private void write(String content) {
        Path output = getReportFile().get().getAsFile().toPath();
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot write documentation verification report " + output,
                    exception);
        }
    }
}
