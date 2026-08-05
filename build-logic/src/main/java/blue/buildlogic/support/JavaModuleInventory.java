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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.api.GradleException;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

/** Deterministic module ownership inventory derived from class artifacts or Java sources. */
public final class JavaModuleInventory {

    public static final String SCHEMA = "blue-java-module-inventory/1.0";

    private static final String CLASS_SUFFIX = ".class";
    private static final String JAVA_SUFFIX = ".java";
    private static final String JAR_SUFFIX = ".jar";
    private static final String RECORD_SCHEMA = "schema";
    private static final String RECORD_MODULE = "module";
    private static final String RECORD_PACKAGE = "package";
    private static final String RECORD_CLASS = "class";
    private static final String RECORD_REFERENCE = "reference";
    private static final String RECORD_SEPARATOR = "\t";
    private static final String DEFAULT_PACKAGE = "<default>";
    private static final String MODULE_DESCRIPTOR = "module-info";
    private static final int RECORD_FIELD_COUNT = 2;
    private static final int CONSTANT_CLASS_TAG = 7;
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][A-Za-z0-9_$.]*)\\s*;");
    private static final Pattern IMPORT_DECLARATION = Pattern.compile(
            "(?m)^\\s*import\\s+(?:static\\s+)?([A-Za-z_$][A-Za-z0-9_$.*]*)\\s*;");

    private JavaModuleInventory() {}

    /** Builds one module inventory from compiled artifacts and optional source inputs. */
    public static Inventory inspect(
            String moduleName, Collection<Path> compiledInputs, Collection<Path> sourceInputs) {
        InventoryBuilder builder = new InventoryBuilder(requireValue(moduleName, "module name"));
        sorted(compiledInputs).forEach(path -> inspectCompiledInput(path, builder));
        sorted(sourceInputs).forEach(path -> inspectSourceInput(path, builder));
        return builder.build();
    }

    /** Parses an inventory emitted by {@link Inventory#write()}. */
    public static Inventory read(Path inventoryFile) {
        List<String> lines;
        try {
            lines = Files.readAllLines(inventoryFile, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot read Java module inventory: " + inventoryFile, exception);
        }
        String schema = null;
        String module = null;
        Set<String> packages = new TreeSet<>();
        Set<String> classes = new TreeSet<>();
        Set<String> references = new TreeSet<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) {
                continue;
            }
            String[] record = line.split(RECORD_SEPARATOR, -1);
            if (record.length != RECORD_FIELD_COUNT) {
                throw invalid(inventoryFile, index, "expected two tab-separated fields");
            }
            String value = requireValue(record[1], "inventory value");
            switch (record[0]) {
                case RECORD_SCHEMA:
                    schema = unique(schema, value, inventoryFile, index, RECORD_SCHEMA);
                    break;
                case RECORD_MODULE:
                    module = unique(module, value, inventoryFile, index, RECORD_MODULE);
                    break;
                case RECORD_PACKAGE:
                    packages.add(value);
                    break;
                case RECORD_CLASS:
                    classes.add(value);
                    break;
                case RECORD_REFERENCE:
                    references.add(value);
                    break;
                default:
                    throw invalid(inventoryFile, index, "unknown record type '" + record[0] + "'");
            }
        }
        if (!SCHEMA.equals(schema)) {
            throw new GradleException("Unsupported Java module inventory schema in " + inventoryFile);
        }
        if (module == null) {
            throw new GradleException("Java module inventory has no module record: " + inventoryFile);
        }
        return new Inventory(module, packages, classes, references);
    }

    private static void inspectCompiledInput(Path input, InventoryBuilder builder) {
        if (Files.isDirectory(input)) {
            try (Stream<Path> paths = Files.walk(input)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(CLASS_SUFFIX))
                        .sorted(Comparator.comparing(path -> normalizedRelativePath(input, path)))
                        .forEach(path -> inspectClass(readClassBytes(path), path.toString(), builder));
            } catch (IOException exception) {
                throw new GradleException("Cannot inspect compiled module directory: " + input, exception);
            }
            return;
        }
        if (Files.isRegularFile(input)
                && input.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(JAR_SUFFIX)) {
            inspectJar(input, builder);
            return;
        }
        if (Files.isRegularFile(input) && input.getFileName().toString().endsWith(CLASS_SUFFIX)) {
            inspectClass(readClassBytes(input), input.toString(), builder);
            return;
        }
        throw new GradleException("Unsupported Java module inventory input: " + input);
    }

    private static void inspectJar(Path jar, InventoryBuilder builder) {
        try (ZipFile archive = new ZipFile(jar.toFile())) {
            List<? extends ZipEntry> entries = Collections.list(archive.entries());
            entries.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().endsWith(CLASS_SUFFIX))
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .forEach(entry -> inspectClass(
                            read(archive, entry), jar + "!" + entry.getName(), builder));
        } catch (IOException exception) {
            throw new GradleException("Cannot inspect compiled module archive: " + jar, exception);
        }
    }

    private static void inspectClass(byte[] bytes, String source, InventoryBuilder builder) {
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassReferenceVisitor visitor = new ClassReferenceVisitor();
            reader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            builder.addClass(visitor.owner());
            builder.addReferences(visitor.references());
            collectConstantPoolClasses(reader, visitor.owner(), builder);
        } catch (RuntimeException exception) {
            throw new GradleException("Cannot inspect module class: " + source, exception);
        }
    }

    private static void collectConstantPoolClasses(
            ClassReader reader, String owner, InventoryBuilder builder) {
        char[] buffer = new char[reader.getMaxStringLength()];
        for (int index = 1; index < reader.getItemCount(); index++) {
            int offset = reader.getItem(index);
            if (offset == 0 || reader.readByte(offset - 1) != CONSTANT_CLASS_TAG) {
                continue;
            }
            String value = reader.readUTF8(offset, buffer);
            Set<String> references = new TreeSet<>();
            collectInternalOrDescriptor(value, references);
            references.remove(owner);
            builder.addReferences(references);
        }
    }

    private static void inspectSourceInput(Path input, InventoryBuilder builder) {
        if (Files.isDirectory(input)) {
            try (Stream<Path> paths = Files.walk(input)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(JAVA_SUFFIX))
                        .sorted(Comparator.comparing(path -> normalizedRelativePath(input, path)))
                        .forEach(path -> inspectJavaSource(path, builder));
            } catch (IOException exception) {
                throw new GradleException("Cannot inspect Java source directory: " + input, exception);
            }
            return;
        }
        if (Files.isRegularFile(input) && input.getFileName().toString().endsWith(JAVA_SUFFIX)) {
            inspectJavaSource(input, builder);
            return;
        }
        throw new GradleException("Unsupported Java source inventory input: " + input);
    }

    private static void inspectJavaSource(Path source, InventoryBuilder builder) {
        String content;
        try {
            content = Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot read Java source inventory input: " + source, exception);
        }
        Matcher packageMatcher = PACKAGE_DECLARATION.matcher(content);
        if (packageMatcher.find()) {
            builder.addPackage(packageMatcher.group(1));
        }
        Matcher importMatcher = IMPORT_DECLARATION.matcher(content);
        while (importMatcher.find()) {
            builder.addReference(importMatcher.group(1));
        }
    }

    private static byte[] readClassBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new GradleException("Cannot read module inventory input: " + file, exception);
        }
    }

    private static byte[] read(ZipFile archive, ZipEntry entry) {
        try (InputStream input = archive.getInputStream(entry)) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new GradleException("Cannot read module archive entry: " + entry.getName(), exception);
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

    private static String requireValue(String value, String description) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()
                || normalized.indexOf('\t') >= 0
                || normalized.indexOf('\n') >= 0
                || normalized.indexOf('\r') >= 0) {
            throw new GradleException("Java module " + description + " must be one non-empty field");
        }
        return normalized;
    }

    private static String unique(
            String current, String value, Path file, int index, String description) {
        if (current != null && !current.equals(value)) {
            throw invalid(file, index, "conflicting " + description + " record");
        }
        return value;
    }

    private static GradleException invalid(Path file, int zeroBasedLine, String detail) {
        return new GradleException("Invalid Java module inventory " + file + " at line "
                + (zeroBasedLine + 1) + ": " + detail);
    }

    private static void collectDescriptor(String descriptor, Set<String> references) {
        if (descriptor == null) {
            return;
        }
        try {
            collectType(Type.getType(descriptor), references);
        } catch (IllegalArgumentException exception) {
            throw new GradleException(
                    "Invalid class descriptor in module inventory: " + descriptor, exception);
        }
    }

    private static void collectType(Type type, Set<String> references) {
        switch (type.getSort()) {
            case Type.ARRAY:
                collectType(type.getElementType(), references);
                break;
            case Type.OBJECT:
                references.add(type.getClassName());
                break;
            case Type.METHOD:
                collectType(type.getReturnType(), references);
                for (Type argument : type.getArgumentTypes()) {
                    collectType(argument, references);
                }
                break;
            default:
                break;
        }
    }

    private static void collectInternalOrDescriptor(String value, Set<String> references) {
        if (value == null) {
            return;
        }
        if (value.startsWith("[")) {
            collectDescriptor(value, references);
        } else {
            references.add(value.replace('/', '.'));
        }
    }

    private static void collectSignature(String signature, Set<String> references) {
        if (signature == null) {
            return;
        }
        new SignatureReader(signature).accept(new SignatureVisitor(Opcodes.ASM9) {
            @Override
            public void visitClassType(String name) {
                collectInternalOrDescriptor(name, references);
            }
        });
    }

    /** Immutable, path-independent ownership and reference inventory for one module. */
    public static final class Inventory {

        private final String module;
        private final Set<String> packages;
        private final Set<String> classes;
        private final Set<String> references;

        private Inventory(
                String module,
                Collection<String> packages,
                Collection<String> classes,
                Collection<String> references) {
            this.module = module;
            this.packages = immutableSet(packages);
            this.classes = immutableSet(classes);
            this.references = immutableSet(references);
        }

        public String getModule() {
            return module;
        }

        public Set<String> getPackages() {
            return packages;
        }

        public Set<String> getClasses() {
            return classes;
        }

        public Set<String> getReferences() {
            return references;
        }

        /** Encodes a deterministic, intentionally simple tab-separated inventory. */
        public String write() {
            StringBuilder output = new StringBuilder();
            append(output, RECORD_SCHEMA, SCHEMA);
            append(output, RECORD_MODULE, module);
            packages.forEach(value -> append(output, RECORD_PACKAGE, value));
            classes.forEach(value -> append(output, RECORD_CLASS, value));
            references.forEach(value -> append(output, RECORD_REFERENCE, value));
            return output.toString();
        }

        private static void append(StringBuilder output, String record, String value) {
            output.append(record).append(RECORD_SEPARATOR).append(value).append('\n');
        }
    }

    private static final class InventoryBuilder {

        private final String module;
        private final Set<String> packages = new TreeSet<>();
        private final Set<String> classes = new TreeSet<>();
        private final Set<String> references = new TreeSet<>();

        private InventoryBuilder(String module) {
            this.module = module;
        }

        private void addClass(String className) {
            if (className == null || className.equals(MODULE_DESCRIPTOR)) {
                return;
            }
            classes.add(className);
            int separator = className.lastIndexOf('.');
            packages.add(separator < 0 ? DEFAULT_PACKAGE : className.substring(0, separator));
        }

        private void addPackage(String packageName) {
            packages.add(requireValue(packageName, "package"));
        }

        private void addReference(String reference) {
            references.add(requireValue(reference, "reference"));
        }

        private void addReferences(Collection<String> values) {
            values.forEach(this::addReference);
        }

        private Inventory build() {
            references.removeAll(classes);
            return new Inventory(module, packages, classes, references);
        }
    }

    private static Set<String> immutableSet(Collection<String> values) {
        return Collections.unmodifiableSet(new TreeSet<>(values));
    }

    private static final class ClassReferenceVisitor extends ClassVisitor {

        private final Set<String> references = new TreeSet<>();
        private String owner;

        private ClassReferenceVisitor() {
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
            owner = name.replace('/', '.');
            collectInternalOrDescriptor(superName, references);
            if (interfaces != null) {
                for (String value : interfaces) {
                    collectInternalOrDescriptor(value, references);
                }
            }
            collectSignature(signature, references);
        }

        @Override
        public void visitOuterClass(String owner, String name, String descriptor) {
            collectInternalOrDescriptor(owner, references);
            collectDescriptor(descriptor, references);
        }

        @Override
        public void visitNestHost(String nestHost) {
            collectInternalOrDescriptor(nestHost, references);
        }

        @Override
        public void visitNestMember(String nestMember) {
            collectInternalOrDescriptor(nestMember, references);
        }

        @Override
        public void visitPermittedSubclass(String permittedSubclass) {
            collectInternalOrDescriptor(permittedSubclass, references);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            collectDescriptor(descriptor, references);
            return annotationVisitor(references);
        }

        @Override
        public FieldVisitor visitField(
                int access, String name, String descriptor, String signature, Object value) {
            collectDescriptor(descriptor, references);
            collectSignature(signature, references);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String value, boolean visible) {
                    collectDescriptor(value, references);
                    return annotationVisitor(references);
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(
                int access,
                String name,
                String descriptor,
                String signature,
                String[] exceptions) {
            collectDescriptor(descriptor, references);
            collectSignature(signature, references);
            if (exceptions != null) {
                for (String exception : exceptions) {
                    collectInternalOrDescriptor(exception, references);
                }
            }
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String value, boolean visible) {
                    collectDescriptor(value, references);
                    return annotationVisitor(references);
                }

                @Override
                public AnnotationVisitor visitParameterAnnotation(
                        int parameter, String value, boolean visible) {
                    collectDescriptor(value, references);
                    return annotationVisitor(references);
                }
            };
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(
                String name, String descriptor, String signature) {
            collectDescriptor(descriptor, references);
            collectSignature(signature, references);
            return new RecordComponentVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String value, boolean visible) {
                    collectDescriptor(value, references);
                    return annotationVisitor(references);
                }
            };
        }

        private String owner() {
            return owner;
        }

        private Set<String> references() {
            references.remove(owner);
            return references;
        }
    }

    private static AnnotationVisitor annotationVisitor(Set<String> references) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                if (value instanceof Type) {
                    collectType((Type) value, references);
                }
            }

            @Override
            public void visitEnum(String name, String descriptor, String value) {
                collectDescriptor(descriptor, references);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                collectDescriptor(descriptor, references);
                return annotationVisitor(references);
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                return annotationVisitor(references);
            }
        };
    }
}
