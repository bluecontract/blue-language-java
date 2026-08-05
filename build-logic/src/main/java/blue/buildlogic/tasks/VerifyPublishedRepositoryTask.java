package blue.buildlogic.tasks;

import blue.buildlogic.support.DeterministicHashing;
import blue.buildlogic.support.DeterministicJson;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Verifies staged Maven coordinates, bytecode level, artifact isolation, and POM graph policy. */
@CacheableTask
public abstract class VerifyPublishedRepositoryTask extends DefaultTask {

    private static final int CLASS_MAGIC = 0xCAFEBABE;
    private static final int JAVA_8_CLASS_MAJOR = 52;

    public VerifyPublishedRepositoryTask() {
        getGroupId().convention("blue.language");
        getAllowedModuleEdges().convention(Collections.emptyList());
    }

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getRepositoryDirectory();

    @Input
    public abstract Property<String> getGroupId();

    @Input
    public abstract Property<String> getVersionValue();

    @Input
    public abstract ListProperty<String> getExpectedArtifacts();

    @Input
    public abstract ListProperty<String> getAllowedModuleEdges();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void verify() {
        Path repository = getRepositoryDirectory().get().getAsFile().toPath();
        String group = oneLine(getGroupId().get(), "group");
        String version = oneLine(getVersionValue().get(), "version");
        Set<String> expected = new TreeSet<>(getExpectedArtifacts().get());
        Set<String> allowed = new TreeSet<>(getAllowedModuleEdges().get());
        List<Map<String, Object>> artifacts = new ArrayList<>();
        Set<String> observedEdges = new TreeSet<>();
        List<String> violations = new ArrayList<>();

        for (String artifact : expected) {
            Path directory = repository.resolve(group.replace('.', File.separatorChar))
                    .resolve(artifact).resolve(version);
            Path jar = artifactFile(directory, artifact, version, ".jar", true);
            Path sources = artifactFile(directory, artifact, version, "-sources.jar", false);
            Path javadoc = artifactFile(directory, artifact, version, "-javadoc.jar", false);
            Path pom = artifactFile(directory, artifact, version, ".pom", false);
            require(jar, artifact, violations);
            require(sources, artifact, violations);
            require(javadoc, artifact, violations);
            require(pom, artifact, violations);
            int classCount = jar != null && Files.isRegularFile(jar)
                    ? inspectJar(artifact, jar, violations) : 0;
            if (pom != null && Files.isRegularFile(pom)) {
                inspectPom(artifact, pom, expected, allowed, observedEdges, violations);
            }
            Map<String, Object> record = new TreeMap<>();
            record.put("artifactId", artifact);
            record.put("classCount", classCount);
            record.put("jarIdentity", jar != null && Files.isRegularFile(jar)
                    ? DeterministicHashing.sha256(jar) : null);
            record.put("pomIdentity", pom != null && Files.isRegularFile(pom)
                    ? DeterministicHashing.sha256(pom) : null);
            artifacts.add(record);
        }
        cycles(expected, observedEdges).forEach(cycle ->
                violations.add("published module dependency cycle: " + cycle));

        Map<String, Object> report = new TreeMap<>();
        report.put("artifacts", artifacts);
        report.put("coordinateCount", artifacts.size());
        report.put("groupId", group);
        report.put("observedModuleEdges", new ArrayList<>(observedEdges));
        report.put("schema", "blue-published-repository-verification/1.0");
        report.put("valid", violations.isEmpty());
        report.put("version", version);
        report.put("violations", violations);
        write(DeterministicJson.write(report));
        if (!violations.isEmpty()) {
            throw new GradleException("Invalid staged Maven repository: "
                    + String.join("; ", violations));
        }
    }

    private static int inspectJar(String artifact, Path jar, List<String> violations) {
        int classCount = 0;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                classCount++;
                try (InputStream input = zip.getInputStream(entry)) {
                    int magic = readInt(input);
                    readUnsignedShort(input);
                    int major = readUnsignedShort(input);
                    if (magic != CLASS_MAGIC || major != JAVA_8_CLASS_MAJOR) {
                        violations.add(artifact + " contains non-Java-8 class "
                                + entry.getName() + " (major " + major + ")");
                    }
                }
                if (forbiddenClass(artifact, entry.getName())) {
                    violations.add(artifact + " contains forbidden class " + entry.getName());
                }
            }
        } catch (IOException exception) {
            throw new GradleException("Cannot inspect staged JAR " + jar, exception);
        }
        if (classCount == 0) {
            violations.add(artifact + " primary JAR contains no classes");
        }
        return classCount;
    }

    private static boolean forbiddenClass(String artifact, String name) {
        boolean fixtureRuntime = name.startsWith("blue/language/conformance/api/")
                || name.startsWith("blue/language/conformance/cli/")
                || name.startsWith("blue/language/conformance/contracts/")
                || name.startsWith("blue/language/conformance/runner/");
        if (artifact.equals("blue-language-model")) {
            return name.startsWith("blue/language/processor/")
                    || name.startsWith("blue/language/provider/") || fixtureRuntime;
        }
        if (artifact.equals("blue-language-core")) {
            return name.startsWith("blue/language/processor/")
                    || name.startsWith("blue/language/provider/ipfs/") || fixtureRuntime;
        }
        return artifact.equals("blue-contracts-core") && fixtureRuntime;
    }

    private static void inspectPom(
            String artifact,
            Path pom,
            Set<String> expected,
            Set<String> allowed,
            Set<String> observed,
            List<String> violations) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            NodeList dependencies = factory.newDocumentBuilder().parse(pom.toFile())
                    .getElementsByTagName("dependency");
            for (int index = 0; index < dependencies.getLength(); index++) {
                Element dependency = (Element) dependencies.item(index);
                String group = child(dependency, "groupId");
                String target = child(dependency, "artifactId");
                String scope = child(dependency, "scope");
                String systemPath = child(dependency, "systemPath");
                if ("system".equals(scope) || !systemPath.isEmpty()) {
                    violations.add(artifact + " POM contains filesystem/system dependency " + target);
                }
                if (target.equals("httpclient") && !artifact.equals("blue-language-ipfs")) {
                    violations.add(artifact + " owns forbidden HTTP dependency");
                }
                if (target.equals("reflections") && !artifact.equals("blue-language-mapping")) {
                    violations.add(artifact + " owns forbidden classpath-scanning dependency");
                }
                if (group.equals("blue.language") && expected.contains(target)) {
                    String edge = artifact + "->" + target;
                    observed.add(edge);
                    if (!allowed.contains(edge)) {
                        violations.add("undeclared published module edge " + edge);
                    }
                }
            }
        } catch (Exception exception) {
            throw new GradleException("Cannot inspect staged POM " + pom, exception);
        }
    }

    private static List<String> cycles(Set<String> modules, Set<String> edges) {
        Map<String, Set<String>> adjacency = new TreeMap<>();
        modules.forEach(module -> adjacency.put(module, new TreeSet<>()));
        for (String edge : edges) {
            String[] parts = edge.split("->", 2);
            adjacency.get(parts[0]).add(parts[1]);
        }
        List<String> cycles = new ArrayList<>();
        for (String module : modules) {
            Deque<String> path = new ArrayDeque<>();
            findCycle(module, module, adjacency, path, new TreeSet<>(), cycles);
        }
        return new ArrayList<>(new TreeSet<>(cycles));
    }

    private static void findCycle(
            String origin,
            String current,
            Map<String, Set<String>> adjacency,
            Deque<String> path,
            Set<String> visiting,
            List<String> cycles) {
        path.addLast(current);
        visiting.add(current);
        for (String target : adjacency.getOrDefault(current, Collections.emptySet())) {
            if (target.equals(origin) && path.size() > 1) {
                cycles.add(String.join(" -> ", path) + " -> " + origin);
            } else if (!visiting.contains(target)) {
                findCycle(origin, target, adjacency, path, visiting, cycles);
            }
        }
        visiting.remove(current);
        path.removeLast();
    }

    private static String child(Element parent, String name) {
        NodeList values = parent.getElementsByTagName(name);
        if (values.getLength() == 0) {
            return "";
        }
        Node value = values.item(0);
        return value.getTextContent().trim();
    }

    private static int readInt(InputStream input) throws IOException {
        return (readUnsignedShort(input) << 16) | readUnsignedShort(input);
    }

    private static int readUnsignedShort(InputStream input) throws IOException {
        int high = input.read();
        int low = input.read();
        if (high < 0 || low < 0) {
            throw new IOException("Unexpected end of class file");
        }
        return (high << 8) | low;
    }

    private static void require(Path path, String artifact, List<String> violations) {
        if (path == null || !Files.isRegularFile(path)) {
            violations.add(artifact + " is missing a staged publication file");
        }
    }

    private static Path artifactFile(
            Path directory, String artifact, String version, String suffix, boolean primaryJar) {
        Path exact = directory.resolve(artifact + "-" + version + suffix);
        if (Files.isRegularFile(exact)) {
            return exact;
        }
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
            List<Path> candidates = entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(artifact + "-"))
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .filter(path -> !primaryJar || (!path.getFileName().toString()
                            .endsWith("-sources.jar") && !path.getFileName().toString()
                            .endsWith("-javadoc.jar")))
                    .sorted().collect(java.util.stream.Collectors.toList());
            if (candidates.size() > 1) {
                throw new GradleException("Ambiguous staged files for " + artifact
                        + " and suffix " + suffix + ": " + candidates);
            }
            return candidates.isEmpty() ? null : candidates.get(0);
        } catch (IOException exception) {
            throw new GradleException("Cannot inspect staged coordinate directory "
                    + directory, exception);
        }
    }

    private static String oneLine(String value, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.contains("\n") || normalized.contains("\r")) {
            throw new GradleException("Published repository " + label + " must be one line");
        }
        return normalized;
    }

    private void write(String value) {
        Path output = getReportFile().get().getAsFile().toPath();
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, value, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot write published repository report " + output, exception);
        }
    }
}
