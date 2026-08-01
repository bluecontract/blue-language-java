package blue.language.docs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiles the examples project and verifies the exact source regions published
 * by Java documentation fences.
 */
final class LanguageDocumentationExamplesTest {

    private static final int REQUIRED_RUNNABLE_EXAMPLE_COUNT = 16;
    private static final Path EXAMPLE_SOURCE_ROOT =
            Paths.get("examples", "src", "main", "java");
    private static final Path EXAMPLE_TEST_SOURCE_ROOT =
            Paths.get("examples", "src", "test", "java");
    private static final Pattern JAVA_BLOCK = Pattern.compile(
            "(?ms)^\\x60\\x60\\x60java[ \\t]*\\r?\\n"
                    + "(.*?)^\\x60\\x60\\x60[ \\t]*$");
    private static final Pattern EXAMPLE_BINDING = Pattern.compile(
            "(?s)<!--\\s*blue-example:\\s*([^#\\s]+)"
                    + "#([A-Za-z0-9_-]+)\\s*-->\\s*$");
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile(
            "(?m)^\\s*package\\s+"
                    + "([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)"
                    + "\\s*;");
    private static final Pattern PUBLIC_CLASS = Pattern.compile(
            "\\bpublic\\s+final\\s+class\\s+"
                    + "([A-Za-z_$][\\w$]*)");
    private static final Pattern RUN_METHOD = Pattern.compile(
            "(?m)^\\s*public\\s+static\\s+"
                    + "[A-Za-z_$][A-Za-z0-9_$.<>?, \\t]*"
                    + "\\s+run\\s*\\(\\s*\\)");
    private static final Pattern MAIN_METHOD = Pattern.compile(
            "(?m)^\\s*public\\s+static\\s+void\\s+main"
                    + "\\s*\\(\\s*String\\s*\\[\\s*]"
                    + "\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*\\)");

    @Test
    void shouldCompileAndRunEveryRunnableExamplesProjectExample(
            @TempDir Path temporaryDirectory) throws Exception {
        // given
        JavaCompiler compiler = Objects.requireNonNull(
                ToolProvider.getSystemJavaCompiler(),
                "Documentation verification requires a JDK compiler");
        List<Path> sources = readJavaSources(EXAMPLE_SOURCE_ROOT);
        List<RunnableExample> examples = runnableExamples(sources);
        Path classes = Files.createDirectories(
                temporaryDirectory.resolve("classes"));
        DiagnosticCollector<JavaFileObject> diagnostics =
                new DiagnosticCollector<>();

        // when
        boolean compiled = compile(
                compiler, sources, classes, diagnostics);
        List<String> executed = compiled
                ? runMainMethods(examples, classes)
                : Collections.emptyList();

        // then
        assertTrue(examples.size() >= REQUIRED_RUNNABLE_EXAMPLE_COUNT,
                "The examples project must retain at least "
                        + REQUIRED_RUNNABLE_EXAMPLE_COUNT
                        + " runnable examples but found "
                        + examples.size());
        assertTrue(compiled, formatDiagnostics(diagnostics));
        assertEquals(
                examples.stream()
                        .map(example -> example.qualifiedClassName)
                        .collect(Collectors.toList()),
                executed,
                "Every discovered runnable example must execute its main method");
    }

    @Test
    void shouldBindEveryJavaFenceToACompiledExamplesProjectRegion()
            throws Exception {
        // given
        List<Path> documents = documentationFiles();
        Set<Path> compiledExampleSources = new LinkedHashSet<>();
        compiledExampleSources.addAll(
                normalized(readJavaSources(EXAMPLE_SOURCE_ROOT)));
        compiledExampleSources.addAll(
                normalized(readJavaSources(EXAMPLE_TEST_SOURCE_ROOT)));

        // when
        BindingReport report = inspectBindings(
                documents, compiledExampleSources);

        // then
        assertTrue(report.fenceCount > 0,
                "Documentation must retain source-bound Java examples");
        assertTrue(report.violations.isEmpty(),
                "Java fences must exactly match tagged, compiled examples-project "
                        + "regions: " + report.violations);
    }

    private static List<Path> readJavaSources(Path root)
            throws Exception {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .collect(Collectors.toList());
        }
    }

    private static List<RunnableExample> runnableExamples(
            List<Path> sources) throws Exception {
        List<RunnableExample> examples = new ArrayList<>();
        for (Path source : sources) {
            String content = read(source);
            if (!RUN_METHOD.matcher(content).find()
                    || !MAIN_METHOD.matcher(content).find()) {
                continue;
            }
            Matcher packageMatcher =
                    PACKAGE_DECLARATION.matcher(content);
            Matcher classMatcher = PUBLIC_CLASS.matcher(content);
            if (!packageMatcher.find() || !classMatcher.find()) {
                throw new IllegalStateException(
                        "Runnable example must declare one public final class: "
                                + source);
            }
            examples.add(new RunnableExample(
                    packageMatcher.group(1) + "."
                            + classMatcher.group(1)));
        }
        return examples;
    }

    private static boolean compile(
            JavaCompiler compiler,
            List<Path> sources,
            Path classes,
            DiagnosticCollector<JavaFileObject> diagnostics)
            throws Exception {
        List<File> sourceFiles = sources.stream()
                .map(Path::toFile)
                .collect(Collectors.toList());
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(
                             diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> compilationUnits =
                    fileManager.getJavaFileObjectsFromFiles(sourceFiles);
            List<String> options = Arrays.asList(
                    "-classpath", System.getProperty("java.class.path"),
                    "-source", "8",
                    "-target", "8",
                    "-d", classes.toString());
            return Boolean.TRUE.equals(compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    compilationUnits).call());
        }
    }

    private static List<String> runMainMethods(
            List<RunnableExample> examples,
            Path classes) throws Exception {
        List<String> executed = new ArrayList<>();
        URL[] classPath = {classes.toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(
                classPath,
                LanguageDocumentationExamplesTest.class
                        .getClassLoader())) {
            for (RunnableExample runnable : examples) {
                Class<?> example = loader.loadClass(
                        runnable.qualifiedClassName);
                Method main = example.getMethod(
                        "main", String[].class);
                try {
                    main.invoke(null, (Object) new String[0]);
                } catch (InvocationTargetException failure) {
                    Throwable cause = failure.getCause();
                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    }
                    if (cause instanceof Error) {
                        throw (Error) cause;
                    }
                    throw failure;
                }
                executed.add(runnable.qualifiedClassName);
            }
        }
        return executed;
    }

    private static List<Path> documentationFiles()
            throws Exception {
        List<Path> documents = new ArrayList<>();
        documents.add(Paths.get("README.md"));
        try (Stream<Path> paths = Files.walk(Paths.get("docs"))) {
            documents.addAll(paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .endsWith(".md"))
                    .collect(Collectors.toList()));
        }
        documents.sort(Comparator.comparing(Path::toString));
        return documents;
    }

    private static Set<Path> normalized(List<Path> paths) {
        return paths.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .collect(Collectors.toCollection(
                        LinkedHashSet::new));
    }

    private static BindingReport inspectBindings(
            List<Path> documents,
            Set<Path> compiledExampleSources) throws Exception {
        Path repositoryRoot =
                Paths.get("").toAbsolutePath().normalize();
        List<String> violations = new ArrayList<>();
        int fenceCount = 0;
        for (Path document : documents) {
            String markdown = read(document);
            Matcher fence = JAVA_BLOCK.matcher(markdown);
            while (fence.find()) {
                fenceCount++;
                Matcher binding = EXAMPLE_BINDING.matcher(
                        markdown.substring(0, fence.start()));
                if (!binding.find()) {
                    violations.add(document
                            + " has an unbound Java fence");
                    continue;
                }
                Path source = repositoryRoot.resolve(
                        binding.group(1)).normalize();
                if (!source.startsWith(repositoryRoot)
                        || !compiledExampleSources.contains(source)
                        || !Files.isRegularFile(source)) {
                    violations.add(document + " -> "
                            + binding.group(1)
                            + " is not a compiled examples-project source");
                    continue;
                }
                String region = sourceRegion(
                        source, binding.group(2));
                if (region == null) {
                    violations.add(document + " -> "
                            + binding.group(1) + "#"
                            + binding.group(2)
                            + " is not one exact tagged region");
                    continue;
                }
                if (!normalizeSnippet(region).equals(
                        normalizeSnippet(fence.group(1)))) {
                    violations.add(document + " -> "
                            + binding.group(1) + "#"
                            + binding.group(2)
                            + " has drifted from its source region");
                }
            }
        }
        return new BindingReport(fenceCount, violations);
    }

    private static String sourceRegion(
            Path source, String regionName) throws Exception {
        String start = "// tag::" + regionName + "[]";
        String end = "// end::" + regionName + "[]";
        String content = read(source);
        int startIndex = content.indexOf(start);
        if (startIndex < 0) {
            return null;
        }
        int contentStart = content.indexOf(
                '\n', startIndex + start.length());
        if (contentStart < 0) {
            return null;
        }
        int endIndex = content.indexOf(
                end, contentStart + 1);
        int duplicateStart = content.indexOf(
                start, startIndex + start.length());
        if (endIndex < 0
                || duplicateStart >= 0
                && duplicateStart < endIndex) {
            return null;
        }
        return content.substring(contentStart + 1, endIndex);
    }

    private static String normalizeSnippet(String snippet) {
        String normalized = snippet
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        int end = normalized.length();
        while (end > 0
                && Character.isWhitespace(
                normalized.charAt(end - 1))) {
            end--;
        }
        return normalized.substring(0, end);
    }

    private static String read(Path path) throws Exception {
        return new String(
                Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String formatDiagnostics(
            DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder result = new StringBuilder(
                "Runnable examples did not compile:");
        for (Diagnostic<? extends JavaFileObject> diagnostic
                : diagnostics.getDiagnostics()) {
            result.append(System.lineSeparator())
                    .append(diagnostic.getSource() == null
                            ? "<unknown>"
                            : diagnostic.getSource().getName())
                    .append(':')
                    .append(diagnostic.getLineNumber())
                    .append(' ')
                    .append(diagnostic.getMessage(null));
        }
        return result.toString();
    }

    private static final class RunnableExample {
        private final String qualifiedClassName;

        private RunnableExample(String qualifiedClassName) {
            this.qualifiedClassName =
                    qualifiedClassName;
        }
    }

    private static final class BindingReport {
        private final int fenceCount;
        private final List<String> violations;

        private BindingReport(
                int fenceCount,
                List<String> violations) {
            this.fenceCount = fenceCount;
            this.violations =
                    Collections.unmodifiableList(
                            new ArrayList<>(violations));
        }
    }
}
