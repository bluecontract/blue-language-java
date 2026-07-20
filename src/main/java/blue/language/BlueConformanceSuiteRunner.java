package blue.language;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.provider.BasicNodeProvider;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.NodeContentHandler;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.CircularBlueIdCalculator;
import blue.language.utils.MergeReverser;
import blue.language.utils.Nodes;
import blue.language.utils.Properties;
import blue.language.utils.UncheckedObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BlueConformanceSuiteRunner {

    private static final String FIXTURE_ROOT = "blue-language-1.0/fixtures/";
    private static final Set<String> OPERATIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "parseSource",
            "parseBlueIdInput",
            "calculateBlueId",
            "calculateCircularSetBlueIds",
            "preprocess",
            "resolve",
            "scenario",
            "canonicalize",
            "assertMinimizedOverlayRoundTrip",
            "calculateContentBlueId",
            "calculateSemanticBlueId",
            "expand",
            "collapse",
            "assertSameNodeBlueId",
            "assertViewPath",
            "registryNodeHashesToPublishedBlueId",
            "changingRegistryDescriptionChangesBlueId",
            "lintPublishableDocumentation"
    )));

    private BlueConformanceSuiteRunner() {
    }

    public static BlueConformanceReport run(Blue blue) {
        BlueConformanceReport metadata = blue.conformanceReport();
        List<String> passed = new ArrayList<>();
        List<BlueConformanceFailure> failures = new ArrayList<>();
        for (FixtureEntry fixture : fixtureEntries()) {
            try {
                runFixture(fixture);
                passed.add(fixture.id);
            } catch (RuntimeException | AssertionError | VirtualMachineError e) {
                failures.add(failure(fixture, e));
            }
        }
        return new BlueConformanceReport(
                metadata.getSpecVersion(),
                metadata.getCoreRegistryBlueIds(),
                metadata.getFixturePackageIdentity(),
                metadata.getFixtureIds(),
                passed,
                Collections.emptyList(),
                metadata.getFixtureCategories(),
                failures);
    }

    public static Set<String> knownOperations() {
        return OPERATIONS;
    }

    public static void validateFixtureMetadataForTest(JsonNode spec) {
        validateFixtureMetadata(spec);
    }

    private static List<FixtureEntry> fixtureEntries() {
        JsonNode manifest = readResource(FIXTURE_ROOT + "manifest.yaml");
        JsonNode fixtures = requireNonNull(manifest, "fixtures");
        if (!fixtures.isArray()) {
            throw new IllegalArgumentException("Fixture manifest field \"fixtures\" must be a list.");
        }
        List<FixtureEntry> entries = new ArrayList<>();
        for (JsonNode entry : fixtures) {
            String id = requireNonNull(entry, "id").asText();
            String category = requireNonNull(entry, "category").asText();
            BlueFixtureCategory.fromLabel(category);
            String path = requireNonNull(entry, "path").asText();
            entries.add(new FixtureEntry(id, category, path));
        }
        return entries;
    }

    private static void runFixture(FixtureEntry fixture) {
        JsonNode spec = readResource(FIXTURE_ROOT + fixture.path);
        validateFixtureMatchesManifest(fixture, spec);
        String operation = text(spec, "operation", "calculateBlueId");
        boolean expectError = spec.path("expectError").asBoolean(false);
        if (expectError) {
            try {
                runOperation(spec, operation);
            } catch (RuntimeException expected) {
                assertExpectedErrorCategory(spec, expected);
                return;
            }
            throw new AssertionError("Fixture expected an error but operation succeeded: " + fixture.id);
        }

        Object actual = runOperation(spec, operation);
        if ("calculateBlueId".equals(operation)
                || "assertSameNodeBlueId".equals(operation)) {
            assertExpectedText(spec, "expectedNodeBlueId", (String) actual);
            if (!"assertSameNodeBlueId".equals(operation)) {
                assertEquivalents((String) actual, spec.get("alsoEquivalentTo"));
                assertDifferent((String) actual, spec.get("alsoDifferentFrom"));
            }
        } else if ("calculateCircularSetBlueIds".equals(operation)) {
            assertExpectedTextList(spec, "expectedBlueIds", (List<String>) actual);
        } else if ("calculateContentBlueId".equals(operation)
                || "calculateSemanticBlueId".equals(operation)
                || "assertMinimizedOverlayRoundTrip".equals(operation)) {
            assertExpectedText(spec, "expectedContentBlueId", (String) actual);
        } else if ("parseSource".equals(operation) || "parseBlueIdInput".equals(operation)) {
            assertExpectedNode(spec, "expectedParsed", (Node) actual);
        } else if ("preprocess".equals(operation)) {
            assertExpectedNode(spec, "expectedPreprocessed", (Node) actual);
        } else if ("canonicalize".equals(operation)) {
            assertExpectedNode(spec, "expectedCanonicalOverlay", (Node) actual);
            assertCanonicalOverlayIsValidBlueIdInput((Node) actual);
        } else if ("resolve".equals(operation)) {
            assertExpectedNode(spec, "expectedResolved", (Node) actual);
        } else if ("scenario".equals(operation)) {
            // Step-specific assertions are performed while running the scenario.
        } else if ("expand".equals(operation)) {
            assertExpectedNode(spec, "expectedExpanded", (Node) actual);
            assertExpectedNodeBlueIdIfPresent(spec, (Node) actual, requirePresent(spec, "source"));
        } else if ("collapse".equals(operation)) {
            assertExpectedNode(spec, "expectedCollapsed", (Node) actual);
            assertExpectedNodeBlueIdIfPresent(spec, (Node) actual, requirePresent(spec, "source"));
        } else if ("assertViewPath".equals(operation)) {
            // Operation-specific assertions are performed while running the fixture.
        } else if ("registryNodeHashesToPublishedBlueId".equals(operation)
                || "changingRegistryDescriptionChangesBlueId".equals(operation)
                || "lintPublishableDocumentation".equals(operation)) {
            // Operation-specific assertions are performed while running the fixture.
        }
    }

    private static Object runOperation(JsonNode spec, String operation) {
        if ("scenario".equals(operation)) {
            runScenario(spec);
            return null;
        }
        if ("assertMinimizedOverlayRoundTrip".equals(operation)) {
            return runMinimizedOverlayRoundTrip(spec);
        }
        Blue blue = new Blue(provider(spec.get("provider")));
        if ("parseSource".equals(operation)) {
            return blue.parseSourceYaml(UncheckedObjectMapper.YAML_MAPPER.writeValueAsString(requirePresent(spec, "source")));
        }
        if ("parseBlueIdInput".equals(operation)) {
            return blue.parseBlueIdInputYaml(UncheckedObjectMapper.YAML_MAPPER.writeValueAsString(requirePresent(spec, "input")));
        }
        if ("calculateBlueId".equals(operation)) {
            Node input = readNode(requirePresent(spec, "input"));
            String blueId = BlueIdCalculator.calculateBlueId(input);
            assertEquals(blueId, FrozenNode.fromNode(input).blueId());
            return blueId;
        }
        if ("calculateCircularSetBlueIds".equals(operation)) {
            Node documents = readNode(requirePresent(spec, "documents"));
            if (documents.getItems() == null) {
                throw new IllegalArgumentException("calculateCircularSetBlueIds fixtures require a documents list.");
            }
            return CircularBlueIdCalculator.calculateCircularSetBlueIds(documents.getItems());
        }
        if ("preprocess".equals(operation)) {
            return blue.preprocess(readNode(requirePresent(spec, "source")));
        }
        if ("resolve".equals(operation)) {
            return blue.resolve(readNode(requirePresent(spec, "source")));
        }
        if ("canonicalize".equals(operation)) {
            return blue.canonicalize(readNode(requirePresent(spec, "source")));
        }
        if ("calculateContentBlueId".equals(operation) || "calculateSemanticBlueId".equals(operation)) {
            return blue.calculateSemanticBlueId(readNode(requirePresent(spec, "source")));
        }
        if ("expand".equals(operation)) {
            return blue.expand(readNode(requirePresent(spec, "source")));
        }
        if ("collapse".equals(operation)) {
            return blue.collapse(readNode(requirePresent(spec, "source")));
        }
        if ("assertSameNodeBlueId".equals(operation)) {
            String left = BlueIdCalculator.calculateBlueId(readNode(requirePresent(spec, "left")));
            String right = BlueIdCalculator.calculateBlueId(readNode(requirePresent(spec, "right")));
            assertEquals(left, right);
            return left;
        }
        if ("assertViewPath".equals(operation)) {
            runAssertViewPath(spec);
            return null;
        }
        if ("registryNodeHashesToPublishedBlueId".equals(operation)) {
            runRegistryNodeHashesToPublishedBlueId(spec);
            return null;
        }
        if ("changingRegistryDescriptionChangesBlueId".equals(operation)) {
            runChangingRegistryDescriptionChangesBlueId(spec);
            return null;
        }
        if ("lintPublishableDocumentation".equals(operation)) {
            runLintPublishableDocumentation(spec);
            return null;
        }
        throw new IllegalArgumentException("Unsupported fixture operation: " + operation);
    }

    private static String runMinimizedOverlayRoundTrip(JsonNode spec) {
        Node source = readNode(requirePresent(spec, "source"));
        Blue writer = new Blue(provider(spec.get("provider")));
        ResolvedSnapshot original = writer.resolveToSnapshot(source);
        assertExpectedText(spec, "expectedContentBlueId", original.blueId());

        Node minimized = new MergeReverser()
                .reverseToMinimizedOverlay(original.resolvedRoot());
        Blue reader = new Blue(provider(spec.get("provider")));
        ResolvedSnapshot reloaded = reader.resolveToSnapshot(minimized);

        assertEquals(original.blueId(), reloaded.blueId());
        assertEquals(
                original.frozenResolvedRoot().resolvedStructuralKey(),
                reloaded.frozenResolvedRoot().resolvedStructuralKey());
        return reloaded.blueId();
    }

    private static void runScenario(JsonNode spec) {
        Blue blue = new Blue(provider(spec.get("provider")));
        JsonNode steps = requireNonNull(spec, "steps");
        if (!steps.isArray() || steps.size() == 0) {
            throw new IllegalArgumentException("Scenario fixtures require at least one step.");
        }
        for (int index = 0; index < steps.size(); index++) {
            JsonNode step = steps.get(index);
            String action = requireNonNull(step, "action").asText();
            boolean expectError = step.path("expectError").asBoolean(false);
            if (expectError) {
                try {
                    runScenarioAction(blue, step, action);
                } catch (RuntimeException expected) {
                    assertExpectedErrorCategory(step, expected);
                    continue;
                }
                throw new AssertionError("Scenario step " + index
                        + " expected an error but succeeded: " + action);
            }

            Object actual = runScenarioAction(blue, step, action);
            assertScenarioStep(step, action, actual);
        }
    }

    private static Object runScenarioAction(Blue blue, JsonNode step, String action) {
        Node source = readNode(requirePresent(step, "source"));
        if ("resolve".equals(action)) {
            return blue.resolve(source);
        }
        if ("canonicalize".equals(action)) {
            return blue.canonicalize(source);
        }
        if ("calculateContentBlueId".equals(action)) {
            return blue.calculateSemanticBlueId(source);
        }
        throw new IllegalArgumentException("Unsupported scenario action: " + action);
    }

    private static void assertScenarioStep(JsonNode step, String action, Object actual) {
        if ("resolve".equals(action)) {
            Node resolved = (Node) actual;
            assertExpectedNodeIfPresent(step, "expectedResolved", resolved);
            assertExpectedResolvedPaths(step, resolved);
            return;
        }
        if ("canonicalize".equals(action)) {
            Node canonical = (Node) actual;
            assertExpectedNode(step, "expectedCanonicalOverlay", canonical);
            assertCanonicalOverlayIsValidBlueIdInput(canonical);
            if (step.has("expectedContentBlueId")) {
                assertExpectedText(step, "expectedContentBlueId",
                        BlueIdCalculator.calculateBlueId(canonical));
            }
            return;
        }
        if ("calculateContentBlueId".equals(action)) {
            assertExpectedText(step, "expectedContentBlueId", (String) actual);
            return;
        }
        throw new IllegalArgumentException("Unsupported scenario action: " + action);
    }

    private static void assertExpectedNodeIfPresent(JsonNode spec, String field, Node actual) {
        if (spec.has(field)) {
            assertExpectedNode(spec, field, actual);
        }
    }

    private static void assertExpectedResolvedPaths(JsonNode step, Node resolved) {
        JsonNode paths = step.get("expectedResolvedPaths");
        if (paths == null) {
            return;
        }
        for (JsonNode assertion : paths) {
            String path = requireNonNull(assertion, "path").asText();
            Node selected = BlueViewPath.select(resolved, path);
            assertExpectedNode(assertion, "expectedNode", selected);
        }
    }

    private static void runAssertViewPath(JsonNode spec) {
        Node document = readNode(requirePresent(spec, "document"));
        JsonNode assertions = requireNonNull(spec, "assertions");
        if (!assertions.isArray() || assertions.size() == 0) {
            throw new IllegalArgumentException("assertViewPath requires at least one assertion.");
        }
        for (JsonNode assertion : assertions) {
            String path = requireNonNull(assertion, "path").asText();
            Node selected = BlueViewPath.select(document, path);
            if (assertion.path("expectedRoot").asBoolean(false)) {
                assertEquals(BlueIdCalculator.calculateBlueId(document), BlueIdCalculator.calculateBlueId(selected));
            }
            if (assertion.has("expectedNode")) {
                assertNodeEquals(readNode(requireNonNull(assertion, "expectedNode")), selected);
            }
        }
    }

    private static void runRegistryNodeHashesToPublishedBlueId(JsonNode spec) {
        requireCoreRegistryKind(spec);
        String registryKey = requireNonNull(spec, "registryKey").asText();
        String expected = requireNonNull(spec, "expectedPublishedBlueId").asText();
        BlueCoreTypeRegistry registry = BlueCoreTypeRegistry.INSTANCE;
        String calculated = BlueIdCalculator.calculateBlueId(registry.node(registryKey));
        assertEquals(expected, calculated);
        assertEquals(expected, registry.blueId(registryKey));
        assertEquals(expected, Properties.CORE_TYPE_NAME_TO_BLUE_ID_MAP.get(registryKey));
    }

    private static void runChangingRegistryDescriptionChangesBlueId(JsonNode spec) {
        requireCoreRegistryKind(spec);
        String registryKey = requireNonNull(spec, "registryKey").asText();
        Node original = BlueCoreTypeRegistry.INSTANCE.node(registryKey);
        Node mutated = original.clone();
        JsonNode mutation = requireNonNull(spec, "mutation");
        String field = requireNonNull(mutation, "field").asText();
        if (!"description".equals(field)) {
            throw new IllegalArgumentException("Unsupported registry mutation field: " + field);
        }
        mutated.description((mutated.getDescription() == null ? "" : mutated.getDescription())
                + requireNonNull(mutation, "append").asText());
        boolean changed = !BlueIdCalculator.calculateBlueId(original).equals(BlueIdCalculator.calculateBlueId(mutated));
        assertEquals(requireNonNull(spec, "expectBlueIdChanged").asBoolean(), changed);
    }

    private static void runLintPublishableDocumentation(JsonNode spec) {
        JsonNode files = requireNonNull(spec, "publishableFiles");
        if (!files.isArray() || files.size() == 0) {
            throw new IllegalArgumentException("lintPublishableDocumentation requires publishableFiles.");
        }
        JsonNode requiredHeadings = spec.get("requiredHeadings");
        JsonNode forbiddenJoinedTerms = spec.get("forbiddenJoinedTerms");
        if ((requiredHeadings == null || !requiredHeadings.isArray() || requiredHeadings.size() == 0)
                && (forbiddenJoinedTerms == null || !forbiddenJoinedTerms.isArray() || forbiddenJoinedTerms.size() == 0)) {
            throw new IllegalArgumentException("lintPublishableDocumentation requires headings or forbidden terms.");
        }
        for (JsonNode file : files) {
            String path = file.asText();
            String content = readTextResource(path);
            if (requiredHeadings != null) {
                for (JsonNode heading : requiredHeadings) {
                    if (!content.contains(heading.asText())) {
                        throw new AssertionError("Missing required heading in " + path + ": " + heading.asText());
                    }
                }
            }
            if (forbiddenJoinedTerms != null) {
                for (JsonNode entry : forbiddenJoinedTerms) {
                    JsonNode tokens = requireNonNull(entry, "tokens");
                    String joiner = requireNonNull(entry, "joiner").asText();
                    List<String> tokenValues = new ArrayList<>();
                    for (JsonNode token : tokens) {
                        tokenValues.add(token.asText());
                    }
                    String forbidden = String.join(joiner, tokenValues);
                    if (content.contains(forbidden)) {
                        throw new AssertionError("Forbidden term in " + path + ": " + forbidden);
                    }
                }
            }
        }
    }

    private static void requireCoreRegistryKind(JsonNode spec) {
        String registryKind = requireNonNull(spec, "registryKind").asText();
        if (!"Blue Language core type registry".equals(registryKind)) {
            throw new IllegalArgumentException("Unsupported registry kind: " + registryKind);
        }
    }

    private static NodeProvider provider(JsonNode providerSpec) {
        if (providerSpec == null || providerSpec.isNull()) {
            return blueId -> null;
        }
        if (!providerSpec.isArray()) {
            throw new IllegalArgumentException("Fixture provider must be a list.");
        }
        Map<String, Node> nodesByBlueId = new LinkedHashMap<>();
        List<NodeProvider> cyclicSetProviders = new ArrayList<>();
        for (JsonNode entry : providerSpec) {
            if (entry.has("cyclicSet")) {
                cyclicSetProviders.add(cyclicSetProvider(entry));
                continue;
            }
            String requestedBlueId = text(entry, "requestedBlueId", text(entry, "blueId", null));
            JsonNode nodeSpec = entry.has("returnedNode") ? entry.get("returnedNode") : entry.get("node");
            if (requestedBlueId == null || nodeSpec == null || nodeSpec.isNull()) {
                throw new IllegalArgumentException("Fixture provider entries require requestedBlueId and node/returnedNode.");
            }
            nodesByBlueId.put(requestedBlueId, readNode(nodeSpec));
        }
        return new FixtureNodeProvider(nodesByBlueId, cyclicSetProviders);
    }

    private static NodeProvider cyclicSetProvider(JsonNode entry) {
        Node documentsNode = readNode(requireNonNull(entry, "cyclicSet"));
        List<Node> documents = documentsNode.getItems();
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("Fixture cyclicSet must contain at least one document.");
        }

        Map<String, String> idsByName = new LinkedHashMap<>();
        NodeProvider provider;
        if (documents.size() == 1) {
            Node document = new Blue().preprocess(documents.get(0).clone());
            requireCyclicDocumentName(document, idsByName);
            List<String> memberIds = CircularBlueIdCalculator.calculateCircularSetBlueIds(documents);
            String memberId = memberIds.get(0);
            idsByName.put(document.getName(), memberId);
            provider = new SingletonCyclicSetProvider(document, memberId);
        } else {
            BasicNodeProvider basicProvider = new BasicNodeProvider(documentsNode);
            for (Node document : documents) {
                requireCyclicDocumentName(document, idsByName);
                idsByName.put(document.getName(), basicProvider.getBlueIdByName(document.getName()));
            }
            provider = basicProvider;
        }
        assertExpectedCyclicMemberBlueIds(entry, idsByName);
        return provider;
    }

    private static void requireCyclicDocumentName(Node document, Map<String, String> idsByName) {
        String name = document.getName();
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Fixture cyclicSet documents require unique names.");
        }
        if (idsByName.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate fixture cyclicSet document name: " + name);
        }
    }

    private static void assertExpectedCyclicMemberBlueIds(JsonNode entry,
                                                           Map<String, String> actualIdsByName) {
        JsonNode expected = requireNonNull(entry, "expectedMemberBlueIds");
        if (!expected.isObject() || expected.size() != actualIdsByName.size()) {
            throw new IllegalArgumentException(
                    "expectedMemberBlueIds must map every cyclicSet document name exactly once.");
        }
        actualIdsByName.forEach((name, actualBlueId) -> {
            JsonNode expectedBlueId = expected.get(name);
            if (expectedBlueId == null || expectedBlueId.isNull()) {
                throw new IllegalArgumentException(
                        "Missing expected cyclic member BlueId for document: " + name);
            }
            assertEquals(expectedBlueId.asText(), actualBlueId);
        });
    }

    private static void validateFixtureMatchesManifest(FixtureEntry fixture, JsonNode spec) {
        validateFixtureMetadata(spec);
        assertEquals(fixture.id, requireNonNull(spec, "id").asText());
        assertEquals(
                BlueFixtureCategory.fromLabel(fixture.category),
                BlueFixtureCategory.fromLabel(requireNonNull(spec, "category").asText()));
    }

    private static void validateFixtureMetadata(JsonNode spec) {
        requireNonNull(spec, "id");
        requireNonNull(spec, "category");
        requireNonNull(spec, "operation");
        if (spec.has("profile")) {
            throw new IllegalArgumentException("Fixtures must use category, not profile.");
        }
        BlueFixtureCategory.fromLabel(requireNonNull(spec, "category").asText());
        String operation = requireNonNull(spec, "operation").asText();
        if (!OPERATIONS.contains(operation)) {
            throw new IllegalArgumentException("Unsupported fixture operation: " + operation);
        }
        if ("scenario".equals(operation)) {
            validateScenarioMetadata(spec);
        } else if (!spec.path("expectError").asBoolean(false)) {
            requireExpectedOutput(spec, operation);
        } else {
            validateExpectedErrorCategoryFields(spec);
        }
    }

    private static void validateScenarioMetadata(JsonNode spec) {
        JsonNode steps = requireNonNull(spec, "steps");
        if (!steps.isArray() || steps.size() == 0) {
            throw new IllegalArgumentException("Scenario fixtures require at least one step.");
        }
        Set<String> actions = new HashSet<>(Arrays.asList(
                "resolve", "canonicalize", "calculateContentBlueId"));
        for (JsonNode step : steps) {
            String action = requireNonNull(step, "action").asText();
            if (!actions.contains(action)) {
                throw new IllegalArgumentException("Unsupported scenario action: " + action);
            }
            requireNonNull(step, "source");
            if (step.path("expectError").asBoolean(false)) {
                validateScenarioErrorStep(step);
                continue;
            }
            validateScenarioSuccessStep(step, action);
        }
    }

    private static void validateScenarioSuccessStep(JsonNode step, String action) {
        Set<String> outputFields = scenarioOutputFields(step);
        if ("resolve".equals(action)) {
            JsonNode paths = step.get("expectedResolvedPaths");
            if (paths != null && (!paths.isArray() || paths.size() == 0)) {
                throw new IllegalArgumentException("expectedResolvedPaths must be a non-empty list.");
            }
            boolean hasPaths = paths != null && paths.isArray() && paths.size() > 0;
            if (!step.has("expectedResolved") && !hasPaths) {
                throw new IllegalArgumentException(
                        "resolve requires expectedResolved or a non-empty expectedResolvedPaths list.");
            }
            requireOnlyScenarioOutputs(outputFields, "expectedResolved", "expectedResolvedPaths");
            return;
        }
        if ("canonicalize".equals(action)) {
            requireNonNull(step, "expectedCanonicalOverlay");
            requireOnlyScenarioOutputs(outputFields,
                    "expectedCanonicalOverlay", "expectedContentBlueId");
            return;
        }
        requireNonNull(step, "expectedContentBlueId");
        requireOnlyScenarioOutputs(outputFields, "expectedContentBlueId");
    }

    private static void validateScenarioErrorStep(JsonNode step) {
        boolean one = step.has("expectedErrorCategory") ^ step.has("expectedErrorCategories");
        if (!one) {
            throw new IllegalArgumentException("Scenario error steps require exactly one error-category field.");
        }
        validateExpectedErrorCategoryFields(step);
        if (!scenarioOutputFields(step).isEmpty()) {
            throw new IllegalArgumentException("Scenario error steps cannot declare success-output assertions.");
        }
    }

    private static Set<String> scenarioOutputFields(JsonNode step) {
        Set<String> fields = new HashSet<>();
        for (String field : Arrays.asList("expectedResolved", "expectedResolvedPaths",
                "expectedCanonicalOverlay", "expectedContentBlueId", "expectedProvenance")) {
            if (step.has(field)) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static void requireOnlyScenarioOutputs(Set<String> actual, String... allowedFields) {
        Set<String> allowed = new HashSet<>(Arrays.asList(allowedFields));
        if (!allowed.containsAll(actual)) {
            throw new IllegalArgumentException("Unsupported scenario assertions: " + actual);
        }
    }

    private static BlueConformanceFailure failure(FixtureEntry fixture, Throwable throwable) {
        String operation = null;
        try {
            operation = text(readResource(FIXTURE_ROOT + fixture.path), "operation", null);
        } catch (RuntimeException ignored) {
            // The fixture may be unreadable; keep the manifest-level failure details.
        }
        return new BlueConformanceFailure(
                fixture.id,
                BlueFixtureCategory.fromLabel(fixture.category),
                operation,
                throwable.getClass().getName(),
                throwable.getMessage(),
                BlueLanguageErrorClassifier.classify(throwable));
    }

    private static void requireExpectedOutput(JsonNode spec, String operation) {
        if ("calculateBlueId".equals(operation)
                || "assertSameNodeBlueId".equals(operation)) {
            requireNonNull(spec, "expectedNodeBlueId");
            return;
        }
        if ("calculateCircularSetBlueIds".equals(operation)) {
            requireNonNull(spec, "expectedBlueIds");
            return;
        }
        if ("calculateContentBlueId".equals(operation)
                || "calculateSemanticBlueId".equals(operation)
                || "assertMinimizedOverlayRoundTrip".equals(operation)) {
            requireNonNull(spec, "expectedContentBlueId");
            return;
        }
        if ("parseSource".equals(operation) || "parseBlueIdInput".equals(operation)) {
            requireNonNull(spec, "expectedParsed");
            return;
        }
        if ("preprocess".equals(operation)) {
            requireNonNull(spec, "expectedPreprocessed");
            return;
        }
        if ("canonicalize".equals(operation)) {
            requireNonNull(spec, "expectedCanonicalOverlay");
            return;
        }
        if ("resolve".equals(operation)) {
            requireNonNull(spec, "expectedResolved");
            return;
        }
        if ("scenario".equals(operation)) {
            requireNonNull(spec, "steps");
            return;
        }
        if ("expand".equals(operation)) {
            requireNonNull(spec, "expectedExpanded");
            return;
        }
        if ("collapse".equals(operation)) {
            requireNonNull(spec, "expectedCollapsed");
            return;
        }
        if ("assertViewPath".equals(operation)) {
            requireNonNull(spec, "assertions");
            return;
        }
        if ("registryNodeHashesToPublishedBlueId".equals(operation)) {
            requireNonNull(spec, "expectedPublishedBlueId");
            return;
        }
        if ("changingRegistryDescriptionChangesBlueId".equals(operation)) {
            requireNonNull(spec, "expectBlueIdChanged");
            return;
        }
        if ("lintPublishableDocumentation".equals(operation)) {
            requireNonNull(spec, "publishableFiles");
            if (!spec.has("requiredHeadings") && !spec.has("forbiddenJoinedTerms")) {
                throw new IllegalArgumentException("lintPublishableDocumentation must assert headings or forbidden terms.");
            }
            return;
        }
        throw new IllegalArgumentException("Unsupported fixture operation: " + operation);
    }

    private static void validateExpectedErrorCategoryFields(JsonNode spec) {
        if (spec.has("expectedErrorCategory")) {
            BlueLanguageErrorCategory.valueOf(requireNonNull(spec, "expectedErrorCategory").asText());
        }
        if (spec.has("expectedErrorCategories")) {
            JsonNode categories = requireNonNull(spec, "expectedErrorCategories");
            if (!categories.isArray() || categories.size() == 0) {
                throw new IllegalArgumentException("expectedErrorCategories must be a non-empty list.");
            }
            for (JsonNode category : categories) {
                BlueLanguageErrorCategory.valueOf(category.asText());
            }
        }
    }

    private static void assertExpectedErrorCategory(JsonNode spec, Throwable throwable) {
        JsonNode expected = spec.get("expectedErrorCategory");
        JsonNode allowed = spec.get("expectedErrorCategories");
        if ((expected == null || expected.isNull()) && (allowed == null || allowed.isNull())) {
            return;
        }
        BlueLanguageErrorCategory actual = BlueLanguageErrorClassifier.classify(throwable);
        if (expected != null && !expected.isNull()) {
            assertEquals(BlueLanguageErrorCategory.valueOf(expected.asText()), actual);
        }
        if (allowed != null && !allowed.isNull()) {
            for (JsonNode category : allowed) {
                if (BlueLanguageErrorCategory.valueOf(category.asText()) == actual) {
                    return;
                }
            }
            throw new AssertionError("Expected error category in " + allowed + " but was " + actual
                    + " for error: " + throwable.getMessage());
        }
    }

    private static void assertExpectedText(JsonNode spec, String field, String actual) {
        assertEquals(requireNonNull(spec, field).asText(), actual);
    }

    private static void assertExpectedTextList(JsonNode spec, String field, List<String> actual) {
        JsonNode expected = requireNonNull(spec, field);
        if (!expected.isArray()) {
            throw new AssertionError("Expected fixture field \"" + field + "\" to be a list.");
        }
        List<String> expectedValues = new ArrayList<>();
        for (JsonNode value : expected) {
            expectedValues.add(value.asText());
        }
        assertEquals(expectedValues, actual);
    }

    private static void assertExpectedNode(JsonNode spec, String field, Node actual) {
        assertNodeEquals(readNode(requireNonNull(spec, field)), actual);
    }

    private static void assertNodeEquals(Node expectedNode, Node actual) {
        JsonNode expected = UncheckedObjectMapper.YAML_MAPPER.readTree(
                UncheckedObjectMapper.YAML_MAPPER.writeValueAsString(expectedNode));
        JsonNode actualTree = UncheckedObjectMapper.YAML_MAPPER.readTree(
                UncheckedObjectMapper.YAML_MAPPER.writeValueAsString(actual));
        assertEquals(expected, actualTree);
    }

    private static void assertExpectedNodeBlueIdIfPresent(JsonNode spec, Node actual, JsonNode sourceSpec) {
        JsonNode expected = spec.get("expectedNodeBlueId");
        if (expected == null || expected.isNull()) {
            return;
        }
        String expectedBlueId = expected.asText();
        assertEquals(expectedBlueId, BlueIdCalculator.calculateBlueId(actual));
        assertEquals(expectedBlueId, BlueIdCalculator.calculateBlueId(readNode(sourceSpec)));
    }

    private static void assertEquivalents(String actualBlueId, JsonNode equivalents) {
        if (equivalents == null || equivalents.isNull()) {
            return;
        }
        if (equivalents.isArray()) {
            for (JsonNode equivalent : equivalents) {
                assertEquals(actualBlueId, BlueIdCalculator.calculateBlueId(readNode(equivalent)));
            }
        } else {
            assertEquals(actualBlueId, BlueIdCalculator.calculateBlueId(readNode(equivalents)));
        }
    }

    private static void assertDifferent(String actualBlueId, JsonNode differentInputs) {
        if (differentInputs == null || differentInputs.isNull()) {
            return;
        }
        if (differentInputs.isArray()) {
            for (JsonNode different : differentInputs) {
                assertNotEquals(actualBlueId, BlueIdCalculator.calculateBlueId(readNode(different)));
            }
        } else {
            assertNotEquals(actualBlueId, BlueIdCalculator.calculateBlueId(readNode(differentInputs)));
        }
    }

    private static void assertCanonicalOverlayIsValidBlueIdInput(Node canonical) {
        BlueIdCalculator.calculateBlueId(canonical);
        assertNoCanonicalOverlayControls(canonical, "/", false);
    }

    private static void assertNoCanonicalOverlayControls(Node node, String path, boolean listElement) {
        if (node == null) {
            if (listElement) {
                throw new AssertionError("Canonical Overlay contains null list element at " + path);
            }
            return;
        }
        if (node.getBlue() != null) {
            throw new AssertionError("Canonical Overlay contains blue at " + path);
        }
        if (node.getPreviousBlueId() != null) {
            throw new AssertionError("Canonical Overlay contains $previous at " + path);
        }
        if (node.getPosition() != null) {
            throw new AssertionError("Canonical Overlay contains $pos at " + path);
        }
        if (node.getProperties() != null && node.getProperties().containsKey("$replace")) {
            throw new AssertionError("Canonical Overlay contains $replace at " + path);
        }
        if (listElement && Nodes.isEmptyNode(node) && !Nodes.isEmptyPlaceholder(node)) {
            throw new AssertionError("Canonical Overlay contains empty-object list element at " + path);
        }
        assertNoCanonicalOverlayControls(node.getType(), appendPath(path, "type"), false);
        assertNoCanonicalOverlayControls(node.getItemType(), appendPath(path, "itemType"), false);
        assertNoCanonicalOverlayControls(node.getKeyType(), appendPath(path, "keyType"), false);
        assertNoCanonicalOverlayControls(node.getValueType(), appendPath(path, "valueType"), false);
        assertNoCanonicalOverlayControls(node.getBlue(), appendPath(path, "blue"), false);
        assertNoCanonicalOverlayControls(node.getContracts(), appendPath(path, "contracts"), false);
        assertNoCanonicalOverlayControls(node.getSchema(), appendPath(path, "schema"));
        if (node.getItems() != null) {
            for (int i = 0; i < node.getItems().size(); i++) {
                assertNoCanonicalOverlayControls(node.getItems().get(i), appendPath(path, String.valueOf(i)), true);
            }
        }
        if (node.getProperties() != null) {
            node.getProperties().forEach((key, value) ->
                    assertNoCanonicalOverlayControls(value, appendPath(path, key), false));
        }
    }

    private static void assertNoCanonicalOverlayControls(Schema schema, String path) {
        if (schema == null) {
            return;
        }
        assertNoCanonicalOverlayControls(schema.getRequired(), appendPath(path, "required"), false);
        assertNoCanonicalOverlayControls(schema.getMinLength(), appendPath(path, "minLength"), false);
        assertNoCanonicalOverlayControls(schema.getMaxLength(), appendPath(path, "maxLength"), false);
        assertNoCanonicalOverlayControls(schema.getMinimum(), appendPath(path, "minimum"), false);
        assertNoCanonicalOverlayControls(schema.getMaximum(), appendPath(path, "maximum"), false);
        assertNoCanonicalOverlayControls(schema.getExclusiveMinimum(), appendPath(path, "exclusiveMinimum"), false);
        assertNoCanonicalOverlayControls(schema.getExclusiveMaximum(), appendPath(path, "exclusiveMaximum"), false);
        assertNoCanonicalOverlayControls(schema.getMultipleOf(), appendPath(path, "multipleOf"), false);
        assertNoCanonicalOverlayControls(schema.getMinItems(), appendPath(path, "minItems"), false);
        assertNoCanonicalOverlayControls(schema.getMaxItems(), appendPath(path, "maxItems"), false);
        assertNoCanonicalOverlayControls(schema.getUniqueItems(), appendPath(path, "uniqueItems"), false);
        assertNoCanonicalOverlayControls(schema.getMinFields(), appendPath(path, "minFields"), false);
        assertNoCanonicalOverlayControls(schema.getMaxFields(), appendPath(path, "maxFields"), false);
        if (schema.getEnum() != null) {
            for (int i = 0; i < schema.getEnum().size(); i++) {
                assertNoCanonicalOverlayControls(schema.getEnum().get(i), appendPath(path, "enum/" + i), false);
            }
        }
    }

    private static JsonNode readResource(String resource) {
        try (InputStream inputStream = BlueConformanceSuiteRunner.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (inputStream == null) {
                throw new IllegalArgumentException("Missing fixture resource: " + resource);
            }
            return UncheckedObjectMapper.YAML_MAPPER.readTree(inputStream);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to read fixture resource: " + resource, e);
        }
    }

    private static String readTextResource(String resource) {
        String bundledResource = resource.startsWith("specifications/")
                ? resource.substring("specifications/".length())
                : resource;
        try (InputStream inputStream = BlueConformanceSuiteRunner.class.getClassLoader()
                .getResourceAsStream(bundledResource)) {
            if (inputStream == null) {
                throw new IllegalArgumentException("Missing publishable resource: " + resource);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to read publishable resource: " + resource, e);
        }
    }

    private static Node readNode(JsonNode node) {
        return UncheckedObjectMapper.YAML_MAPPER.treeToValue(node, Node.class);
    }

    private static JsonNode requirePresent(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw new IllegalArgumentException("Fixture is missing required field: " + field);
        }
        return value;
    }

    private static JsonNode requireNonNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Fixture is missing required field: " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but was " + actual);
        }
    }

    private static void assertNotEquals(Object unexpected, Object actual) {
        if (unexpected == null ? actual == null : unexpected.equals(actual)) {
            throw new AssertionError("Did not expect " + actual);
        }
    }

    private static String appendPath(String path, String segment) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "/" + segment;
        }
        return path + "/" + segment;
    }

    private static final class FixtureNodeProvider
            implements NodeProvider, CyclicAwareNodeProvider {
        private final Map<String, Node> ordinaryNodesByBlueId;
        private final List<NodeProvider> cyclicSetProviders;

        private FixtureNodeProvider(Map<String, Node> ordinaryNodesByBlueId,
                                    List<NodeProvider> cyclicSetProviders) {
            this.ordinaryNodesByBlueId = ordinaryNodesByBlueId;
            this.cyclicSetProviders = cyclicSetProviders;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            Node ordinary = ordinaryNodesByBlueId.get(blueId);
            if (ordinary != null) {
                return Collections.singletonList(ordinary.clone());
            }
            for (NodeProvider provider : cyclicSetProviders) {
                List<Node> nodes = provider.fetchByBlueId(blueId);
                if (nodes != null) {
                    return nodes;
                }
            }
            return null;
        }

        @Override
        public boolean hasVerifiedContentForBlueId(String blueId) {
            if (ordinaryNodesByBlueId.containsKey(blueId)) {
                return false;
            }
            for (NodeProvider provider : cyclicSetProviders) {
                if (provider instanceof CyclicAwareNodeProvider
                        && ((CyclicAwareNodeProvider) provider)
                        .hasVerifiedContentForBlueId(blueId)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class SingletonCyclicSetProvider
            implements NodeProvider, CyclicAwareNodeProvider {
        private final String memberBlueId;
        private final Node content;

        private SingletonCyclicSetProvider(Node document, String memberBlueId) {
            this.memberBlueId = memberBlueId;
            String masterBlueId = memberBlueId.substring(0, memberBlueId.indexOf('#'));
            JsonNode resolvedContent = NodeContentHandler.resolveThisReferences(
                    UncheckedObjectMapper.JSON_MAPPER.valueToTree(document), masterBlueId, true);
            this.content = UncheckedObjectMapper.JSON_MAPPER.treeToValue(resolvedContent, Node.class);
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            return memberBlueId.equals(blueId)
                    ? Collections.singletonList(content.clone().blueId(memberBlueId))
                    : null;
        }

        @Override
        public boolean hasVerifiedContentForBlueId(String blueId) {
            return memberBlueId.equals(blueId);
        }
    }

    private static final class FixtureEntry {
        private final String id;
        private final String category;
        private final String path;

        private FixtureEntry(String id, String category, String path) {
            this.id = id;
            this.category = category;
            this.path = path;
        }
    }
}
