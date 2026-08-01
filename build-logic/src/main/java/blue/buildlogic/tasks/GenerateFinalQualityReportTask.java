package blue.buildlogic.tasks;

import blue.buildlogic.support.DeterministicJson;
import blue.buildlogic.support.FinalQualityEvidence;
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
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
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

/** Produces the final source, API, test, docs, benchmark, and release eligibility report. */
@DisableCachingByDefault(because = "Source commit and executed release evidence are invocation facts")
public abstract class GenerateFinalQualityReportTask extends DefaultTask {

    public GenerateFinalQualityReportTask() {
        getExpectedModuleCount().convention(7);
        getExpectedLanguageFixtures().convention(153);
        getExpectedContractsFixtures().convention(140);
        getMaximumOrdinaryClassLines().convention(1200);
        getBlueFacadeLineLimit().convention(700);
        getBlueFacadeMemberLimit().convention(24);
        getPublicFacadeMemberLimit().convention(100);
        getClassSizeRationales().convention(java.util.Collections.emptyMap());
        getRequiredSmokeBenchmarks().convention(java.util.Collections.emptyList());
        getJavadocsSuccessful().convention(false);
        getExamplesCompiled().convention(false);
        getBenchmarksCompiled().convention(false);
    }

    @Internal
    public abstract DirectoryProperty getRepositoryRoot();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getProductionSources();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getApiInventories();

    @InputFiles
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract ConfigurableFileCollection getModuleArtifacts();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getTestResults();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getPackageCycleReports();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getReleaseConformanceReport();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getDocumentationReport();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getModuleStructureReport();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getLanguageSpecification();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getContractsSpecification();

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getBenchmarkResults();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getPublishedRepositoryReport();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getPublishedSmokeReport();

    @Input
    public abstract Property<String> getSourceCommit();

    @Input
    public abstract MapProperty<String, String> getClassSizeRationales();

    @Input
    public abstract ListProperty<String> getRequiredSmokeBenchmarks();

    @Input
    public abstract Property<Integer> getExpectedModuleCount();

    @Input
    public abstract Property<Integer> getExpectedLanguageFixtures();

    @Input
    public abstract Property<Integer> getExpectedContractsFixtures();

    @Input
    public abstract Property<Integer> getMaximumOrdinaryClassLines();

    @Input
    public abstract Property<Integer> getBlueFacadeLineLimit();

    @Input
    public abstract Property<Integer> getBlueFacadeMemberLimit();

    @Input
    public abstract Property<Integer> getPublicFacadeMemberLimit();

    @Input
    public abstract Property<Boolean> getJavadocsSuccessful();

    @Input
    public abstract Property<Boolean> getExamplesCompiled();

    @Input
    public abstract Property<Boolean> getBenchmarksCompiled();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void generate() {
        File benchmark = getBenchmarkResults().getAsFile().getOrNull();
        FinalQualityEvidence.Inputs inputs = new FinalQualityEvidence.Inputs(
                getRepositoryRoot().get().getAsFile().toPath(),
                paths(getProductionSources()),
                paths(getApiInventories()),
                paths(getModuleArtifacts()),
                paths(getTestResults()),
                paths(getPackageCycleReports()),
                getReleaseConformanceReport().get().getAsFile().toPath(),
                getDocumentationReport().get().getAsFile().toPath(),
                getModuleStructureReport().get().getAsFile().toPath(),
                getLanguageSpecification().get().getAsFile().toPath(),
                getContractsSpecification().get().getAsFile().toPath(),
                benchmark == null ? null : benchmark.toPath(),
                getPublishedRepositoryReport().get().getAsFile().toPath(),
                getPublishedSmokeReport().get().getAsFile().toPath(),
                getSourceCommit().get(),
                getClassSizeRationales().get(),
                getRequiredSmokeBenchmarks().get(),
                getExpectedModuleCount().get(),
                getExpectedLanguageFixtures().get(),
                getExpectedContractsFixtures().get(),
                getMaximumOrdinaryClassLines().get(),
                getBlueFacadeLineLimit().get(),
                getBlueFacadeMemberLimit().get(),
                getPublicFacadeMemberLimit().get(),
                getJavadocsSuccessful().get(),
                getExamplesCompiled().get(),
                getBenchmarksCompiled().get());
        Map<String, Object> report = FinalQualityEvidence.analyze(inputs);
        write(DeterministicJson.write(report));
    }

    private static Collection<Path> paths(ConfigurableFileCollection files) {
        List<Path> values = new ArrayList<>();
        for (File file : files.getFiles()) {
            values.add(file.toPath());
        }
        return values;
    }

    private void write(String content) {
        Path output = getReportFile().get().getAsFile().toPath();
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot write final quality report " + output, exception);
        }
    }
}
