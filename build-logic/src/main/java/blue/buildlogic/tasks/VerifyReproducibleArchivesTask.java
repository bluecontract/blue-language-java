package blue.buildlogic.tasks;

import blue.buildlogic.support.ReproducibleArchiveInspector;
import java.io.File;
import java.util.Comparator;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Verifies every configured archive has canonical entry order and normalized timestamps. */
@DisableCachingByDefault(because = "Verification has no output")
public abstract class VerifyReproducibleArchivesTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getArchives();

    @TaskAction
    public void verify() {
        getArchives().getFiles().stream()
                .sorted(Comparator.comparing(File::getName).thenComparing(File::getAbsolutePath))
                .forEach(archive -> ReproducibleArchiveInspector.verify(archive.toPath()));
    }
}
