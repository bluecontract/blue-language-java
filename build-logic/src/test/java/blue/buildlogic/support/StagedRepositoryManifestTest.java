package blue.buildlogic.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.stream.Stream;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StagedRepositoryManifestTest {

    private static final String ARTIFACT = "blue-contracts-fixture";
    private static final String GROUP = "blue.language";
    private static final String VERSION = "1.2.3";
    private static final String COMMIT =
            "0123456789abcdef0123456789abcdef01234567";
    private static final String FIXTURES = "sha256:"
            + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String RELEASE = "sha256:"
            + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldAssembleAndVerifyOneNonSelfReferentialRepository() throws Exception {
        // given
        Fixture fixture = fixture();

        // when
        StagedRepositoryManifest.assemble(
                fixture.source,
                fixture.target,
                GROUP,
                VERSION,
                Collections.singletonList(ARTIFACT),
                fixture.bindings);
        StagedRepositoryManifest.assemble(
                fixture.source,
                fixture.target,
                GROUP,
                VERSION,
                Collections.singletonList(ARTIFACT),
                fixture.bindings);
        StagedRepositoryManifest.Verification verification =
                StagedRepositoryManifest.verify(
                        fixture.target,
                        GROUP,
                        VERSION,
                        Collections.singletonList(ARTIFACT),
                        fixture.bindings);

        // then
        assertTrue(verification.getViolations().isEmpty());
        assertTrue(verification.getManifestIdentity().startsWith("sha256:"));
        String manifest = Files.readString(
                fixture.target.resolve(StagedRepositoryManifest.MANIFEST_FILE));
        assertFalse(manifest.contains("artifactManifestIdentity"));
        assertTrue(manifest.contains(COMMIT));
        assertTrue(manifest.contains(FIXTURES));
        assertTrue(manifest.contains(RELEASE));
        try (Stream<Path> paths = Files.walk(fixture.target)) {
            assertEquals(10L, paths.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void shouldRejectTamperedPayloadAndChecksum() throws Exception {
        // given
        Fixture fixture = fixture();
        StagedRepositoryManifest.assemble(
                fixture.source,
                fixture.target,
                GROUP,
                VERSION,
                Collections.singletonList(ARTIFACT),
                fixture.bindings);
        Path runtime = fixture.target.resolve(
                "blue/language/" + ARTIFACT + "/" + VERSION + "/"
                        + ARTIFACT + "-" + VERSION + ".jar");

        // when
        Files.writeString(runtime, "tampered", StandardCharsets.UTF_8);
        StagedRepositoryManifest.Verification verification =
                StagedRepositoryManifest.verify(
                        fixture.target,
                        GROUP,
                        VERSION,
                        Collections.singletonList(ARTIFACT),
                        fixture.bindings);

        // then
        assertTrue(verification.getViolations().stream()
                .anyMatch(value -> value.contains("checksum mismatch")));
        assertTrue(verification.getViolations().stream()
                .anyMatch(value -> value.contains("manifest does not match")));
    }

    @Test
    void shouldRejectUnexpectedRepositoryFile() throws Exception {
        // given
        Fixture fixture = fixture();
        StagedRepositoryManifest.assemble(
                fixture.source,
                fixture.target,
                GROUP,
                VERSION,
                Collections.singletonList(ARTIFACT),
                fixture.bindings);
        Files.writeString(
                fixture.target.resolve("unexpected.txt"),
                "not part of the handoff\n",
                StandardCharsets.UTF_8);

        // when
        StagedRepositoryManifest.Verification verification =
                StagedRepositoryManifest.verify(
                        fixture.target,
                        GROUP,
                        VERSION,
                        Collections.singletonList(ARTIFACT),
                        fixture.bindings);

        // then
        assertTrue(verification.getViolations().contains(
                "unexpected staged repository file unexpected.txt"));
    }

    @Test
    void shouldRejectWrongVersionInsteadOfFallingBackFromMissingExactPayload()
            throws Exception {
        // given
        Fixture fixture = fixture();
        Path coordinate = fixture.source.resolve(
                "blue/language/" + ARTIFACT + "/" + VERSION);
        Path exactRuntime = coordinate.resolve(ARTIFACT + "-" + VERSION + ".jar");
        Files.delete(exactRuntime);
        Path wrongVersion = coordinate.resolve(ARTIFACT + "-9.9.9.jar");
        Files.writeString(wrongVersion, "wrong version\n", StandardCharsets.UTF_8);

        // when
        GradleException failure = assertThrows(
                GradleException.class,
                () -> StagedRepositoryManifest.assemble(
                        fixture.source,
                        fixture.target,
                        GROUP,
                        VERSION,
                        Collections.singletonList(ARTIFACT),
                        fixture.bindings));

        // then
        assertTrue(Files.isRegularFile(wrongVersion));
        assertTrue(failure.getMessage().contains(
                "Required staged publication file is missing"));
        assertTrue(failure.getMessage().contains(
                ARTIFACT + "-" + VERSION + ".jar"));
        assertFalse(Files.exists(fixture.target));
    }

    @Test
    void shouldRejectTamperedManifestBindingAndManifestSidecar() throws Exception {
        // given
        Fixture fixture = fixture();
        StagedRepositoryManifest.assemble(
                fixture.source,
                fixture.target,
                GROUP,
                VERSION,
                Collections.singletonList(ARTIFACT),
                fixture.bindings);
        Path manifest = fixture.target.resolve(StagedRepositoryManifest.MANIFEST_FILE);
        Path sidecar = fixture.target.resolve(
                StagedRepositoryManifest.MANIFEST_CHECKSUM_FILE);
        String originalManifest = Files.readString(manifest, StandardCharsets.UTF_8);
        String changedCommit = "fedcba9876543210fedcba9876543210fedcba98";
        Files.writeString(
                manifest,
                originalManifest.replace(COMMIT, changedCommit),
                StandardCharsets.UTF_8);
        writeChecksum(manifest, sidecar);

        // when
        StagedRepositoryManifest.Verification bindingVerification =
                StagedRepositoryManifest.verify(
                        fixture.target,
                        GROUP,
                        VERSION,
                        Collections.singletonList(ARTIFACT),
                        fixture.bindings);

        // then
        assertTrue(bindingVerification.getViolations().stream()
                .anyMatch(value -> value.contains(
                        "manifest does not match exact payloads and bindings")));
        assertFalse(bindingVerification.getViolations().stream()
                .anyMatch(value -> value.contains("manifest checksum mismatch")));

        // and when the manifest bytes are restored but the sidecar is changed
        Files.writeString(manifest, originalManifest, StandardCharsets.UTF_8);
        Files.writeString(sidecar, "tampered\n", StandardCharsets.UTF_8);
        StagedRepositoryManifest.Verification sidecarVerification =
                StagedRepositoryManifest.verify(
                        fixture.target,
                        GROUP,
                        VERSION,
                        Collections.singletonList(ARTIFACT),
                        fixture.bindings);

        // then
        assertTrue(sidecarVerification.getViolations().stream()
                .anyMatch(value -> value.contains("manifest checksum mismatch")));
    }

    @Test
    void shouldNeverOverwriteDivergentExistingRepository() throws Exception {
        // given
        Fixture fixture = fixture();
        StagedRepositoryManifest.assemble(
                fixture.source,
                fixture.target,
                GROUP,
                VERSION,
                Collections.singletonList(ARTIFACT),
                fixture.bindings);
        Path manifest = fixture.target.resolve(StagedRepositoryManifest.MANIFEST_FILE);
        Files.writeString(manifest, "different\n", StandardCharsets.UTF_8);

        // when
        GradleException failure = assertThrows(
                GradleException.class,
                () -> StagedRepositoryManifest.assemble(
                        fixture.source,
                        fixture.target,
                        GROUP,
                        VERSION,
                        Collections.singletonList(ARTIFACT),
                        fixture.bindings));

        // then
        assertTrue(failure.getMessage().contains("already exists with different bytes"));
        assertEquals("different\n", Files.readString(manifest));
    }

    @Test
    void shouldRejectSpecificationBytesThatDoNotMatchReleaseManifest() throws Exception {
        // given
        Fixture fixture = fixture();
        Files.writeString(fixture.specification, "different\n", StandardCharsets.UTF_8);

        // when
        GradleException failure = assertThrows(
                GradleException.class,
                () -> StagedRepositoryManifest.bindings(
                        fixture.specification, fixture.releaseManifest, COMMIT));

        // then
        assertTrue(failure.getMessage().contains("specification bytes"));
    }

    @Test
    void shouldRejectUnsafeCoordinatesBeforeResolvingRepositoryPaths() throws Exception {
        // given
        Fixture fixture = fixture();

        // when / then
        GradleException failure = assertThrows(
                GradleException.class,
                () -> StagedRepositoryManifest.assemble(
                        fixture.source,
                        fixture.target,
                        GROUP,
                        VERSION,
                        Collections.singletonList("../outside"),
                        fixture.bindings));
        assertTrue(failure.getMessage().contains("safe Maven coordinate token"));
        assertFalse(Files.exists(temporaryDirectory.resolve("outside")));
    }

    private Fixture fixture() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("mutable"));
        Path target = temporaryDirectory.resolve("immutable");
        Path coordinate = Files.createDirectories(source.resolve(
                "blue/language/" + ARTIFACT + "/" + VERSION));
        String base = ARTIFACT + "-" + VERSION;
        Files.writeString(coordinate.resolve(base + ".pom"), "pom\n");
        Files.writeString(coordinate.resolve(base + ".jar"), "runtime\n");
        Files.writeString(coordinate.resolve(base + "-sources.jar"), "sources\n");
        Files.writeString(coordinate.resolve(base + "-javadoc.jar"), "javadoc\n");
        Path specification = Files.writeString(
                temporaryDirectory.resolve("contracts.md"), "specification\n");
        String specificationIdentity = DeterministicHashing.sha256(specification)
                .substring("sha256:".length());
        Path releaseManifest = Files.writeString(
                temporaryDirectory.resolve("release-manifest.yaml"),
                "specificationDocument:\n"
                        + "  sha256: " + specificationIdentity + "\n"
                        + "fixturePackage:\n"
                        + "  packageIdentity: " + FIXTURES + "\n"
                        + "releaseIdentity: " + RELEASE + "\n");
        StagedRepositoryManifest.Bindings bindings = StagedRepositoryManifest.bindings(
                specification, releaseManifest, COMMIT);
        return new Fixture(source, target, specification, releaseManifest, bindings);
    }

    private static void writeChecksum(Path input, Path output) throws Exception {
        String hash = DeterministicHashing.sha256(input).substring("sha256:".length());
        Files.writeString(
                output,
                hash + "  " + input.getFileName() + "\n",
                StandardCharsets.UTF_8);
    }

    private static final class Fixture {

        private final Path source;
        private final Path target;
        private final Path specification;
        private final Path releaseManifest;
        private final StagedRepositoryManifest.Bindings bindings;

        private Fixture(
                Path source,
                Path target,
                Path specification,
                Path releaseManifest,
                StagedRepositoryManifest.Bindings bindings) {
            this.source = source;
            this.target = target;
            this.specification = specification;
            this.releaseManifest = releaseManifest;
            this.bindings = bindings;
        }
    }
}
