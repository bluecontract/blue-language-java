package blue.buildlogic.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JavaPackageCycleAnalyzerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldIgnoreSamePackageAndExternalReferences() throws Exception {
        // given
        Path compiled = compile(
                "acyclic",
                source(
                        "alpha/Alpha.java",
                        "package alpha;"
                                + " public final class Alpha {"
                                + " Alpha sibling;"
                                + " java.util.List<String> values;"
                                + " external.library.Dependency dependency;"
                                + " }"),
                source(
                        "alpha/Sibling.java",
                        "package alpha; final class Sibling {}"),
                source(
                        "external/library/Dependency.java",
                        "package external.library; public final class Dependency {}"));
        Path owned = copyPackage(compiled, "alpha", "acyclic-owned");

        // when
        JavaPackageCycleAnalyzer.Result result =
                JavaPackageCycleAnalyzer.analyze(Collections.singletonList(owned));

        // then
        assertTrue(result.isAcyclic());
        assertEquals(0, result.getCycleCount());
        assertEquals(Collections.singleton("alpha"), result.getPackages());
        assertTrue(result.getEdges().isEmpty());
        assertEquals(Collections.singletonList(
                Collections.singletonList("alpha")), result.getComponents());
        assertEquals(
                "{\"acyclic\":true,\"components\":[{\"cyclic\":false,"
                        + "\"packages\":[\"alpha\"]}],\"cycleCount\":0,"
                        + "\"cycles\":[],\"edgeCount\":0,\"edges\":[],"
                        + "\"packageCount\":1,\"packages\":[\"alpha\"],"
                        + "\"schema\":\"blue-java-package-cycles/1.0\"}\n",
                result.toJson());
    }

    @Test
    void shouldFindTwoPackageCycleFromMethodBodyInstructions() throws Exception {
        // given
        Path compiled = compile(
                "two-cycle",
                source(
                        "first/First.java",
                        "package first; public final class First {"
                                + " public Object create() { return new second.Second(); }"
                                + " }"),
                source(
                        "second/Second.java",
                        "package second; public final class Second {"
                                + " public Object create() { return new first.First(); }"
                                + " }"));

        // when
        JavaPackageCycleAnalyzer.Result result =
                JavaPackageCycleAnalyzer.analyze(Collections.singletonList(compiled));

        // then
        assertFalse(result.isAcyclic());
        assertEquals(1, result.getCycleCount());
        assertEquals(Collections.singletonList(
                Arrays.asList("first", "second")), result.getCycles());
        assertEquals(2, result.getEdges().size());
        assertTrue(result.toJson().contains(
                "\"components\":[{\"cyclic\":true,"
                        + "\"packages\":[\"first\",\"second\"]}]"));
    }

    @Test
    void shouldFindThreePackageCycleAndKeepAcyclicComponentSeparate()
            throws Exception {
        // given
        Path compiled = compile(
                "three-cycle",
                source(
                        "alpha/Alpha.java",
                        "package alpha; public final class Alpha {"
                                + " public Object next() { return new beta.Beta(); }"
                                + " }"),
                source(
                        "beta/Beta.java",
                        "package beta; public final class Beta {"
                                + " public Object next() { return new gamma.Gamma(); }"
                                + " }"),
                source(
                        "gamma/Gamma.java",
                        "package gamma; public final class Gamma {"
                                + " public Object next() { return new alpha.Alpha(); }"
                                + " }"),
                source(
                        "observer/Observer.java",
                        "package observer; public final class Observer {"
                                + " public Object observe() { return new alpha.Alpha(); }"
                                + " }"));

        // when
        JavaPackageCycleAnalyzer.Result result =
                JavaPackageCycleAnalyzer.analyze(Collections.singletonList(compiled));

        // then
        assertEquals(1, result.getCycleCount());
        assertEquals(Collections.singletonList(
                Arrays.asList("alpha", "beta", "gamma")), result.getCycles());
        assertEquals(
                Arrays.asList(
                        Arrays.asList("alpha", "beta", "gamma"),
                        Collections.singletonList("observer")),
                result.getComponents());
        assertEquals(4, result.getEdges().size());
    }

    @Test
    void shouldProduceIdenticalOutputForEveryInputOrder() throws Exception {
        // given
        Path compiled = compile(
                "ordered",
                source(
                        "a/A.java",
                        "package a; public final class A { public b.B next; }"),
                source(
                        "b/B.java",
                        "package b; public final class B { public c.C next; }"),
                source(
                        "c/C.java",
                        "package c; public final class C { public a.A next; }"));
        Path first = copyPackage(compiled, "a", "ordered-a");
        Path second = copyPackage(compiled, "b", "ordered-b");
        Path third = copyPackage(compiled, "c", "ordered-c");

        // when
        String forward = JavaPackageCycleAnalyzer.analyze(
                Arrays.asList(first, second, third)).toJson();
        String reverse = JavaPackageCycleAnalyzer.analyze(
                Arrays.asList(third, second, first)).toJson();

        // then
        assertEquals(forward, reverse);
    }

    @Test
    void shouldAnalyzeJarAndDirectoryInputsAsOneOwnedGraph() throws Exception {
        // given
        Path compiled = compile(
                "mixed",
                source(
                        "archive/Archived.java",
                        "package archive; public final class Archived {"
                                + " public directory.DirectorySide next; }"),
                source(
                        "directory/DirectorySide.java",
                        "package directory; public final class DirectorySide {"
                                + " public archive.Archived next; }"));
        Path archive = jarPackage(compiled, "archive", "archive-side.jar");
        Path directory = copyPackage(compiled, "directory", "directory-side");

        // when
        JavaPackageCycleAnalyzer.Result result = JavaPackageCycleAnalyzer.analyze(
                Arrays.asList(directory, archive));

        // then
        assertEquals(1, result.getCycleCount());
        assertEquals(Collections.singletonList(
                Arrays.asList("archive", "directory")), result.getCycles());
        assertEquals(2, result.getEdges().size());
    }

    @Test
    void shouldAnalyzeCompleteJarWithoutDependingOnEntryOrder() throws Exception {
        // given
        Path compiled = compile(
                "jar-only",
                source(
                        "left/Left.java",
                        "package left; public final class Left { public right.Right next; }"),
                source(
                        "right/Right.java",
                        "package right; public final class Right {}"));
        Path forwardArchive = jarAll(compiled, "complete-forward.jar", false);
        Path reverseArchive = jarAll(compiled, "complete-reverse.jar", true);

        // when
        JavaPackageCycleAnalyzer.Result result = JavaPackageCycleAnalyzer.analyze(
                Collections.singletonList(forwardArchive));
        String reverseReport = JavaPackageCycleAnalyzer.analyze(
                Collections.singletonList(reverseArchive)).toJson();

        // then
        assertTrue(result.isAcyclic());
        assertEquals(1, result.getEdges().size());
        assertEquals(result.toJson(), reverseReport);
        JavaPackageCycleAnalyzer.Edge edge = result.getEdges().first();
        assertEquals("left", edge.getSource());
        assertEquals("right", edge.getTarget());
    }

    private Path compile(String name, Source... sources) throws Exception {
        Path fixtureRoot = temporaryDirectory.resolve(name);
        Path[] sourcePaths = new Path[sources.length];
        for (int index = 0; index < sources.length; index++) {
            sourcePaths[index] = TestJavaCompiler.source(
                    fixtureRoot.resolve("src"),
                    sources[index].path,
                    sources[index].content);
        }
        Path output = fixtureRoot.resolve("classes");
        TestJavaCompiler.compile(output, sourcePaths);
        return output;
    }

    private Path copyPackage(Path compiled, String packagePath, String outputName)
            throws Exception {
        Path output = temporaryDirectory.resolve(outputName);
        Path packageRoot = compiled.resolve(packagePath);
        try (Stream<Path> paths = Files.walk(packageRoot)) {
            for (Path source : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                Path relative = compiled.relativize(source);
                Path target = output.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return output;
    }

    private Path jarPackage(Path compiled, String packagePath, String outputName)
            throws Exception {
        return jar(compiled, outputName, compiled.resolve(packagePath), false);
    }

    private Path jarAll(Path compiled, String outputName, boolean reverse)
            throws Exception {
        return jar(compiled, outputName, compiled, reverse);
    }

    private Path jar(
            Path compiled,
            String outputName,
            Path selectedRoot,
            boolean reverse)
            throws Exception {
        Path archive = temporaryDirectory.resolve(outputName);
        try (JarOutputStream output = new JarOutputStream(
                Files.newOutputStream(archive));
                Stream<Path> paths = Files.walk(selectedRoot)) {
            List<Path> classes = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .sorted((left, right) -> compiled.relativize(left).toString()
                            .compareTo(compiled.relativize(right).toString()))
                    .collect(java.util.stream.Collectors.toList());
            if (reverse) {
                Collections.reverse(classes);
            }
            for (Path classFile : classes) {
                String entryName = compiled.relativize(classFile).toString()
                        .replace(classFile.getFileSystem().getSeparator(), "/");
                output.putNextEntry(new JarEntry(entryName));
                try (InputStream input = Files.newInputStream(classFile)) {
                    input.transferTo(output);
                }
                output.closeEntry();
            }
        }
        return archive;
    }

    private static Source source(String path, String content) {
        return new Source(path, content);
    }

    private static final class Source {

        private final String path;
        private final String content;

        private Source(String path, String content) {
            this.path = path;
            this.content = content;
        }
    }
}
