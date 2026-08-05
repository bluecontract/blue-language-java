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
import java.util.Comparator;
import java.util.List;
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
        Path normalizedRoot = realPath(root, "snapshot root");
        List<SourceSnapshot.Entry> entries = new ArrayList<>();
        for (Path input : inputs) {
            Path normalizedInput = realPath(input, "snapshot input");
            if (!Files.isRegularFile(normalizedInput)) {
                continue;
            }
            if (!normalizedInput.startsWith(normalizedRoot)) {
                throw new GradleException(
                        "Snapshot input is outside its declared root: " + normalizedInput);
            }
            String relativePath = normalizedRoot.relativize(normalizedInput).toString()
                    .replace(input.getFileSystem().getSeparator(), "/");
            entries.add(new SourceSnapshot.Entry(relativePath, sha256(normalizedInput)));
        }
        entries.sort(Comparator.comparing(SourceSnapshot.Entry::getPath));

        MessageDigest digest = sha256Digest();
        for (SourceSnapshot.Entry entry : entries) {
            String record = entry.getPath() + '\0' + entry.getIdentity() + '\n';
            digest.update(record.getBytes(StandardCharsets.UTF_8));
        }
        return new SourceSnapshot(identity(digest.digest()), entries);
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
}
