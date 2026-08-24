package blue.language.conformance.contracts;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Filesystem boundary and invariant helpers for deterministic export. */
final class FullLifecycleFixtureFiles {

    private FullLifecycleFixtureFiles() {
    }

static Path existingAbsoluteDirectory(Path value, String label) {
    Objects.requireNonNull(value, label);
    require(value.isAbsolute(), label + " must be absolute");
    Path normalized = value.normalize();
    require(Files.isDirectory(normalized),
            label + " must be an existing directory");
    return normalized;
}

static Path emptyAbsoluteDirectory(Path value, String label)
        throws IOException {
    Objects.requireNonNull(value, label);
    require(value.isAbsolute(), label + " must be absolute");
    Path normalized = value.normalize();
    if (!Files.exists(normalized)) {
        Path parent = normalized.getParent();
        require(parent != null && Files.isDirectory(parent),
                label + " parent must be an existing directory");
        Files.createDirectory(normalized);
    }
    require(Files.isDirectory(normalized),
            label + " must be a directory");
    try (DirectoryStream<Path> entries =
                 Files.newDirectoryStream(normalized)) {
        require(!entries.iterator().hasNext(), label + " must be empty");
    }
    return normalized;
}

static void deleteTree(Path root) throws IOException {
    if (root == null || !Files.exists(root)) {
        return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
        List<Path> reversed = paths.sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());
        for (Path path : reversed) {
            Files.deleteIfExists(path);
        }
    }
}

static void require(boolean condition, String message) {
    if (!condition) {
        throw new IllegalArgumentException(message);
    }
}
}
