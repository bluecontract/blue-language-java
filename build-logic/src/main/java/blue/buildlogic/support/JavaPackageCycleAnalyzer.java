package blue.buildlogic.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.api.GradleException;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

/**
 * Derives a deterministic package dependency graph from compiled Java artifacts.
 *
 * <p>Package ownership comes only from classes present in the configured inputs.
 * A reference contributes an edge only when its target package is owned by those
 * same inputs. This excludes JDK and external-library packages without relying on
 * a mutable prefix allowlist. Same-package references are deliberately omitted,
 * so a singleton strongly connected component is never reported as a cycle.</p>
 */
public final class JavaPackageCycleAnalyzer {

    public static final String SCHEMA = "blue-java-package-cycles/1.0";

    private static final String CLASS_SUFFIX = ".class";
    private static final String JAR_SUFFIX = ".jar";
    private static final String MODULE_DESCRIPTOR = "module-info";
    private static final String DEFAULT_PACKAGE = "<default>";

    private JavaPackageCycleAnalyzer() {}

    /**
     * Analyzes class directories, individual class files, and JAR archives.
     *
     * @param compiledInputs compiled artifacts whose packages form the owned graph
     * @return immutable deterministic package-cycle result
     */
    public static Result analyze(Collection<Path> compiledInputs) {
        SortedMap<String, SortedSet<String>> classReferences = new TreeMap<>();
        for (Path input : sortedPaths(compiledInputs)) {
            inspectInput(input, classReferences);
        }

        SortedSet<String> ownedPackages = new TreeSet<>();
        for (String className : classReferences.keySet()) {
            ownedPackages.add(packageName(className));
        }

        SortedSet<Edge> edges = new TreeSet<>();
        for (Map.Entry<String, SortedSet<String>> entry : classReferences.entrySet()) {
            String sourcePackage = packageName(entry.getKey());
            for (String reference : entry.getValue()) {
                String targetPackage = packageName(reference);
                if (ownedPackages.contains(targetPackage)
                        && !sourcePackage.equals(targetPackage)) {
                    edges.add(new Edge(sourcePackage, targetPackage));
                }
            }
        }

        SortedMap<String, SortedSet<String>> graph = new TreeMap<>();
        for (String packageName : ownedPackages) {
            graph.put(packageName, new TreeSet<>());
        }
        for (Edge edge : edges) {
            graph.get(edge.source).add(edge.target);
        }
        List<List<String>> components = new StronglyConnectedComponents(graph).analyze();
        return new Result(ownedPackages, edges, components);
    }

    private static void inspectInput(
            Path input, SortedMap<String, SortedSet<String>> classReferences) {
        if (Files.isDirectory(input)) {
            inspectDirectory(input, classReferences);
            return;
        }
        if (Files.isRegularFile(input)
                && input.getFileName().toString().endsWith(CLASS_SUFFIX)) {
            addClass(read(input), input.toString(), classReferences);
            return;
        }
        if (Files.isRegularFile(input)
                && input.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                        .endsWith(JAR_SUFFIX)) {
            inspectJar(input, classReferences);
            return;
        }
        throw new GradleException("Unsupported Java package-cycle input: " + input);
    }

    private static void inspectDirectory(
            Path directory, SortedMap<String, SortedSet<String>> classReferences) {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(CLASS_SUFFIX))
                    .sorted(Comparator.comparing(
                            path -> normalizedRelativePath(directory, path)))
                    .forEach(path -> addClass(
                            read(path), path.toString(), classReferences));
        } catch (IOException exception) {
            throw new GradleException(
                    "Cannot inspect package-cycle class directory: " + directory,
                    exception);
        }
    }

    private static void inspectJar(
            Path jar, SortedMap<String, SortedSet<String>> classReferences) {
        try (ZipFile archive = new ZipFile(jar.toFile())) {
            List<? extends ZipEntry> entries = Collections.list(archive.entries());
            entries.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().endsWith(CLASS_SUFFIX))
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .forEach(entry -> addClass(
                            read(archive, entry),
                            jar + "!" + entry.getName(),
                            classReferences));
        } catch (IOException exception) {
            throw new GradleException(
                    "Cannot inspect package-cycle JAR: " + jar, exception);
        }
    }

    private static void addClass(
            byte[] bytecode,
            String source,
            SortedMap<String, SortedSet<String>> classReferences) {
        ClassReferenceVisitor visitor = new ClassReferenceVisitor();
        try {
            new ClassReader(bytecode).accept(visitor, 0);
        } catch (RuntimeException exception) {
            throw new GradleException(
                    "Cannot inspect package-cycle class: " + source, exception);
        }
        if (visitor.owner == null || MODULE_DESCRIPTOR.equals(visitor.owner)) {
            return;
        }
        classReferences.computeIfAbsent(visitor.owner, ignored -> new TreeSet<>())
                .addAll(visitor.references());
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new GradleException(
                    "Cannot read package-cycle class: " + file, exception);
        }
    }

    private static byte[] read(ZipFile archive, ZipEntry entry) {
        try (InputStream input = archive.getInputStream(entry)) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new GradleException(
                    "Cannot read package-cycle archive entry: " + entry.getName(),
                    exception);
        }
    }

    private static List<Path> sortedPaths(Collection<Path> paths) {
        List<Path> sorted = new ArrayList<>(paths);
        sorted.sort(Comparator.comparing(
                path -> path.toAbsolutePath().normalize().toString()));
        return sorted;
    }

    private static String normalizedRelativePath(Path root, Path file) {
        return root.relativize(file).toString()
                .replace(file.getFileSystem().getSeparator(), "/");
    }

    private static String packageName(String className) {
        int separator = className.lastIndexOf('.');
        return separator < 0 ? DEFAULT_PACKAGE : className.substring(0, separator);
    }

    private static void collectDescriptor(
            String descriptor, Set<String> references) {
        if (descriptor == null) {
            return;
        }
        try {
            collectType(Type.getType(descriptor), references);
        } catch (IllegalArgumentException exception) {
            throw new GradleException(
                    "Invalid descriptor in package-cycle input: " + descriptor,
                    exception);
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

    private static void collectInternalName(
            String internalName, Set<String> references) {
        if (internalName == null) {
            return;
        }
        if (internalName.startsWith("[")) {
            collectDescriptor(internalName, references);
        } else {
            references.add(internalName.replace('/', '.'));
        }
    }

    private static void collectSignature(
            String signature, Set<String> references) {
        if (signature == null) {
            return;
        }
        try {
            new SignatureReader(signature).accept(
                    new ReferenceSignatureVisitor(references));
        } catch (IllegalArgumentException exception) {
            throw new GradleException(
                    "Invalid signature in package-cycle input: " + signature,
                    exception);
        }
    }

    private static void collectHandle(Handle handle, Set<String> references) {
        collectInternalName(handle.getOwner(), references);
        collectDescriptor(handle.getDesc(), references);
    }

    private static void collectConstant(Object value, Set<String> references) {
        if (value instanceof Type) {
            collectType((Type) value, references);
        } else if (value instanceof Handle) {
            collectHandle((Handle) value, references);
        } else if (value instanceof ConstantDynamic) {
            ConstantDynamic dynamic = (ConstantDynamic) value;
            collectDescriptor(dynamic.getDescriptor(), references);
            collectHandle(dynamic.getBootstrapMethod(), references);
            for (int index = 0;
                    index < dynamic.getBootstrapMethodArgumentCount();
                    index++) {
                collectConstant(dynamic.getBootstrapMethodArgument(index), references);
            }
        }
    }

    private static AnnotationVisitor annotationVisitor(Set<String> references) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                collectConstant(value, references);
            }

            @Override
            public void visitEnum(String name, String descriptor, String value) {
                collectDescriptor(descriptor, references);
            }

            @Override
            public AnnotationVisitor visitAnnotation(
                    String name, String descriptor) {
                collectDescriptor(descriptor, references);
                return annotationVisitor(references);
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                return annotationVisitor(references);
            }
        };
    }

    private static final class ReferenceSignatureVisitor extends SignatureVisitor {

        private final Set<String> references;
        private String currentClass;

        private ReferenceSignatureVisitor(Set<String> references) {
            super(Opcodes.ASM9);
            this.references = references;
        }

        @Override
        public SignatureVisitor visitClassBound() {
            return nested();
        }

        @Override
        public SignatureVisitor visitInterfaceBound() {
            return nested();
        }

        @Override
        public SignatureVisitor visitSuperclass() {
            return nested();
        }

        @Override
        public SignatureVisitor visitInterface() {
            return nested();
        }

        @Override
        public SignatureVisitor visitParameterType() {
            return nested();
        }

        @Override
        public SignatureVisitor visitReturnType() {
            return nested();
        }

        @Override
        public SignatureVisitor visitExceptionType() {
            return nested();
        }

        @Override
        public SignatureVisitor visitArrayType() {
            return nested();
        }

        @Override
        public void visitClassType(String name) {
            currentClass = name;
            collectInternalName(name, references);
        }

        @Override
        public void visitInnerClassType(String name) {
            currentClass = currentClass == null
                    ? name
                    : currentClass + '$' + name;
            collectInternalName(currentClass, references);
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            return nested();
        }

        @Override
        public void visitEnd() {
            currentClass = null;
        }

        private SignatureVisitor nested() {
            return new ReferenceSignatureVisitor(references);
        }
    }

    private static final class ClassReferenceVisitor extends ClassVisitor {

        private final SortedSet<String> referencedClasses = new TreeSet<>();
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
            collectInternalName(superName, referencedClasses);
            if (interfaces != null) {
                for (String implemented : interfaces) {
                    collectInternalName(implemented, referencedClasses);
                }
            }
            collectSignature(signature, referencedClasses);
        }

        @Override
        public void visitOuterClass(String owner, String name, String descriptor) {
            collectInternalName(owner, referencedClasses);
            collectDescriptor(descriptor, referencedClasses);
        }

        @Override
        public void visitInnerClass(
                String name, String outerName, String innerName, int access) {
            collectInternalName(name, referencedClasses);
            collectInternalName(outerName, referencedClasses);
        }

        @Override
        public void visitNestHost(String nestHost) {
            collectInternalName(nestHost, referencedClasses);
        }

        @Override
        public void visitNestMember(String nestMember) {
            collectInternalName(nestMember, referencedClasses);
        }

        @Override
        public void visitPermittedSubclass(String permittedSubclass) {
            collectInternalName(permittedSubclass, referencedClasses);
        }

        @Override
        public AnnotationVisitor visitAnnotation(
                String descriptor, boolean visible) {
            collectDescriptor(descriptor, referencedClasses);
            return annotationVisitor(referencedClasses);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(
                int typeRef,
                TypePath typePath,
                String descriptor,
                boolean visible) {
            collectDescriptor(descriptor, referencedClasses);
            return annotationVisitor(referencedClasses);
        }

        @Override
        public FieldVisitor visitField(
                int access,
                String name,
                String descriptor,
                String signature,
                Object value) {
            collectDescriptor(descriptor, referencedClasses);
            collectSignature(signature, referencedClasses);
            collectConstant(value, referencedClasses);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(
                        String annotationDescriptor, boolean visible) {
                    collectDescriptor(annotationDescriptor, referencedClasses);
                    return annotationVisitor(referencedClasses);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(
                        int typeRef,
                        TypePath typePath,
                        String annotationDescriptor,
                        boolean visible) {
                    collectDescriptor(annotationDescriptor, referencedClasses);
                    return annotationVisitor(referencedClasses);
                }
            };
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(
                String name, String descriptor, String signature) {
            collectDescriptor(descriptor, referencedClasses);
            collectSignature(signature, referencedClasses);
            return new RecordComponentVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(
                        String annotationDescriptor, boolean visible) {
                    collectDescriptor(annotationDescriptor, referencedClasses);
                    return annotationVisitor(referencedClasses);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(
                        int typeRef,
                        TypePath typePath,
                        String annotationDescriptor,
                        boolean visible) {
                    collectDescriptor(annotationDescriptor, referencedClasses);
                    return annotationVisitor(referencedClasses);
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
            collectDescriptor(descriptor, referencedClasses);
            collectSignature(signature, referencedClasses);
            if (exceptions != null) {
                for (String exception : exceptions) {
                    collectInternalName(exception, referencedClasses);
                }
            }
            return new MethodReferenceVisitor(referencedClasses);
        }

        private SortedSet<String> references() {
            referencedClasses.remove(owner);
            return referencedClasses;
        }
    }

    private static final class MethodReferenceVisitor extends MethodVisitor {

        private final Set<String> references;

        private MethodReferenceVisitor(Set<String> references) {
            super(Opcodes.ASM9);
            this.references = references;
        }

        @Override
        public AnnotationVisitor visitAnnotationDefault() {
            return annotationVisitor(references);
        }

        @Override
        public AnnotationVisitor visitAnnotation(
                String descriptor, boolean visible) {
            collectDescriptor(descriptor, references);
            return annotationVisitor(references);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(
                int typeRef,
                TypePath typePath,
                String descriptor,
                boolean visible) {
            collectDescriptor(descriptor, references);
            return annotationVisitor(references);
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(
                int parameter, String descriptor, boolean visible) {
            collectDescriptor(descriptor, references);
            return annotationVisitor(references);
        }

        @Override
        public AnnotationVisitor visitInsnAnnotation(
                int typeRef,
                TypePath typePath,
                String descriptor,
                boolean visible) {
            collectDescriptor(descriptor, references);
            return annotationVisitor(references);
        }

        @Override
        public AnnotationVisitor visitTryCatchAnnotation(
                int typeRef,
                TypePath typePath,
                String descriptor,
                boolean visible) {
            collectDescriptor(descriptor, references);
            return annotationVisitor(references);
        }

        @Override
        public AnnotationVisitor visitLocalVariableAnnotation(
                int typeRef,
                TypePath typePath,
                Label[] start,
                Label[] end,
                int[] index,
                String descriptor,
                boolean visible) {
            collectDescriptor(descriptor, references);
            return annotationVisitor(references);
        }

        @Override
        public void visitFrame(
                int type,
                int numLocal,
                Object[] local,
                int numStack,
                Object[] stack) {
            collectFrameValues(local, numLocal);
            collectFrameValues(stack, numStack);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            collectInternalName(type, references);
        }

        @Override
        public void visitFieldInsn(
                int opcode, String owner, String name, String descriptor) {
            collectInternalName(owner, references);
            collectDescriptor(descriptor, references);
        }

        @Override
        public void visitMethodInsn(
                int opcode,
                String owner,
                String name,
                String descriptor,
                boolean isInterface) {
            collectInternalName(owner, references);
            collectDescriptor(descriptor, references);
        }

        @Override
        public void visitInvokeDynamicInsn(
                String name,
                String descriptor,
                Handle bootstrapMethodHandle,
                Object... bootstrapMethodArguments) {
            collectDescriptor(descriptor, references);
            collectHandle(bootstrapMethodHandle, references);
            for (Object argument : bootstrapMethodArguments) {
                collectConstant(argument, references);
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            collectConstant(value, references);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
            collectDescriptor(descriptor, references);
        }

        @Override
        public void visitTryCatchBlock(
                Label start, Label end, Label handler, String type) {
            collectInternalName(type, references);
        }

        @Override
        public void visitLocalVariable(
                String name,
                String descriptor,
                String signature,
                Label start,
                Label end,
                int index) {
            collectDescriptor(descriptor, references);
            collectSignature(signature, references);
        }

        private void collectFrameValues(Object[] values, int count) {
            if (values == null) {
                return;
            }
            for (int index = 0; index < count; index++) {
                Object value = values[index];
                if (value instanceof String) {
                    collectInternalName((String) value, references);
                }
            }
        }
    }

    /** Immutable directed edge between two distinct owned Java packages. */
    public static final class Edge implements Comparable<Edge> {

        private final String source;
        private final String target;

        private Edge(String source, String target) {
            this.source = source;
            this.target = target;
        }

        public String getSource() {
            return source;
        }

        public String getTarget() {
            return target;
        }

        @Override
        public int compareTo(Edge other) {
            int sourceOrder = source.compareTo(other.source);
            return sourceOrder != 0 ? sourceOrder : target.compareTo(other.target);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Edge)) {
                return false;
            }
            Edge edge = (Edge) other;
            return source.equals(edge.source) && target.equals(edge.target);
        }

        @Override
        public int hashCode() {
            return 31 * source.hashCode() + target.hashCode();
        }
    }

    /** Immutable package graph, SCC inventory, and machine-readable evidence. */
    public static final class Result {

        private final SortedSet<String> packages;
        private final SortedSet<Edge> edges;
        private final List<List<String>> components;
        private final List<List<String>> cycles;

        private Result(
                Collection<String> packages,
                Collection<Edge> edges,
                Collection<List<String>> components) {
            this.packages = Collections.unmodifiableSortedSet(
                    new TreeSet<>(packages));
            this.edges = Collections.unmodifiableSortedSet(new TreeSet<>(edges));
            this.components = immutableComponents(components);
            List<List<String>> cyclic = new ArrayList<>();
            for (List<String> component : this.components) {
                if (component.size() > 1) {
                    cyclic.add(component);
                }
            }
            this.cycles = Collections.unmodifiableList(cyclic);
        }

        public int getCycleCount() {
            return cycles.size();
        }

        public SortedSet<String> getPackages() {
            return packages;
        }

        public SortedSet<Edge> getEdges() {
            return edges;
        }

        public List<List<String>> getComponents() {
            return components;
        }

        public List<List<String>> getCycles() {
            return cycles;
        }

        public boolean isAcyclic() {
            return cycles.isEmpty();
        }

        /** Encodes the sorted graph and all SCCs as canonical build-evidence JSON. */
        public String toJson() {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("schema", SCHEMA);
            report.put("acyclic", isAcyclic());
            report.put("packageCount", packages.size());
            report.put("edgeCount", edges.size());
            report.put("cycleCount", getCycleCount());
            report.put("packages", new ArrayList<>(packages));

            List<Map<String, Object>> encodedEdges = new ArrayList<>();
            for (Edge edge : edges) {
                Map<String, Object> encoded = new LinkedHashMap<>();
                encoded.put("source", edge.source);
                encoded.put("target", edge.target);
                encodedEdges.add(encoded);
            }
            report.put("edges", encodedEdges);

            List<Map<String, Object>> encodedComponents = new ArrayList<>();
            for (List<String> component : components) {
                Map<String, Object> encoded = new LinkedHashMap<>();
                encoded.put("packages", component);
                encoded.put("cyclic", component.size() > 1);
                encodedComponents.add(encoded);
            }
            report.put("components", encodedComponents);
            report.put("cycles", cycles);
            return DeterministicJson.write(report);
        }

        private static List<List<String>> immutableComponents(
                Collection<List<String>> source) {
            List<List<String>> copy = new ArrayList<>();
            for (List<String> component : source) {
                copy.add(Collections.unmodifiableList(new ArrayList<>(component)));
            }
            return Collections.unmodifiableList(copy);
        }
    }

    private static final class StronglyConnectedComponents {

        private final SortedMap<String, SortedSet<String>> graph;
        private final Map<String, Integer> indexes = new HashMap<>();
        private final Map<String, Integer> lowLinks = new HashMap<>();
        private final Deque<String> stack = new ArrayDeque<>();
        private final Set<String> onStack = new HashSet<>();
        private final List<List<String>> components = new ArrayList<>();
        private int nextIndex;

        private StronglyConnectedComponents(
                SortedMap<String, SortedSet<String>> graph) {
            this.graph = graph;
        }

        private List<List<String>> analyze() {
            for (String packageName : graph.keySet()) {
                if (!indexes.containsKey(packageName)) {
                    connect(packageName);
                }
            }
            components.sort(JavaPackageCycleAnalyzer::compareComponents);
            return components;
        }

        private void connect(String packageName) {
            indexes.put(packageName, nextIndex);
            lowLinks.put(packageName, nextIndex);
            nextIndex++;
            stack.push(packageName);
            onStack.add(packageName);

            for (String target : graph.get(packageName)) {
                if (!indexes.containsKey(target)) {
                    connect(target);
                    lowLinks.put(
                            packageName,
                            Math.min(lowLinks.get(packageName), lowLinks.get(target)));
                } else if (onStack.contains(target)) {
                    lowLinks.put(
                            packageName,
                            Math.min(lowLinks.get(packageName), indexes.get(target)));
                }
            }

            if (lowLinks.get(packageName).equals(indexes.get(packageName))) {
                List<String> component = new ArrayList<>();
                String member;
                do {
                    member = stack.pop();
                    onStack.remove(member);
                    component.add(member);
                } while (!member.equals(packageName));
                Collections.sort(component);
                components.add(component);
            }
        }
    }

    private static int compareComponents(List<String> left, List<String> right) {
        int commonSize = Math.min(left.size(), right.size());
        for (int index = 0; index < commonSize; index++) {
            int order = left.get(index).compareTo(right.get(index));
            if (order != 0) {
                return order;
            }
        }
        return Integer.compare(left.size(), right.size());
    }
}
