package blue.buildlogic.tasks;

import blue.buildlogic.support.DeterministicHashing;
import blue.buildlogic.support.DeterministicJson;
import blue.buildlogic.support.SourceSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Writes a path-ordered SHA-256 identity receipt for a configurable file set. */
@CacheableTask
public abstract class GenerateFileIdentityTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getInputFiles();

    @Internal
    public abstract DirectoryProperty getRootDirectory();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void generate() {
        Path root = getRootDirectory().get().getAsFile().toPath();
        List<Path> paths = getInputFiles().getFiles().stream()
                .map(java.io.File::toPath)
                .collect(Collectors.toList());
        SourceSnapshot snapshot = DeterministicHashing.snapshot(root, paths);

        List<Map<String, Object>> entries = new ArrayList<>();
        for (SourceSnapshot.Entry entry : snapshot.getEntries()) {
            Map<String, Object> item = new TreeMap<>();
            item.put("identity", entry.getIdentity());
            item.put("path", entry.getPath());
            entries.add(item);
        }
        Map<String, Object> report = new TreeMap<>();
        report.put("entries", entries);
        report.put("fileCount", entries.size());
        report.put("identity", snapshot.getIdentity());
        report.put("schema", "blue-file-set-identity/1.0");
        write(getOutputFile().get().getAsFile().toPath(), DeterministicJson.write(report));
    }

    private static void write(Path output, String value) {
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, value, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot write file identity receipt: " + output, exception);
        }
    }
}
