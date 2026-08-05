package blue.buildlogic.support;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.api.GradleException;

/** Structural verification for portable paths and normalized JAR/ZIP timestamps. */
public final class ReproducibleArchiveInspector {

    private ReproducibleArchiveInspector() {}

    public static void verify(Path archive) {
        Set<String> uniqueNames = new HashSet<>();
        Set<Long> timestamps = new TreeSet<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!uniqueNames.add(name)) {
                    throw failure(archive, "duplicate entry '" + name + "'");
                }
                if (name.startsWith("/") || name.contains("\\") || hasParentTraversal(name)) {
                    throw failure(archive, "non-portable entry path '" + name + "'");
                }
                timestamps.add(entry.getTime());
            }
        } catch (IOException exception) {
            throw new GradleException("Cannot inspect archive: " + archive, exception);
        }

        if (timestamps.size() > 1) {
            throw failure(archive, "entries do not share one normalized timestamp");
        }
    }

    private static boolean hasParentTraversal(String name) {
        return name.equals("..") || name.startsWith("../") || name.contains("/../");
    }

    private static GradleException failure(Path archive, String detail) {
        return new GradleException("Archive is not reproducible (" + detail + "): " + archive);
    }
}
