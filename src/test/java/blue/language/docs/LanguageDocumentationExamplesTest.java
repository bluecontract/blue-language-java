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
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compiles and executes the exact Java examples published by the core docs. */
final class LanguageDocumentationExamplesTest {

    private static final Pattern JAVA_BLOCK = Pattern.compile(
            "(?s)```java[\\t ]*\\r?\\n(.*?)\\r?\\n```");
    private static final Pattern PUBLIC_CLASS = Pattern.compile(
            "\\bpublic\\s+final\\s+class\\s+([A-Za-z_$][\\w$]*)");
    private static final List<Path> REQUIRED_DOCUMENTS =
            Collections.unmodifiableList(Arrays.asList(
                    Paths.get("docs", "concepts", "nodes-and-blueids.md"),
                    Paths.get("docs", "concepts", "direct-vs-source-blueid.md"),
                    Paths.get("docs", "concepts", "preprocessing.md"),
                    Paths.get("docs", "concepts", "expansion-collapse-specialization.md"),
                    Paths.get("docs", "concepts", "resolution-canonicalization-minimization.md"),
                    Paths.get("docs", "concepts", "lists-and-incremental-blueid.md"),
                    Paths.get("docs", "guides", "building-a-node-provider.md"),
                    Paths.get("docs", "architecture", "language-pipeline.md")));

    @Test
    void shouldCompileAndRunEveryRequiredLanguageCoreExample(
            @TempDir Path temporaryDirectory) throws Exception {
        // given
        JavaCompiler compiler = Objects.requireNonNull(
                ToolProvider.getSystemJavaCompiler(),
                "Documentation verification requires a JDK compiler");
        List<Snippet> snippets = readRequiredSnippets();
        Path classes = Files.createDirectories(
                temporaryDirectory.resolve("classes"));
        DiagnosticCollector<JavaFileObject> diagnostics =
                new DiagnosticCollector<>();

        // when
        boolean compiled = compile(
                compiler, snippets, classes, diagnostics);

        // then
        assertTrue(compiled, formatDiagnostics(diagnostics));
        runMainMethods(snippets, classes);
    }

    private List<Snippet> readRequiredSnippets() throws Exception {
        List<Snippet> snippets = new ArrayList<>();
        for (Path document : REQUIRED_DOCUMENTS) {
            String markdown = new String(
                    Files.readAllBytes(document), StandardCharsets.UTF_8);
            Matcher blockMatcher = JAVA_BLOCK.matcher(markdown);
            assertTrue(blockMatcher.find(),
                    document + " must contain one Java example");
            String source = blockMatcher.group(1);
            assertTrue(!blockMatcher.find(),
                    document + " must keep one focused Java example");
            Matcher classMatcher = PUBLIC_CLASS.matcher(source);
            assertTrue(classMatcher.find(),
                    document + " example must be a complete public class");
            snippets.add(new Snippet(classMatcher.group(1), source));
        }
        assertEquals(REQUIRED_DOCUMENTS.size(), snippets.size());
        return snippets;
    }

    private boolean compile(
            JavaCompiler compiler,
            List<Snippet> snippets,
            Path classes,
            DiagnosticCollector<JavaFileObject> diagnostics)
            throws Exception {
        List<File> sourceFiles = new ArrayList<>();
        for (Snippet snippet : snippets) {
            Path sourcePath = classes.getParent()
                    .resolve(snippet.className + ".java");
            Files.write(
                    sourcePath,
                    snippet.source.getBytes(StandardCharsets.UTF_8));
            sourceFiles.add(sourcePath.toFile());
        }
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

    private void runMainMethods(
            List<Snippet> snippets, Path classes) throws Exception {
        URL[] classPath = {classes.toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(
                classPath, getClass().getClassLoader())) {
            for (Snippet snippet : snippets) {
                Class<?> example = loader.loadClass(snippet.className);
                Method main = example.getMethod("main", String[].class);
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
            }
        }
    }

    private String formatDiagnostics(
            DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder result = new StringBuilder(
                "Documentation examples did not compile:");
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

    private static final class Snippet {
        private final String className;
        private final String source;

        private Snippet(String className, String source) {
            this.className = className;
            this.source = source;
        }
    }
}
