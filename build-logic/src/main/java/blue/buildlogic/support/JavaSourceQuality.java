package blue.buildlogic.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.api.GradleException;

/** Stable source-level facts used by documentation and final-quality evidence. */
public final class JavaSourceQuality {

    private static final Pattern PACKAGE = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][A-Za-z0-9_$.]*)\\s*;");
    private static final Pattern PUBLIC_TYPE = Pattern.compile(
            "(?m)^\\s*public\\s+(?:(?:final|abstract|sealed|non-sealed|strictfp)\\s+)*"
                    + "(@interface|class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\b");
    private static final Pattern PUBLIC_METHOD = Pattern.compile(
            "(?m)^\\s*public\\s+(?!class\\b|interface\\b|enum\\b|record\\b|@interface\\b)"
                    + "[^=;{}]+\\([^;{}]*\\)\\s*(?:throws\\s+[^;{]+)?(?:\\{|;)");

    private JavaSourceQuality() {}

    /** Reads production Java files in repository-relative order. */
    public static Analysis analyze(Path repositoryRoot, Collection<Path> javaSources) {
        Path root = repositoryRoot.toAbsolutePath().normalize();
        List<Path> sorted = new ArrayList<>(javaSources);
        sorted.removeIf(path -> !Files.isRegularFile(path)
                || !path.getFileName().toString().endsWith(".java"));
        sorted.sort(Comparator.comparing(path -> relative(root, path)));

        List<SourceFile> files = new ArrayList<>();
        Map<String, TreeSet<String>> publicTypesByPackage = new TreeMap<>();
        TreeSet<String> packagesWithPackageInfo = new TreeSet<>();
        for (Path source : sorted) {
            String content = read(source);
            String relativePath = relative(root, source);
            String packageName = match(PACKAGE, content, 1);
            String module = module(relativePath);
            String fileName = source.getFileName().toString();
            String expectedType = fileName.substring(0, fileName.length() - ".java".length());
            TypeDeclaration declaration = publicDeclaration(content, expectedType);
            int lineCount = lineCount(content);
            int publicMethodCount = declaration == null ? 0 : matches(PUBLIC_METHOD, content);
            SourceFile file = new SourceFile(
                    source.toAbsolutePath().normalize(),
                    module,
                    relativePath,
                    packageName,
                    expectedType,
                    declaration,
                    lineCount,
                    publicMethodCount);
            files.add(file);
            if ("package-info.java".equals(fileName) && packageName != null) {
                packagesWithPackageInfo.add(packageName);
            }
            if (declaration != null && packageName != null) {
                publicTypesByPackage.computeIfAbsent(packageName, ignored -> new TreeSet<>())
                        .add(packageName + "." + declaration.name);
            }
        }
        return new Analysis(files, publicTypesByPackage, packagesWithPackageInfo);
    }

    private static TypeDeclaration publicDeclaration(String content, String expectedType) {
        Matcher matcher = PUBLIC_TYPE.matcher(content);
        while (matcher.find()) {
            if (expectedType.equals(matcher.group(2))) {
                return new TypeDeclaration(matcher.group(1), matcher.group(2),
                        content.substring(matcher.start(), matcher.end()).contains("abstract"));
            }
        }
        return null;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new GradleException("Cannot read Java source quality input: " + file, exception);
        }
    }

    private static String match(Pattern pattern, String value, int group) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(group) : null;
    }

    private static int matches(Pattern pattern, String value) {
        int count = 0;
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static int lineCount(String content) {
        if (content.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int index = 0; index < content.length(); index++) {
            if (content.charAt(index) == '\n') {
                lines++;
            }
        }
        return content.endsWith("\n") ? lines - 1 : lines;
    }

    private static String module(String relativePath) {
        int separator = relativePath.indexOf('/');
        return separator < 0 ? ":root" : relativePath.substring(0, separator);
    }

    private static String relative(Path root, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new GradleException("Java source is outside repository root: " + path);
        }
        return root.relativize(normalized).toString().replace('\\', '/');
    }

    /** Immutable repository source analysis. */
    public static final class Analysis {

        private final List<SourceFile> files;
        private final Map<String, List<String>> publicTypesByPackage;
        private final java.util.Set<String> packagesWithPackageInfo;

        private Analysis(
                List<SourceFile> files,
                Map<String, TreeSet<String>> publicTypesByPackage,
                java.util.Set<String> packagesWithPackageInfo) {
            this.files = Collections.unmodifiableList(new ArrayList<>(files));
            Map<String, List<String>> packages = new LinkedHashMap<>();
            publicTypesByPackage.forEach((name, types) -> packages.put(
                    name, Collections.unmodifiableList(new ArrayList<>(types))));
            this.publicTypesByPackage = Collections.unmodifiableMap(packages);
            this.packagesWithPackageInfo = Collections.unmodifiableSet(
                    new TreeSet<>(packagesWithPackageInfo));
        }

        public List<SourceFile> files() {
            return files;
        }

        public Map<String, List<String>> publicTypesByPackage() {
            return publicTypesByPackage;
        }

        public java.util.Set<String> packagesWithPackageInfo() {
            return packagesWithPackageInfo;
        }

        public int publicTypeCount() {
            return publicTypesByPackage.values().stream().mapToInt(List::size).sum();
        }

        public List<String> missingPackageInfo() {
            List<String> missing = new ArrayList<>();
            for (String packageName : publicTypesByPackage.keySet()) {
                if (!packagesWithPackageInfo.contains(packageName)) {
                    missing.add(packageName);
                }
            }
            return missing;
        }

        public List<SourceFile> largestFiles(int limit) {
            List<SourceFile> sorted = new ArrayList<>(files);
            sorted.sort(Comparator.comparingInt(SourceFile::lineCount).reversed()
                    .thenComparing(SourceFile::relativePath));
            return Collections.unmodifiableList(
                    new ArrayList<>(sorted.subList(0, Math.min(limit, sorted.size()))));
        }
    }

    /** Facts about one repository Java source file and its top-level public type. */
    public static final class SourceFile {

        private final Path sourcePath;
        private final String module;
        private final String relativePath;
        private final String packageName;
        private final String expectedType;
        private final TypeDeclaration declaration;
        private final int lineCount;
        private final int publicMethodCount;

        private SourceFile(
                Path sourcePath,
                String module,
                String relativePath,
                String packageName,
                String expectedType,
                TypeDeclaration declaration,
                int lineCount,
                int publicMethodCount) {
            this.sourcePath = sourcePath;
            this.module = module;
            this.relativePath = relativePath;
            this.packageName = packageName;
            this.expectedType = expectedType;
            this.declaration = declaration;
            this.lineCount = lineCount;
            this.publicMethodCount = publicMethodCount;
        }

        public String module() {
            return module;
        }

        public Path sourcePath() {
            return sourcePath;
        }

        public String relativePath() {
            return relativePath;
        }

        public String packageName() {
            return packageName;
        }

        public String typeName() {
            return expectedType;
        }

        public String qualifiedTypeName() {
            return packageName == null ? expectedType : packageName + "." + expectedType;
        }

        public boolean isPublic() {
            return declaration != null;
        }

        public String kind() {
            return declaration == null ? null : declaration.kind;
        }

        public boolean isAbstract() {
            return declaration != null && declaration.abstractType;
        }

        public int lineCount() {
            return lineCount;
        }

        public int publicMethodCount() {
            return publicMethodCount;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> value = new TreeMap<>();
            value.put("lineCount", lineCount);
            value.put("module", module);
            value.put("path", relativePath);
            value.put("public", isPublic());
            value.put("publicMethodCount", publicMethodCount);
            value.put("type", qualifiedTypeName());
            return value;
        }
    }

    private static final class TypeDeclaration {

        private final String kind;
        private final String name;
        private final boolean abstractType;

        private TypeDeclaration(String kind, String name, boolean abstractType) {
            this.kind = kind;
            this.name = name;
            this.abstractType = abstractType;
        }
    }
}
