package blue.buildlogic.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RepositorySourceFilesTest {

    private static final String COMMIT = "0123456789012345678901234567890123456789";
    private static final String MIGRATION = "blue-conformance/src/main/tools/migration/";
    private static final List<String> REQUIRED = List.of(
            MIGRATION + "classify-retired-slot-reviewed-after.tar.gz",
            MIGRATION + "classify-legal-detached-retarget-reviewed-after.tar.gz",
            MIGRATION + "classify-baseline-reconciliation-reviewed-inputs.tar.gz");

    @TempDir
    Path temporaryDirectory;

    private Path projectDirectory;
    private Path gradleUserHomeDirectory;

    @BeforeEach
    void prepareProjectDirectories() throws Exception {
        projectDirectory = Files.createDirectories(temporaryDirectory.resolve("project")).toRealPath();
        gradleUserHomeDirectory = Files.createDirectories(temporaryDirectory.resolve("gradle-user-home")).toRealPath();
    }

    @Test
    void shouldIncludeOnlyExactRequiredArchivesInCleanAndSourceReleaseInputs() throws Exception {
        // given
        Project project = fixture();
        Set<String> expected = new TreeSet<>(REQUIRED);
        expected.add("source.txt");

        // when
        Set<String> sourceRelease = names(RepositorySourceFiles.createForSourceRelease(project).getFiles());
        Set<String> cleanSource = names(RepositorySourceFiles.create(project).getFiles());

        // then
        assertEquals(expected, sourceRelease);
        expected.add(".cz.toml");
        assertEquals(expected, cleanSource);
    }

    @Test
    void shouldRejectCaseVariantsOfRequiredArchivePaths() throws Exception {
        // given
        List<Path> wrongCases = List.of(
                write(MIGRATION + "Classify-retired-slot-reviewed-after.tar.gz", "wrong path case"),
                write(MIGRATION + "classify-legal-detached-retarget-reviewed-after.TAR.GZ", "upper extension"),
                write(MIGRATION + "classify-baseline-reconciliation-reviewed-inputs.TaR.gZ", "mixed extension"));
        Project project = newProject();

        // when
        Set<String> cleanSource = names(RepositorySourceFiles.create(project).getFiles());

        // then
        assertTrue(cleanSource.isEmpty());
        assertTrue(RepositorySourceFiles.createForSourceRelease(project).isEmpty());
        for (Path wrongCase : wrongCases) {
            SourceReleaseArchiveVerifier.Result result = SourceReleaseArchiveVerifier.verify(
                    zip("case.zip", List.of(wrongCase)), Set.of(entryName(wrongCase)), "blue-1.0");
            assertFalse(result.isValid());
            assertTrue(result.getViolations().contains("forbidden-debris:" + entryName(wrongCase)));
        }
    }

    @Test
    void shouldBindEveryRequiredArchiveAndIgnoreGeneratedArchivesInCleanBuildEvidence() throws Exception {
        // given
        Project project = fixture();
        Path marker = write("build/clean-build.json", CleanBuildEvidence.createCleanBuild(
                projectDirectory, sourcePaths(project), COMMIT, "42", ":clean", ":build",
                List.of("clean", "build"), Collections.emptyList()));

        // when / then
        write("build/generated.tar.gz", "changed generated output");
        write(MIGRATION + "unreviewed.tar.gz", "changed unrelated archive");
        assertTrue(verify(marker, project).isVerified());
        for (String path : REQUIRED) {
            write(path, "changed required input");
            assertFalse(verify(marker, project).isVerified(), path);
            assertEquals("source-inputs-changed-since-clean-build", verify(marker, project).getReason());
            write(path, "reviewed:" + path);
            assertTrue(verify(marker, project).isVerified(), path);
            Files.delete(projectDirectory.resolve(path));
            assertFalse(verify(marker, project).isVerified(), path);
            write(path, "reviewed:" + path);
            assertTrue(verify(marker, project).isVerified(), path);
        }
    }

    @Test
    void shouldVerifyRequiredArchiveZipEntriesAndRejectMissingOrArbitraryArchives() throws Exception {
        // given
        Project project = fixture();
        List<Path> inputs = RepositorySourceFiles.createForSourceRelease(project).getFiles().stream()
                .map(File::toPath).collect(Collectors.toCollection(ArrayList::new));
        inputs.add(projectDirectory.resolve(".cz.toml"));
        Set<String> expected = inputs.stream().map(this::entryName)
                .collect(Collectors.toCollection(TreeSet::new));

        // when
        Path archive = zip("source.zip", inputs);
        SourceReleaseArchiveVerifier.Result valid = SourceReleaseArchiveVerifier.verify(
                archive, expected, "blue-1.0");

        // then
        assertTrue(valid.isValid(), valid.toJson());
        try (ZipFile saved = new ZipFile(archive.toFile())) {
            for (String path : REQUIRED) {
                ZipEntry entry = saved.getEntry("blue-1.0/" + path);
                assertTrue(entry != null, path);
                assertEquals("reviewed:" + path,
                        new String(saved.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        List<Path> missing = new ArrayList<>(inputs);
        missing.remove(projectDirectory.resolve(REQUIRED.get(0)));
        SourceReleaseArchiveVerifier.Result absent = SourceReleaseArchiveVerifier.verify(
                zip("missing.zip", missing), expected, "blue-1.0");
        assertFalse(absent.isValid());
        assertTrue(absent.getViolations().contains("missing-entry:blue-1.0/" + REQUIRED.get(0)));
        for (String path : List.of(MIGRATION + "unreviewed.tar.gz", "other/" + REQUIRED.get(0),
                "build/" + REQUIRED.get(0), MIGRATION + "classify-retired-witness-reviewed-after.tar.gz")) {
            Path arbitrary = projectDirectory.resolve(path);
            List<Path> extraInputs = new ArrayList<>(inputs);
            extraInputs.add(arbitrary);
            Set<String> extraExpected = new TreeSet<>(expected);
            extraExpected.add(entryName(arbitrary));
            SourceReleaseArchiveVerifier.Result debris = SourceReleaseArchiveVerifier.verify(
                    zip("debris.zip", extraInputs), extraExpected, "blue-1.0");
            assertFalse(debris.isValid(), path);
            assertTrue(debris.getViolations().contains("forbidden-debris:" + entryName(arbitrary)));
        }
    }

    private Project fixture() throws Exception {
        write(".cz.toml", "version = \"1.0\"\n");
        write("source.txt", "source");
        for (String path : REQUIRED) {
            write(path, "reviewed:" + path);
            write("build/" + path, "generated copy");
            write("other/" + path, "wrong location");
        }
        for (String path : List.of("build/generated.tar.gz", MIGRATION + "unreviewed.tar.gz",
                MIGRATION + "classify-retired-witness-reviewed-after.tar.gz",
                "scratch.tar.gz", "scratch.zip", "scratch.tar", "scratch.tgz")) {
            write(path, "not a required source input");
        }
        return newProject();
    }

    private Project newProject() {
        return ProjectBuilder.builder().withProjectDir(projectDirectory.toFile())
                .withGradleUserHomeDir(gradleUserHomeDirectory.toFile()).build();
    }

    private List<Path> sourcePaths(Project project) {
        return RepositorySourceFiles.create(project).getFiles().stream()
                .map(File::toPath).collect(Collectors.toList());
    }

    private Set<String> names(Collection<File> files) {
        return files.stream().map(file -> projectDirectory.relativize(file.toPath()).toString()
                .replace(File.separatorChar, '/')).collect(Collectors.toCollection(TreeSet::new));
    }

    private CleanBuildEvidence.Verification verify(Path marker, Project project) {
        return CleanBuildEvidence.verify(marker, projectDirectory, sourcePaths(project),
                COMMIT, "42", ":clean", ":build");
    }

    private String entryName(Path path) {
        return "blue-1.0/" + projectDirectory.relativize(path).toString().replace(File.separatorChar, '/');
    }

    private Path zip(String name, Collection<Path> files) throws Exception {
        Path output = projectDirectory.resolve("build").resolve(name);
        Files.createDirectories(output.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            for (Path path : files) {
                ZipEntry entry = new ZipEntry(entryName(path));
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(Files.readAllBytes(path));
                zip.closeEntry();
            }
        }
        return output;
    }

    private Path write(String relativePath, String content) throws Exception {
        Path path = projectDirectory.resolve(relativePath);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
