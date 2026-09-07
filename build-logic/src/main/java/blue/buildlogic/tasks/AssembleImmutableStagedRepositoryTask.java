package blue.buildlogic.tasks;

import blue.buildlogic.support.CommitBoundDevelopmentCandidate;
import blue.buildlogic.support.StagedRepositoryManifest;
import java.nio.file.Path;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Exports transient Maven publications into one exact, non-overwriting dependency repository. */
@DisableCachingByDefault(
        because = "The task must inspect and never replace an existing handoff repository")
public abstract class AssembleImmutableStagedRepositoryTask extends DefaultTask {

    public AssembleImmutableStagedRepositoryTask() {
        getGroupId().convention("blue.language");
    }

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getSourceRepository();

    @Internal
    public abstract DirectoryProperty getOutputRepository();

    /**
     * Tracks the requested handoff path without declaring it as a Gradle output directory.
     *
     * <p>Gradle creates declared output directories before invoking a task action. That lifecycle
     * behavior would make a genuinely new immutable handoff look like a pre-existing repository
     * and would defeat the task's fail-closed, non-overwriting publication contract.
     */
    @Input
    public String getOutputRepositoryPath() {
        return getOutputRepository().get().getAsFile().getAbsolutePath();
    }

    @Input
    public abstract Property<String> getGroupId();

    @Input
    public abstract Property<String> getVersionValue();

    @Input
    public abstract ListProperty<String> getExpectedArtifacts();

    @Input
    public abstract Property<String> getSourceCommit();

    @Input
    public abstract Property<String> getSourceTree();

    @Input
    public int getBuiltWithJava() {
        return Runtime.version().feature();
    }

    @Input
    public abstract Property<String> getRepositoryHead();

    @Input
    public abstract Property<String> getWorkingTreeStatus();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getContractsSpecification();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getContractsReleaseManifest();

    @TaskAction
    public void assemble() {
        int builtWithJava = getBuiltWithJava();
        if (builtWithJava != StagedRepositoryManifest.REQUIRED_BUILD_JAVA) {
            throw new GradleException(
                    "Immutable development repository must be built with Java "
                            + StagedRepositoryManifest.REQUIRED_BUILD_JAVA
                            + "; current Gradle JVM is Java " + builtWithJava);
        }
        CommitBoundDevelopmentCandidate.verify(
                getVersionValue().get(),
                getSourceCommit().get(),
                getRepositoryHead().get(),
                getWorkingTreeStatus().get());
        Path specification = getContractsSpecification().get().getAsFile().toPath();
        StagedRepositoryManifest.Bindings bindings = StagedRepositoryManifest.bindings(
                specification,
                getContractsReleaseManifest().get().getAsFile().toPath(),
                getSourceCommit().get(),
                getSourceTree().get(),
                false,
                builtWithJava);
        StagedRepositoryManifest.assemble(
                getSourceRepository().get().getAsFile().toPath(),
                getOutputRepository().get().getAsFile().toPath(),
                getGroupId().get(),
                getVersionValue().get(),
                getExpectedArtifacts().get(),
                bindings);
    }
}
