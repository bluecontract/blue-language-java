package blue.buildlogic.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.gradle.api.GradleException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Securely reads deterministic test and test-case evidence from Gradle JUnit XML. */
public final class JUnitEvidence {

    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_PASSED = "PASSED";
    private static final String STATUS_SKIPPED = "SKIPPED";

    private JUnitEvidence() {}

    /** Parses and merges suites by name while preserving exact test-case outcomes. */
    public static Summary parse(
            Collection<Path> resultFiles, String sourceTask, boolean includeTestCases) {
        List<Path> files = new ArrayList<>();
        for (Path file : resultFiles) {
            if (Files.isRegularFile(file) && file.getFileName().toString().endsWith(".xml")) {
                files.add(file);
            }
        }
        files.sort(Comparator.comparing(path -> path.toAbsolutePath().normalize().toString()));
        if (files.isEmpty()) {
            throw new GradleException(sourceTask + " produced no JUnit XML test suites");
        }

        Map<String, MutableSuite> suites = new TreeMap<>();
        for (Path file : files) {
            Element suite = parse(file).getDocumentElement();
            String name = suite.getAttribute("name").trim();
            if (name.isEmpty()) {
                name = file.getFileName().toString();
            }
            int tests = integerAttribute(suite, "tests", file);
            int failed = integerAttribute(suite, "failures", file)
                    + integerAttribute(suite, "errors", file);
            int skipped = integerAttribute(suite, "skipped", file);
            int passed = tests - failed - skipped;
            if (passed < 0) {
                throw new GradleException("Invalid JUnit counts in " + file);
            }
            MutableSuite value = suites.computeIfAbsent(name, MutableSuite::new);
            value.add(tests, passed, failed, skipped);
            if (includeTestCases) {
                addTestCases(suite, value.testCases);
            }
        }

        List<Suite> values = new ArrayList<>();
        for (MutableSuite suite : suites.values()) {
            if (suite.tests > 0) {
                values.add(suite.freeze());
            }
        }
        if (values.isEmpty()) {
            throw new GradleException(sourceTask + " executed no tests");
        }
        return new Summary(sourceTask, values);
    }

    private static Document parse(Path file) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(file.toFile());
        } catch (IOException | ParserConfigurationException | SAXException exception) {
            throw new GradleException("Cannot parse JUnit XML evidence: " + file, exception);
        }
    }

    private static int integerAttribute(Element suite, String name, Path file) {
        String value = suite.getAttribute(name);
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new GradleException(
                    "Invalid JUnit integer '" + name + "' in " + file, exception);
        }
    }

    private static void addTestCases(Element suite, List<TestCase> output) {
        NodeList cases = suite.getElementsByTagName("testcase");
        for (int index = 0; index < cases.getLength(); index++) {
            Element testCase = (Element) cases.item(index);
            String status = testCase.getElementsByTagName("failure").getLength() > 0
                            || testCase.getElementsByTagName("error").getLength() > 0
                    ? STATUS_FAILED
                    : testCase.getElementsByTagName("skipped").getLength() > 0
                    ? STATUS_SKIPPED
                    : STATUS_PASSED;
            output.add(new TestCase(
                    testCase.getAttribute("classname"),
                    testCase.getAttribute("name"),
                    status));
        }
    }

    /** Immutable aggregate of all parsed suites. */
    public static final class Summary {

        private final String sourceTask;
        private final List<Suite> suites;
        private final int tests;
        private final int passed;
        private final int failed;
        private final int skipped;

        private Summary(String sourceTask, List<Suite> suites) {
            this.sourceTask = sourceTask;
            this.suites = Collections.unmodifiableList(new ArrayList<>(suites));
            this.tests = suites.stream().mapToInt(Suite::getTests).sum();
            this.passed = suites.stream().mapToInt(Suite::getPassed).sum();
            this.failed = suites.stream().mapToInt(Suite::getFailed).sum();
            this.skipped = suites.stream().mapToInt(Suite::getSkipped).sum();
        }

        public int getTests() { return tests; }
        public int getPassed() { return passed; }
        public int getFailed() { return failed; }
        public int getSkipped() { return skipped; }
        public List<Suite> getSuites() { return suites; }

        public boolean isConformant() {
            return tests > 0 && failed == 0 && skipped == 0 && passed == tests;
        }

        /** Finds all records for one exact test class and method. */
        public List<Map<String, Object>> records(String className, String methodName) {
            List<Map<String, Object>> matches = new ArrayList<>();
            for (Suite suite : suites) {
                for (TestCase testCase : suite.testCases) {
                    if (className.equals(testCase.className)
                            && matchesMethod(testCase.name, methodName)) {
                        matches.add(testCase.toMap());
                    }
                }
            }
            return matches;
        }

        /** Aggregates suites whose fully qualified name ends with the requested suffix. */
        public Map<String, Object> suiteEvidence(String suiteSuffix) {
            return suiteEvidence(suiteSuffix, false);
        }

        /** Aggregates matching suites and optionally retains their deterministic case records. */
        public Map<String, Object> suiteEvidence(
                String suiteSuffix, boolean includeTestCases) {
            int matchingTests = 0;
            int matchingPassed = 0;
            int matchingFailed = 0;
            int matchingSkipped = 0;
            List<String> names = new ArrayList<>();
            List<Map<String, Object>> cases = new ArrayList<>();
            for (Suite suite : suites) {
                if (suite.name.equals(suiteSuffix)
                        || suite.name.endsWith("." + suiteSuffix)) {
                    names.add(suite.name);
                    matchingTests += suite.tests;
                    matchingPassed += suite.passed;
                    matchingFailed += suite.failed;
                    matchingSkipped += suite.skipped;
                    if (includeTestCases) {
                        for (TestCase testCase : suite.testCases) {
                            cases.add(testCase.toMap());
                        }
                    }
                }
            }
            Map<String, Object> value = new TreeMap<>();
            value.put("evidenceKind", "passing-junit-suite");
            value.put("executed", !names.isEmpty());
            value.put("failed", matchingFailed);
            value.put("passed", matchingPassed);
            value.put("skipped", matchingSkipped);
            value.put("suiteNames", names);
            if (includeTestCases) {
                value.put("testCases", cases);
            }
            value.put("tests", matchingTests);
            return value;
        }

        public Map<String, Object> toMap() {
            List<Map<String, Object>> encodedSuites = new ArrayList<>();
            for (Suite suite : suites) {
                encodedSuites.add(suite.toMap());
            }
            Map<String, Object> value = new TreeMap<>();
            value.put("conformant", isConformant());
            value.put("executedSuites", suiteNames());
            value.put("failed", failed);
            value.put("passed", passed);
            value.put("skipped", skipped);
            value.put("sourceTask", sourceTask);
            value.put("suiteCount", suites.size());
            value.put("suites", encodedSuites);
            value.put("tests", tests);
            return value;
        }

        private List<String> suiteNames() {
            List<String> names = new ArrayList<>();
            for (Suite suite : suites) {
                names.add(suite.name);
            }
            return names;
        }

        private static boolean matchesMethod(String name, String method) {
            return name.equals(method)
                    || name.equals(method + "()")
                    || name.startsWith(method + "(");
        }
    }

    /** Immutable counts and optional cases for one suite name. */
    public static final class Suite {

        private final String name;
        private final int tests;
        private final int passed;
        private final int failed;
        private final int skipped;
        private final List<TestCase> testCases;

        private Suite(
                String name,
                int tests,
                int passed,
                int failed,
                int skipped,
                List<TestCase> testCases) {
            this.name = name;
            this.tests = tests;
            this.passed = passed;
            this.failed = failed;
            this.skipped = skipped;
            List<TestCase> sorted = new ArrayList<>(testCases);
            sorted.sort(Comparator.comparing((TestCase value) -> value.className)
                    .thenComparing(value -> value.name));
            this.testCases = Collections.unmodifiableList(sorted);
        }

        public int getTests() { return tests; }
        public int getPassed() { return passed; }
        public int getFailed() { return failed; }
        public int getSkipped() { return skipped; }

        private Map<String, Object> toMap() {
            List<Map<String, Object>> cases = new ArrayList<>();
            for (TestCase testCase : testCases) {
                cases.add(testCase.toMap());
            }
            Map<String, Object> value = new TreeMap<>();
            value.put("failed", failed);
            value.put("name", name);
            value.put("passed", passed);
            value.put("skipped", skipped);
            if (!cases.isEmpty()) {
                value.put("testCases", cases);
            }
            value.put("tests", tests);
            return value;
        }
    }

    private static final class MutableSuite {

        private final String name;
        private final List<TestCase> testCases = new ArrayList<>();
        private int tests;
        private int passed;
        private int failed;
        private int skipped;

        private MutableSuite(String name) {
            this.name = name;
        }

        private void add(int tests, int passed, int failed, int skipped) {
            this.tests += tests;
            this.passed += passed;
            this.failed += failed;
            this.skipped += skipped;
        }

        private Suite freeze() {
            return new Suite(name, tests, passed, failed, skipped, testCases);
        }
    }

    private static final class TestCase {

        private final String className;
        private final String name;
        private final String status;

        private TestCase(String className, String name, String status) {
            this.className = className;
            this.name = name;
            this.status = status;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> value = new TreeMap<>();
            value.put("className", className);
            value.put("name", name);
            value.put("status", status);
            return value;
        }
    }
}
