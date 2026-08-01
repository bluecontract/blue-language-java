package blue.buildlogic.tasks;

import blue.buildlogic.support.DeterministicHashing;
import blue.buildlogic.support.SourceSnapshot;
import blue.buildlogic.support.StaleInputVerifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Fails when current inputs no longer match a previously generated evidence document. */
@DisableCachingByDefault(because = "Verification has no output and must inspect current inputs")
public abstract class VerifyInputIdentityTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceFiles();

    @Internal
    public abstract DirectoryProperty getSourceRoot();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getEvidenceFile();

    @TaskAction
    public void verify() {
        Path evidencePath = getEvidenceFile().get().getAsFile().toPath();
        String evidence;
        try {
            evidence = Files.readString(evidencePath, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot read release evidence: " + evidencePath, exception);
        }
        List<Path> sourcePaths = getSourceFiles().getFiles().stream()
                .map(java.io.File::toPath)
                .collect(Collectors.toList());
        SourceSnapshot current = DeterministicHashing.snapshot(
                getSourceRoot().get().getAsFile().toPath(), sourcePaths);
        StaleInputVerifier.assertCurrent(
                StaleInputVerifier.sourceIdentityFrom(evidence), current.getIdentity());
    }
}
