package blue.buildlogic.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.api.GradleException;

/** Builds and verifies the exact manifest for a downstream immutable Maven repository. */
public final class StagedRepositoryManifest {

    public static final String MANIFEST_FILE = "artifact-manifest.json";
    public static final String MANIFEST_CHECKSUM_FILE = MANIFEST_FILE + ".sha256";
    public static final String SCHEMA = "blue-development-maven-repository/1.0";
    public static final String LOCAL_RC_SCHEMA = "blue-local-rc-maven-repository/1.0";
    public static final int REQUIRED_BUILD_JAVA = 17;

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
    private static final Pattern TREE = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern GROUP = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9_-]*(?:\\.[A-Za-z0-9][A-Za-z0-9_-]*)+");
    private static final Pattern MAVEN_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");
    private static final Pattern SHA_256 = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final List<ArtifactKind> KINDS = Collections.unmodifiableList(Arrays.asList(
            new ArtifactKind("pom", ".pom"),
            new ArtifactKind("runtime", ".jar")));

    private static final List<ArtifactKind> LOCAL_RC_KINDS = Collections.unmodifiableList(Arrays.asList(
            new ArtifactKind("pom", ".pom"),
            new ArtifactKind("runtime", ".jar"),
            new ArtifactKind("sources", "-sources.jar"),
            new ArtifactKind("javadoc", "-javadoc.jar")));

    private StagedRepositoryManifest() {}

    /** Reads and cross-checks the release identities that every exported artifact must bind. */
    public static Bindings bindings(
            Path contractsSpecification,
            Path contractsReleaseManifest,
            String sourceCommit,
            String sourceTree,
            boolean sourceDirty,
            int builtWithJava) {
        String commit = gitIdentity(sourceCommit);
        JsonNode release;
        try {
            release = YAML.readTree(contractsReleaseManifest.toFile());
        } catch (IOException exception) {
            throw new GradleException(
                    "Cannot read Contracts release manifest " + contractsReleaseManifest,
                    exception);
        }
        String recordedSpecification = requiredText(
                release.path("specificationDocument"), "sha256");
        if (!recordedSpecification.matches("[0-9a-f]{64}")) {
            throw new GradleException(
                    "Contracts release manifest has an invalid specification SHA-256");
        }
        String specification = DeterministicHashing.sha256(contractsSpecification);
        if (!specification.equals("sha256:" + recordedSpecification)) {
            throw new GradleException(
                    "Contracts specification bytes do not match the release manifest");
        }
        String fixtures = sha256Identity(requiredText(
                release.path("fixturePackage"), "packageIdentity"),
                "fixture package identity");
        String contractsRelease = sha256Identity(
                requiredText(release, "releaseIdentity"),
                "Contracts release identity");
        JsonNode canonicalRelease = release.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) canonicalRelease).putNull("releaseIdentity");
        try {
            byte[] canonicalBytes = new org.erdtman.jcs.JsonCanonicalizer(
                    canonicalRelease.toString()).getEncodedUTF8();
            String recomputed = "sha256:" + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(canonicalBytes));
            if (!contractsRelease.equals(recomputed)) {
                throw new GradleException("Contracts release identity does not authenticate its manifest bytes");
            }
        } catch (IOException | java.security.NoSuchAlgorithmException invalid) {
            throw new GradleException("Cannot authenticate the Contracts release manifest", invalid);
        }
        return new Bindings(
                commit,
                sourceTree,
                sourceDirty,
                builtWithJava,
                specification,
                fixtures,
                contractsRelease);
    }

    /**
     * Creates one repository without ever replacing an existing target. An existing target is
     * accepted only when every path and byte is already identical.
     */
    public static void assemble(
            Path sourceRepository,
            Path targetRepository,
            String group,
            String version,
            List<String> artifacts,
            Bindings bindings) {
        Path source = sourceRepository.toAbsolutePath().normalize();
        Path target = targetRepository.toAbsolutePath().normalize();
        if (source.equals(target)
                || source.startsWith(target)
                || target.startsWith(source)) {
            throw new GradleException(
                    "Mutable and immutable staged repositories must be disjoint");
        }
        if (!Files.isDirectory(source)) {
            throw new GradleException("Mutable staged repository does not exist: " + source);
        }
        Path parent = target.getParent();
        if (parent == null) {
            throw new GradleException("Immutable staged repository has no parent: " + target);
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException exception) {
            throw new GradleException("Cannot create staged repository parent " + parent, exception);
        }
        Path temporary = parent.resolve(target.getFileName()
                + ".assembling-" + UUID.randomUUID());
        try {
            Files.createDirectory(temporary);
            copyArtifacts(source, temporary, group, version, artifacts);
            Bindings checked = requireBindings(bindings);
            String manifest = DeterministicJson.write(manifest(
                    temporary, group, version, artifacts, checked));
            write(temporary.resolve(MANIFEST_FILE), manifest);
            writeChecksum(temporary.resolve(MANIFEST_FILE));
            Verification verification = verify(
                    temporary, group, version, artifacts, checked);
            if (!verification.getViolations().isEmpty()) {
                throw new GradleException("Cannot assemble staged repository: "
                        + String.join("; ", verification.getViolations()));
            }
            if (Files.exists(target)) {
                if (!sameTree(temporary, target)) {
                    throw new GradleException(
                            "Immutable staged repository already exists with different bytes: "
                                    + target);
                }
                deleteTree(temporary);
                return;
            }
            moveDirectory(temporary, target);
        } catch (IOException exception) {
            deleteTreeQuietly(temporary);
            throw new GradleException(
                    "Cannot assemble immutable staged repository " + target, exception);
        } catch (RuntimeException exception) {
            deleteTreeQuietly(temporary);
            throw exception;
        }
    }

    /** Verifies exact payloads, checksum companions, manifest bytes, and repository closure. */
    public static Verification verify(
            Path repository,
            String group,
            String version,
            List<String> artifacts,
            Bindings bindings) {
        List<String> violations = new ArrayList<>();
        Path root = repository.toAbsolutePath().normalize();
        Map<String, Object> expectedManifest = null;
        try {
            expectedManifest = manifest(
                    root, group, version, artifacts, requireBindings(bindings));
        } catch (RuntimeException exception) {
            violations.add(exception.getMessage());
        }

        Set<String> expectedPaths = expectedPaths(group, version, artifacts);
        for (String path : expectedPaths) {
            Path payload = root.resolve(path);
            if (!Files.isRegularFile(payload) || Files.isSymbolicLink(payload)) {
                violations.add("missing or symbolic staged artifact " + path);
                continue;
            }
            String checksumPath = path + ".sha256";
            Path checksum = root.resolve(checksumPath);
            if (!Files.isRegularFile(checksum) || Files.isSymbolicLink(checksum)) {
                violations.add("missing or symbolic staged checksum " + checksumPath);
                continue;
            }
            String expected = checksumText(payload);
            if (!expected.equals(read(checksum))) {
                violations.add("staged checksum mismatch " + checksumPath);
            }
        }

        Path manifestPath = root.resolve(MANIFEST_FILE);
        Path manifestChecksum = root.resolve(MANIFEST_CHECKSUM_FILE);
        if (!Files.isRegularFile(manifestPath) || Files.isSymbolicLink(manifestPath)) {
            violations.add("missing or symbolic staged artifact manifest");
        } else if (expectedManifest != null
                && !DeterministicJson.write(expectedManifest).equals(read(manifestPath))) {
            violations.add("staged artifact manifest does not match exact payloads and bindings");
        }
        if (!Files.isRegularFile(manifestChecksum)
                || Files.isSymbolicLink(manifestChecksum)) {
            violations.add("missing or symbolic staged artifact manifest checksum");
        } else if (Files.isRegularFile(manifestPath)
                && !checksumText(manifestPath).equals(read(manifestChecksum))) {
            violations.add("staged artifact manifest checksum mismatch");
        }

        Set<String> completeExpected = new LinkedHashSet<>();
        for (String path : expectedPaths) {
            completeExpected.add(path);
            completeExpected.add(path + ".sha256");
        }
        completeExpected.add(MANIFEST_FILE);
        completeExpected.add(MANIFEST_CHECKSUM_FILE);
        Set<String> actual = regularFilePaths(root, violations);
        for (String extra : actual) {
            if (!completeExpected.contains(extra)) {
                violations.add("unexpected staged repository file " + extra);
            }
        }
        for (String missing : completeExpected) {
            if (!actual.contains(missing)) {
                violations.add("missing staged repository file " + missing);
            }
        }
        String manifestIdentity = Files.isRegularFile(manifestPath)
                ? DeterministicHashing.sha256(manifestPath) : null;
        return new Verification(manifestIdentity, violations);
    }

    private static void copyArtifacts(
            Path source,
            Path target,
            String group,
            String version,
            List<String> artifacts) throws IOException {
        for (ArtifactFile artifact : artifactFiles(group, version, artifacts)) {
            Path input = source.resolve(artifact.path);
            if (!Files.isRegularFile(input) || Files.isSymbolicLink(input)) {
                throw new GradleException("Required staged publication file is missing: " + input);
            }
            Path output = target.resolve(artifact.path);
            Files.createDirectories(output.getParent());
            Files.copy(input, output, StandardCopyOption.COPY_ATTRIBUTES);
            writeChecksum(output);
        }
    }

    private static Map<String, Object> manifest(
            Path repository,
            String group,
            String version,
            List<String> artifacts,
            Bindings bindings) {
        String checkedGroup = mavenGroup(group);
        String checkedVersion = mavenToken(version, "version");
        String expectedVersion = bindings.isSourceDirty()
                ? "3.1.0-dev.tree." + bindings.getSourceTree()
                : "3.1.0-dev." + bindings.getSourceCommit();
        boolean localRc = CommitBoundDevelopmentCandidate.isLocalRc(checkedVersion);
        if (localRc && bindings.isSourceDirty()) {
            throw new GradleException("Local RC repository requires clean source provenance");
        }
        if (!localRc && !checkedVersion.equals(expectedVersion)) {
            throw new GradleException(
                    "Development repository version is not bound to exact source provenance: "
                            + checkedVersion + " != " + expectedVersion);
        }
        List<Map<String, Object>> records = new ArrayList<>();
        for (ArtifactFile artifact : artifactFiles(
                checkedGroup, checkedVersion, artifacts)) {
            Path file = repository.resolve(artifact.path);
            if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
                throw new GradleException("Required staged artifact is missing: " + artifact.path);
            }
            Map<String, Object> record = new TreeMap<>();
            record.put("bytes", size(file));
            record.put("checksumPath", artifact.path + ".sha256");
            record.put("coordinate", checkedGroup + ":" + artifact.artifactId
                    + ":" + checkedVersion);
            record.put("kind", artifact.kind);
            record.put("path", artifact.path);
            record.put("sha256", DeterministicHashing.sha256(file));
            records.add(record);
        }
        records.sort(Comparator
                .comparing((Map<String, Object> record) ->
                        String.valueOf(record.get("coordinate")))
                .thenComparing(record -> String.valueOf(record.get("kind")))
                .thenComparing(record -> String.valueOf(record.get("path"))));
        Map<String, Object> manifest = new TreeMap<>();
        manifest.put("artifacts", records);
        manifest.put("builtWithJava", bindings.getBuiltWithJava());
        manifest.put("contractsFixturePackageIdentity",
                bindings.getContractsFixturePackageIdentity());
        manifest.put("contractsReleaseIdentity", bindings.getContractsReleaseIdentity());
        manifest.put("contractsSpecificationIdentity",
                bindings.getContractsSpecificationIdentity());
        manifest.put("groupId", checkedGroup);
        manifest.put("releaseReadinessClaimed", false);
        manifest.put("schema", localRc ? LOCAL_RC_SCHEMA : SCHEMA);
        manifest.put("sourceCommit", bindings.getSourceCommit());
        manifest.put("sourceDirty", bindings.isSourceDirty());
        manifest.put("sourceTree", bindings.getSourceTree());
        manifest.put("stagePurpose", localRc ? "LOCAL_RC" : "DEVELOPMENT");
        manifest.put("version", checkedVersion);
        return manifest;
    }

    private static List<ArtifactFile> artifactFiles(
            String group,
            String version,
            List<String> artifacts) {
        String checkedGroup = mavenGroup(group);
        String checkedVersion = mavenToken(version, "version");
        Set<String> unique = new LinkedHashSet<>();
        for (String artifact : artifacts) {
            String checked = mavenToken(artifact, "artifact");
            if (!unique.add(checked)) {
                throw new GradleException("Duplicate staged artifact " + checked);
            }
        }
        List<ArtifactFile> files = new ArrayList<>();
        String groupPath = checkedGroup.replace('.', '/');
        List<String> sorted = new ArrayList<>(unique);
        Collections.sort(sorted);
        for (String artifact : sorted) {
            String base = groupPath + "/" + artifact + "/" + checkedVersion
                    + "/" + artifact + "-" + checkedVersion;
            for (ArtifactKind kind : CommitBoundDevelopmentCandidate.isLocalRc(checkedVersion)
                    ? LOCAL_RC_KINDS : KINDS) {
                files.add(new ArtifactFile(artifact, kind.name, base + kind.suffix));
            }
        }
        return files;
    }

    private static Set<String> expectedPaths(
            String group, String version, List<String> artifacts) {
        return artifactFiles(group, version, artifacts).stream()
                .map(file -> file.path)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> regularFilePaths(Path root, List<String> violations) {
        if (!Files.isDirectory(root)) {
            violations.add("staged repository does not exist " + root);
            return Collections.emptySet();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> !path.equals(root))
                    .filter(path -> {
                        if (Files.isSymbolicLink(path)) {
                            violations.add("symbolic link in staged repository "
                                    + relative(root, path));
                            return false;
                        }
                        return Files.isRegularFile(path);
                    })
                    .map(path -> relative(root, path))
                    .sorted()
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (IOException exception) {
            throw new GradleException("Cannot inspect staged repository " + root, exception);
        }
    }

    private static boolean sameTree(Path left, Path right) {
        List<String> violations = new ArrayList<>();
        Set<String> leftPaths = regularFilePaths(left, violations);
        Set<String> rightPaths = regularFilePaths(right, violations);
        if (!violations.isEmpty() || !leftPaths.equals(rightPaths)) {
            return false;
        }
        for (String path : leftPaths) {
            try {
                if (Files.mismatch(left.resolve(path), right.resolve(path)) != -1L) {
                    return false;
                }
            } catch (IOException exception) {
                throw new GradleException("Cannot compare staged repositories", exception);
            }
        }
        return true;
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void writeChecksum(Path input) throws IOException {
        write(input.resolveSibling(input.getFileName() + ".sha256"), checksumText(input));
    }

    private static String checksumText(Path input) {
        String identity = DeterministicHashing.sha256(input);
        return identity.substring("sha256:".length()) + "  " + input.getFileName() + "\n";
    }

    private static void write(Path output, String content) throws IOException {
        Files.createDirectories(output.getParent());
        Files.writeString(output, content, StandardCharsets.UTF_8);
    }

    private static String read(Path input) {
        try {
            return Files.readString(input, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot read staged repository file " + input, exception);
        }
    }

    private static long size(Path input) {
        try {
            return Files.size(input);
        } catch (IOException exception) {
            throw new GradleException("Cannot read staged artifact size " + input, exception);
        }
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    private static String requiredText(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isTextual() || value.asText().trim().isEmpty()) {
            throw new GradleException("Contracts release manifest is missing " + field);
        }
        return value.asText().trim();
    }

    private static String sha256Identity(String value, String label) {
        if (!SHA_256.matcher(value).matches()) {
            throw new GradleException("Contracts release manifest has invalid " + label);
        }
        return value;
    }

    private static String gitIdentity(String value) {
        String commit = oneLine(value, "source commit");
        if (!COMMIT.matcher(commit).matches()) {
            throw new GradleException("Staged repository source commit is not a Git identity");
        }
        return commit;
    }

    private static String treeIdentity(String value) {
        String tree = oneLine(value, "source tree");
        if (!TREE.matcher(tree).matches()) {
            throw new GradleException(
                    "Development repository source tree is not a 40-character Git identity");
        }
        return tree;
    }

    private static int buildJava(int value) {
        if (value != REQUIRED_BUILD_JAVA) {
            throw new GradleException(
                    "Development repository must be built with Java "
                            + REQUIRED_BUILD_JAVA + "; received Java " + value);
        }
        return value;
    }

    private static String mavenGroup(String value) {
        String group = oneLine(value, "group");
        if (!GROUP.matcher(group).matches()) {
            throw new GradleException("Staged repository group is not a safe Maven group ID");
        }
        return group;
    }

    private static String mavenToken(String value, String label) {
        String token = oneLine(value, label);
        if (!MAVEN_TOKEN.matcher(token).matches()) {
            throw new GradleException(
                    "Staged repository " + label + " is not a safe Maven coordinate token");
        }
        return token;
    }

    private static Bindings requireBindings(Bindings bindings) {
        if (bindings == null) {
            throw new GradleException("Staged repository release bindings are missing");
        }
        return bindings;
    }

    private static String oneLine(String value, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.contains("\n") || normalized.contains("\r")) {
            throw new GradleException("Staged repository " + label + " must be one line");
        }
        return normalized;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTreeQuietly(Path root) {
        try {
            deleteTree(root);
        } catch (IOException ignored) {
            // Preserve the primary staging failure; the UUID path remains invocation-owned.
        }
    }

    /** Exact source and semantic identities bound into the staged artifact manifest. */
    public static final class Bindings {

        private final String sourceCommit;
        private final String sourceTree;
        private final boolean sourceDirty;
        private final int builtWithJava;
        private final String contractsSpecificationIdentity;
        private final String contractsFixturePackageIdentity;
        private final String contractsReleaseIdentity;

        public Bindings(
                String sourceCommit,
                String sourceTree,
                boolean sourceDirty,
                int builtWithJava,
                String contractsSpecificationIdentity,
                String contractsFixturePackageIdentity,
                String contractsReleaseIdentity) {
            this.sourceCommit = gitIdentity(sourceCommit);
            this.sourceTree = treeIdentity(sourceTree);
            this.sourceDirty = sourceDirty;
            this.builtWithJava = buildJava(builtWithJava);
            this.contractsSpecificationIdentity = sha256Identity(
                    contractsSpecificationIdentity, "specification identity");
            this.contractsFixturePackageIdentity = sha256Identity(
                    contractsFixturePackageIdentity, "fixture package identity");
            this.contractsReleaseIdentity = sha256Identity(
                    contractsReleaseIdentity, "release identity");
        }

        public String getSourceCommit() {
            return sourceCommit;
        }

        public String getSourceTree() {
            return sourceTree;
        }

        public boolean isSourceDirty() {
            return sourceDirty;
        }

        public int getBuiltWithJava() {
            return builtWithJava;
        }

        public String getContractsSpecificationIdentity() {
            return contractsSpecificationIdentity;
        }

        public String getContractsFixturePackageIdentity() {
            return contractsFixturePackageIdentity;
        }

        public String getContractsReleaseIdentity() {
            return contractsReleaseIdentity;
        }
    }

    /** Verification result whose manifest identity is deliberately external to manifest bytes. */
    public static final class Verification {

        private final String manifestIdentity;
        private final List<String> violations;

        private Verification(String manifestIdentity, List<String> violations) {
            this.manifestIdentity = manifestIdentity;
            this.violations = Collections.unmodifiableList(new ArrayList<>(violations));
        }

        public String getManifestIdentity() {
            return manifestIdentity;
        }

        public List<String> getViolations() {
            return violations;
        }
    }

    private static final class ArtifactKind {

        private final String name;
        private final String suffix;

        private ArtifactKind(String name, String suffix) {
            this.name = name;
            this.suffix = suffix;
        }
    }

    private static final class ArtifactFile {

        private final String artifactId;
        private final String kind;
        private final String path;

        private ArtifactFile(String artifactId, String kind, String path) {
            this.artifactId = artifactId;
            this.kind = kind;
            this.path = path;
        }
    }
}
