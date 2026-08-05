package blue.buildlogic.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.api.GradleException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Creates a stable, dependency-free description of the public binary API in class artifacts. */
public final class JavaPublicApiInventory {

    public static final String SCHEMA = "blue-java-public-api/1.0";

    private static final String CLASS_SUFFIX = ".class";
    private static final String JAR_SUFFIX = ".jar";
    private static final String MODULE_DESCRIPTOR = "module-info";
    private static final String PACKAGE_DESCRIPTOR = "package-info";

    private JavaPublicApiInventory() {}

    /** Inventories class directories, individual class files, and JARs in host-independent order. */
    public static List<String> inspect(Collection<Path> compiledInputs) {
        Set<String> entries = new TreeSet<>();
        sorted(compiledInputs).forEach(input -> inspectInput(input, entries::addAll));
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** Reads and unions existing line-oriented inventories, ignoring their comment headers. */
    public static List<String> union(
            Collection<String> generatedEntries, Collection<Path> inventoryFiles) {
        Set<String> entries = new TreeSet<>(generatedEntries);
        for (Path inventory : sorted(inventoryFiles)) {
            try {
                for (String line : Files.readAllLines(inventory, StandardCharsets.UTF_8)) {
                    String normalized = line.trim();
                    if (!normalized.isEmpty() && !normalized.startsWith("#")) {
                        entries.add(normalized);
                    }
                }
            } catch (IOException exception) {
                throw new GradleException("Cannot read Java API inventory: " + inventory, exception);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** Encodes inventory entries using a stable comment header followed by sorted API records. */
    public static String write(String moduleName, Collection<String> entries) {
        String normalizedModule = requireHeaderValue(moduleName, "module name");
        List<String> sortedEntries = new ArrayList<>(new TreeSet<>(entries));
        StringBuilder output = new StringBuilder();
        output.append("# schema: ").append(SCHEMA).append('\n');
        output.append("# module: ").append(normalizedModule).append('\n');
        output.append("# entryCount: ").append(sortedEntries.size()).append('\n');
        for (String entry : sortedEntries) {
            output.append(entry).append('\n');
        }
        return output.toString();
    }

    private static void inspectInput(Path input, Consumer<List<String>> consumer) {
        if (Files.isDirectory(input)) {
            inspectDirectory(input, consumer);
        } else if (Files.isRegularFile(input)
                && input.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(JAR_SUFFIX)) {
            inspectJar(input, consumer);
        } else if (Files.isRegularFile(input)
                && input.getFileName().toString().endsWith(CLASS_SUFFIX)) {
            consumer.accept(inspectClass(read(input), input.toString()));
        } else {
            throw new GradleException("Unsupported Java API inventory input: " + input);
        }
    }

    private static void inspectDirectory(Path directory, Consumer<List<String>> consumer) {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(CLASS_SUFFIX))
                    .sorted(Comparator.comparing(path -> normalizedRelativePath(directory, path)))
                    .forEach(path -> consumer.accept(inspectClass(read(path), path.toString())));
        } catch (IOException exception) {
            throw new GradleException("Cannot inspect compiled class directory: " + directory, exception);
        }
    }

    private static void inspectJar(Path jar, Consumer<List<String>> consumer) {
        try (ZipFile archive = new ZipFile(jar.toFile())) {
            List<? extends ZipEntry> entries = Collections.list(archive.entries());
            entries.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().endsWith(CLASS_SUFFIX))
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .forEach(entry -> consumer.accept(inspectClass(
                            read(archive, entry), jar + "!" + entry.getName())));
        } catch (IOException exception) {
            throw new GradleException("Cannot inspect compiled Java archive: " + jar, exception);
        }
    }

    private static List<String> inspectClass(byte[] bytes, String source) {
        ApiClassVisitor visitor = new ApiClassVisitor();
        try {
            new ClassReader(bytes).accept(
                    visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return visitor.entries();
        } catch (RuntimeException exception) {
            throw new GradleException("Cannot inspect compiled class: " + source, exception);
        }
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new GradleException("Cannot read compiled class: " + file, exception);
        }
    }

    private static byte[] read(ZipFile archive, ZipEntry entry) {
        try (InputStream input = archive.getInputStream(entry)) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new GradleException("Cannot read archive class entry: " + entry.getName(), exception);
        }
    }

    private static List<Path> sorted(Collection<Path> paths) {
        List<Path> sorted = new ArrayList<>(paths);
        sorted.sort(Comparator.comparing(path -> path.toAbsolutePath().normalize().toString()));
        return sorted;
    }

    private static String normalizedRelativePath(Path root, Path file) {
        return root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
    }

    private static String requireHeaderValue(String value, String description) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new GradleException("Java API inventory " + description + " must be one non-empty line");
        }
        return normalized;
    }

    private static boolean isApiVisible(int access) {
        return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0;
    }

    private static boolean isSynthetic(int access) {
        return (access & Opcodes.ACC_SYNTHETIC) != 0;
    }

    private static String binaryName(String internalName) {
        return internalName == null ? "" : internalName.replace('/', '.');
    }

    private static String typeAccess(int access) {
        List<String> flags = new ArrayList<>();
        addFlag(flags, access, Opcodes.ACC_PUBLIC, "public");
        addFlag(flags, access, Opcodes.ACC_PROTECTED, "protected");
        addFlag(flags, access, Opcodes.ACC_ABSTRACT, "abstract");
        addFlag(flags, access, Opcodes.ACC_FINAL, "final");
        addFlag(flags, access, Opcodes.ACC_INTERFACE, "interface");
        addFlag(flags, access, Opcodes.ACC_ANNOTATION, "annotation");
        addFlag(flags, access, Opcodes.ACC_ENUM, "enum");
        addFlag(flags, access, Opcodes.ACC_RECORD, "record");
        return String.join(",", flags);
    }

    private static String fieldAccess(int access) {
        List<String> flags = new ArrayList<>();
        addFlag(flags, access, Opcodes.ACC_PUBLIC, "public");
        addFlag(flags, access, Opcodes.ACC_PROTECTED, "protected");
        addFlag(flags, access, Opcodes.ACC_STATIC, "static");
        addFlag(flags, access, Opcodes.ACC_FINAL, "final");
        addFlag(flags, access, Opcodes.ACC_TRANSIENT, "transient");
        addFlag(flags, access, Opcodes.ACC_VOLATILE, "volatile");
        addFlag(flags, access, Opcodes.ACC_ENUM, "enum");
        return String.join(",", flags);
    }

    private static String methodAccess(int access) {
        List<String> flags = new ArrayList<>();
        addFlag(flags, access, Opcodes.ACC_PUBLIC, "public");
        addFlag(flags, access, Opcodes.ACC_PROTECTED, "protected");
        addFlag(flags, access, Opcodes.ACC_STATIC, "static");
        addFlag(flags, access, Opcodes.ACC_ABSTRACT, "abstract");
        addFlag(flags, access, Opcodes.ACC_FINAL, "final");
        addFlag(flags, access, Opcodes.ACC_SYNCHRONIZED, "synchronized");
        addFlag(flags, access, Opcodes.ACC_NATIVE, "native");
        addFlag(flags, access, Opcodes.ACC_STRICT, "strictfp");
        addFlag(flags, access, Opcodes.ACC_VARARGS, "varargs");
        return String.join(",", flags);
    }

    private static void addFlag(List<String> flags, int access, int flag, String name) {
        if ((access & flag) != 0) {
            flags.add(name);
        }
    }

    private static String nullable(String value) {
        return value == null ? "-" : value;
    }

    private static String names(String[] internalNames) {
        if (internalNames == null || internalNames.length == 0) {
            return "-";
        }
        List<String> names = new ArrayList<>();
        for (String name : internalNames) {
            names.add(binaryName(name));
        }
        Collections.sort(names);
        return String.join(",", names);
    }

    private static String constant(Object value) {
        if (value == null) {
            return "-";
        }
        return DeterministicJson.write(value).trim();
    }

    private static final class ApiClassVisitor extends ClassVisitor {

        private final List<String> entries = new ArrayList<>();
        private String owner;
        private boolean visible;

        private ApiClassVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(
                int version,
                int access,
                String name,
                String signature,
                String superName,
                String[] interfaces) {
            owner = binaryName(name);
            visible = isApiVisible(access)
                    && !isSynthetic(access)
                    && !name.endsWith(MODULE_DESCRIPTOR)
                    && !name.endsWith(PACKAGE_DESCRIPTOR);
            if (visible) {
                entries.add("type " + owner
                        + " access=" + typeAccess(access)
                        + " super=" + nullable(binaryName(superName))
                        + " interfaces=" + names(interfaces)
                        + " signature=" + nullable(signature));
            }
        }

        @Override
        public FieldVisitor visitField(
                int access, String name, String descriptor, String signature, Object value) {
            if (visible && isApiVisible(access) && !isSynthetic(access)) {
                entries.add("field " + owner + "#" + name
                        + " descriptor=" + descriptor
                        + " access=" + fieldAccess(access)
                        + " signature=" + nullable(signature)
                        + " constant=" + constant(value));
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(
                int access,
                String name,
                String descriptor,
                String signature,
                String[] exceptions) {
            if (visible
                    && isApiVisible(access)
                    && !isSynthetic(access)
                    && (access & Opcodes.ACC_BRIDGE) == 0) {
                entries.add("method " + owner + "#" + name
                        + " descriptor=" + descriptor
                        + " access=" + methodAccess(access)
                        + " signature=" + nullable(signature)
                        + " throws=" + names(exceptions));
            }
            return null;
        }

        private List<String> entries() {
            return entries;
        }
    }
}
