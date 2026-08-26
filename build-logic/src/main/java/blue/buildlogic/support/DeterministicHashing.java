package blue.buildlogic.support;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.gradle.api.GradleException;

/** Content hashing whose result is independent of filesystem enumeration order and host paths. */
public final class DeterministicHashing {

    private static final int BUFFER_SIZE = 8192;

    private DeterministicHashing() {}

    /** Returns the SHA-256 identity of one regular file. */
    public static String sha256(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new GradleException("Required input is not a regular file: " + file);
        }
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException exception) {
            throw new GradleException("Cannot hash input file: " + file, exception);
        }
        return identity(digest.digest());
    }

    /** Returns the SHA-256 identity of an in-memory deterministic artifact. */
    public static String sha256(byte[] bytes) {
        MessageDigest digest = sha256Digest();
        digest.update(bytes);
        return identity(digest.digest());
    }

    /**
     * Creates an identity from normalized relative path/content-identity records sorted by path.
     */
    public static SourceSnapshot snapshot(Path root, Collection<Path> inputs) {
        return snapshot(Collections.singletonMap("", root), inputs);
    }

    /**
     * Creates an identity across declared roots whose stable logical prefixes replace host paths.
     */
    public static SourceSnapshot snapshot(
            Map<String, Path> rootsByLogicalPrefix, Collection<Path> inputs) {
        List<DeclaredRoot> roots = declaredRoots(rootsByLogicalPrefix);
        Map<String, Path> inputByLogicalPath = new HashMap<>();
        Map<String, SourceSnapshot.Entry> entryByLogicalPath = new TreeMap<>();
        for (Path input : inputs) {
            Path normalizedInput = realPath(input, "snapshot input");
            if (!Files.isRegularFile(normalizedInput)) {
                continue;
            }
            DeclaredRoot declaredRoot = rootFor(normalizedInput, roots);
            if (declaredRoot == null) {
                throw new GradleException(
                        "Snapshot input is outside its declared root: " + normalizedInput);
            }
            String relativePath = declaredRoot.path.relativize(normalizedInput).toString()
                    .replace(normalizedInput.getFileSystem().getSeparator(), "/");
            String logicalPath = declaredRoot.logicalPrefix.isEmpty()
                    ? relativePath
                    : declaredRoot.logicalPrefix + "/" + relativePath;
            Path previous = inputByLogicalPath.putIfAbsent(logicalPath, normalizedInput);
            if (previous != null) {
                if (previous.equals(normalizedInput)) {
                    continue;
                }
                throw new GradleException(
                        "Snapshot inputs have the same logical path: " + logicalPath);
            }
            entryByLogicalPath.put(
                    logicalPath,
                    new SourceSnapshot.Entry(
                            logicalPath, sha256(normalizedInput), size(normalizedInput)));
        }
        List<SourceSnapshot.Entry> entries = new ArrayList<>(entryByLogicalPath.values());

        MessageDigest digest = sha256Digest();
        for (SourceSnapshot.Entry entry : entries) {
            String record = entry.getPath() + '\0' + entry.getIdentity() + '\n';
            digest.update(record.getBytes(StandardCharsets.UTF_8));
        }
        return new SourceSnapshot(identity(digest.digest()), entries);
    }

    private static List<DeclaredRoot> declaredRoots(Map<String, Path> rootsByLogicalPrefix) {
        Objects.requireNonNull(rootsByLogicalPrefix, "rootsByLogicalPrefix");
        if (rootsByLogicalPrefix.isEmpty()) {
            throw new GradleException("At least one snapshot root must be declared");
        }
        List<DeclaredRoot> roots = new ArrayList<>();
        Map<Path, String> prefixByRoot = new HashMap<>();
        for (Map.Entry<String, Path> entry : rootsByLogicalPrefix.entrySet()) {
            String logicalPrefix = logicalPrefix(entry.getKey());
            Path root = realPath(
                    Objects.requireNonNull(entry.getValue(), "snapshot root"), "snapshot root");
            String previous = prefixByRoot.putIfAbsent(root, logicalPrefix);
            if (previous != null) {
                throw new GradleException(
                        "Snapshot root is declared more than once: " + root);
            }
            roots.add(new DeclaredRoot(logicalPrefix, root));
        }
        roots.sort(Comparator.comparingInt((DeclaredRoot root) -> root.path.getNameCount())
                .reversed()
                .thenComparing(root -> root.logicalPrefix));
        return roots;
    }

    private static DeclaredRoot rootFor(Path input, List<DeclaredRoot> roots) {
        for (DeclaredRoot root : roots) {
            if (input.startsWith(root.path)) {
                return root;
            }
        }
        return null;
    }

    private static String logicalPrefix(String value) {
        String prefix = Objects.requireNonNull(value, "snapshot logical prefix");
        if (prefix.isEmpty()) {
            return prefix;
        }
        if (!prefix.matches("[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*)*")) {
            throw new GradleException("Invalid snapshot logical prefix: " + prefix);
        }
        return prefix;
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException exception) {
            throw new GradleException("Cannot read snapshot input size: " + file, exception);
        }
    }

    private static Path realPath(Path path, String description) {
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            throw new GradleException("Cannot resolve " + description + ": " + path, exception);
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The JVM does not provide SHA-256", exception);
        }
    }

    private static String identity(byte[] bytes) {
        StringBuilder result = new StringBuilder("sha256:");
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static final class DeclaredRoot {

        private final String logicalPrefix;
        private final Path path;

        private DeclaredRoot(String logicalPrefix, Path path) {
            this.logicalPrefix = logicalPrefix;
            this.path = path;
        }
    }
}
