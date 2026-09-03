package blue.buildlogic.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.buildlogic.support.DeterministicHashing;
import blue.buildlogic.support.StagedRepositoryManifest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.stream.Stream;
import org.gradle.api.Project;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.testfixtures.ProjectBuilder;
import org.gradle.work.DisableCachingByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AssembleImmutableStagedRepositoryTaskTest {

    private static final String ARTIFACT = "blue-contracts-fixture";
    private static final String COMMIT =
            "0123456789abcdef0123456789abcdef01234567";
    private static final String GROUP = "blue.language";
    private static final String VERSION = "3.1.0-dev." + COMMIT;

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldExportExactPublicationAndReleaseBindings() throws Exception {
        // given
        Project project = ProjectBuilder.builder()
                .withProjectDir(temporaryDirectory.toFile())
                .build();
        Path source = publicationRepository();
        Path specification = Files.writeString(
                temporaryDirectory.resolve("contracts.md"),
                "specification\n",
                StandardCharsets.UTF_8);
        String specificationHash = DeterministicHashing.sha256(specification)
                .substring("sha256:".length());
        Path release = Files.writeString(
                temporaryDirectory.resolve("release-manifest.yaml"),
                "specificationDocument:\n"
                        + "  sha256: " + specificationHash + "\n"
                        + "fixturePackage:\n"
                        + "  packageIdentity: sha256:"
                        + repeat('a') + "\n"
                        + "releaseIdentity: sha256:" + repeat('b') + "\n",
                StandardCharsets.UTF_8);
        AssembleImmutableStagedRepositoryTask task = project.getTasks().register(
                "assembleFixtureRepository",
                AssembleImmutableStagedRepositoryTask.class).get();
        task.getSourceRepository().set(source.toFile());
        task.getOutputRepository().set(project.getLayout().getProjectDirectory()
                .dir("immutable"));
        task.getGroupId().set(GROUP);
        task.getVersionValue().set(VERSION);
        task.getExpectedArtifacts().set(Collections.singletonList(ARTIFACT));
        task.getSourceCommit().set(COMMIT);
        task.getRepositoryHead().set(COMMIT);
        task.getWorkingTreeStatus().set("");
        task.getContractsSpecification().set(specification.toFile());
        task.getContractsReleaseManifest().set(release.toFile());

        // when
        task.assemble();

        // then
        Path target = temporaryDirectory.resolve("immutable");
        String manifest = Files.readString(
                target.resolve(StagedRepositoryManifest.MANIFEST_FILE),
                StandardCharsets.UTF_8);
        assertTrue(manifest.contains("\"sourceCommit\":\"" + COMMIT + "\""));
        assertTrue(manifest.contains("\"contractsSpecificationIdentity\":\"sha256:"
                + specificationHash + "\""));
        assertTrue(Files.isRegularFile(
                target.resolve(StagedRepositoryManifest.MANIFEST_CHECKSUM_FILE)));
        try (Stream<Path> paths = Files.walk(target)) {
            assertEquals(10L, paths.filter(Files::isRegularFile).count());
        }
        assertTrue(AssembleImmutableStagedRepositoryTask.class.isAnnotationPresent(
                DisableCachingByDefault.class));
        assertTrue(AssembleImmutableStagedRepositoryTask.class
                .getMethod("getOutputRepository")
                .isAnnotationPresent(Internal.class));
        assertTrue(AssembleImmutableStagedRepositoryTask.class
                .getMethod("getOutputRepositoryPath")
                .isAnnotationPresent(Input.class));
        assertEquals(
                target.toRealPath(),
                Path.of(task.getOutputRepositoryPath()).toRealPath());
    }

    @Test
    void shouldRejectCandidateVersionThatIsNotCommitBound() throws Exception {
        AssembleImmutableStagedRepositoryTask task = configuredTask("invalidVersion");
        task.getVersionValue().set("3.1.0-dev.latest");

        GradleException failure = assertThrows(GradleException.class, task::assemble);

        assertTrue(failure.getMessage().contains("3.1.0-dev.<40 lowercase hex commit>"));
    }

    @Test
    void shouldRejectCandidateBoundToAnotherCommit() throws Exception {
        AssembleImmutableStagedRepositoryTask task = configuredTask("wrongCommit");
        task.getVersionValue().set("3.1.0-dev." + repeatCommit('f'));

        GradleException failure = assertThrows(GradleException.class, task::assemble);

        assertTrue(failure.getMessage().contains("version is not bound to repository HEAD"));
    }

    @Test
    void shouldRejectDirtyCheckout() throws Exception {
        AssembleImmutableStagedRepositoryTask task = configuredTask("dirtyCheckout");
        task.getWorkingTreeStatus().set(" M build.gradle\n");

        GradleException failure = assertThrows(GradleException.class, task::assemble);

        assertTrue(failure.getMessage().contains("requires a clean Git checkout"));
    }

    private AssembleImmutableStagedRepositoryTask configuredTask(String name) throws Exception {
        Project project = ProjectBuilder.builder()
                .withProjectDir(temporaryDirectory.toFile())
                .build();
        Path source = publicationRepository();
        Path specification = Files.writeString(
                temporaryDirectory.resolve(name + "-contracts.md"),
                "specification\n",
                StandardCharsets.UTF_8);
        String specificationHash = DeterministicHashing.sha256(specification)
                .substring("sha256:".length());
        Path release = Files.writeString(
                temporaryDirectory.resolve(name + "-release-manifest.yaml"),
                "specificationDocument:\n"
                        + "  sha256: " + specificationHash + "\n"
                        + "fixturePackage:\n"
                        + "  packageIdentity: sha256:" + repeat('a') + "\n"
                        + "releaseIdentity: sha256:" + repeat('b') + "\n",
                StandardCharsets.UTF_8);
        AssembleImmutableStagedRepositoryTask task = project.getTasks().register(
                name, AssembleImmutableStagedRepositoryTask.class).get();
        task.getSourceRepository().set(source.toFile());
        task.getOutputRepository().set(project.getLayout().getProjectDirectory()
                .dir(name + "-immutable"));
        task.getGroupId().set(GROUP);
        task.getVersionValue().set(VERSION);
        task.getExpectedArtifacts().set(Collections.singletonList(ARTIFACT));
        task.getSourceCommit().set(COMMIT);
        task.getRepositoryHead().set(COMMIT);
        task.getWorkingTreeStatus().set("");
        task.getContractsSpecification().set(specification.toFile());
        task.getContractsReleaseManifest().set(release.toFile());
        return task;
    }

    private Path publicationRepository() throws Exception {
        Path repository = Files.createDirectories(temporaryDirectory.resolve("mutable"));
        Path coordinate = Files.createDirectories(repository.resolve(
                "blue/language/" + ARTIFACT + "/" + VERSION));
        String base = ARTIFACT + "-" + VERSION;
        Files.writeString(coordinate.resolve(base + ".pom"), "pom\n");
        Files.writeString(coordinate.resolve(base + ".jar"), "runtime\n");
        Files.writeString(coordinate.resolve(base + "-sources.jar"), "sources\n");
        Files.writeString(coordinate.resolve(base + "-javadoc.jar"), "javadoc\n");
        return repository;
    }

    private static String repeat(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }

    private static String repeatCommit(char value) {
        return String.join("", Collections.nCopies(40, String.valueOf(value)));
    }
}
