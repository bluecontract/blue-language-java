package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueLanguageRuntime;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
import blue.language.api.WeightedLruCache;
import blue.language.provider.NodeProvider;

import blue.language.processor.EffectiveContractSnapshotConstants;
import blue.language.processor.GasScheduleConstants;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.registry.RuntimeTypeKey;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.registry.RegistryManifestConstants;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.utils.CanonicalIdentityConstants;
import blue.language.utils.Properties;
import blue.language.utils.SchemaPropertyConstants;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the source conventions that keep the final kernel readable.
 */
final class SourceStyleConventionsTest {

    private static final Pattern JUNIT_ANNOTATION = Pattern.compile(
            "@(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");
    private static final Pattern BEHAVIOR_NAME = Pattern.compile(
            "should[A-Z][A-Za-z0-9]*");
    private static final Pattern ASSERTION_CALL = Pattern.compile(
            "\\b(?:assert[A-Z][A-Za-z0-9]*|(?<!\\.)fail)\\s*\\(");
    private static final List<String> GIVEN_WHEN_THEN = Collections.unmodifiableList(
            Arrays.asList("// given", "// when", "// then"));
    private static final List<String> GIVEN_WHEN_THEN_FILLER =
            Collections.unmodifiableList(Arrays.asList(
                    "The input is supplied directly to the operation below.",
                    "Inputs and expected outcomes are declared inline below.",
                    "Each inline operation is evaluated by its assertion."
            ));
    private static final Set<String> BLUE_WIRE_LITERALS =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    Properties.OBJECT_BLUE_ID,
                    Properties.OBJECT_ITEM_TYPE,
                    Properties.OBJECT_KEY_TYPE,
                    Properties.OBJECT_VALUE_TYPE,
                    Properties.OBJECT_MERGE_POLICY,
                    Properties.OBJECT_CONTRACTS,
                    Properties.OBJECT_SCHEMA,
                    Properties.OBJECT_ITEMS,
                    Properties.OBJECT_VALUE,
                    Properties.OBJECT_TYPE,
                    Properties.OBJECT_BLUE,
                    Properties.BLUE_DIRECTIVE_IMPORTS
            )));
    private static final Set<String> CANONICAL_IDENTITY_LITERALS =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    CanonicalIdentityConstants.LIST_SEED_KEY,
                    CanonicalIdentityConstants.LIST_SEED_VALUE,
                    CanonicalIdentityConstants.LIST_CONS_KEY,
                    CanonicalIdentityConstants.LIST_CONS_ELEMENT_KEY,
                    CanonicalIdentityConstants.LIST_CONS_PREVIOUS_KEY
            )));
    private static final Set<String> SCHEMA_WIRE_LITERALS =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    SchemaPropertyConstants.KEY_REQUIRED,
                    SchemaPropertyConstants.KEY_MIN_LENGTH,
                    SchemaPropertyConstants.KEY_MAX_LENGTH,
                    SchemaPropertyConstants.KEY_MINIMUM,
                    SchemaPropertyConstants.KEY_MAXIMUM,
                    SchemaPropertyConstants.KEY_EXCLUSIVE_MINIMUM,
                    SchemaPropertyConstants.KEY_EXCLUSIVE_MAXIMUM,
                    SchemaPropertyConstants.KEY_MULTIPLE_OF,
                    SchemaPropertyConstants.KEY_MIN_ITEMS,
                    SchemaPropertyConstants.KEY_MAX_ITEMS,
                    SchemaPropertyConstants.KEY_UNIQUE_ITEMS,
                    SchemaPropertyConstants.KEY_MIN_FIELDS,
                    SchemaPropertyConstants.KEY_MAX_FIELDS,
                    SchemaPropertyConstants.KEY_ENUM
            )));
    private static final Set<String> PROCESSOR_MAGIC_LITERALS =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    ProcessorContractConstants.KEY_CONTRACTS,
                    ProcessorContractConstants.KEY_EMBEDDED,
                    ProcessorContractConstants.KEY_INITIALIZED,
                    ProcessorContractConstants.KEY_TERMINATED,
                    ProcessorContractConstants.KEY_CHECKPOINT,
                    ProcessorContractConstants.KEY_PATHS,
                    ProcessorContractConstants.KEY_GENERALIZATION,
                    ProcessorContractConstants.KEY_SUBSCRIPTION_KEY,
                    ProcessorContractConstants.KEY_SUBSCRIPTION_KEYS,
                    ProcessorPointerConstants.RELATIVE_CONTRACTS,
                    ProcessorPointerConstants.RELATIVE_TYPE,
                    ProcessorPointerConstants.RELATIVE_VALUE,
                    ProcessorPointerConstants.RELATIVE_INITIALIZED,
                    ProcessorPointerConstants.RELATIVE_TERMINATED,
                    ProcessorPointerConstants.RELATIVE_EMBEDDED,
                    ProcessorPointerConstants.RELATIVE_EMBEDDED_PATHS,
                    ProcessorPointerConstants.RELATIVE_CHECKPOINT,
                    ProcessorPointerConstants.RELATIVE_GENERALIZATION,
                    ProcessorPointerConstants.PROCESS_EVENT,
                    ProcessorPointerConstants
                            .PROCESS_EVENT_SUBSCRIPTION_KEY,
                    EffectiveContractSnapshotConstants
                            .Role.PROCESSOR_CHANNEL,
                    EffectiveContractSnapshotConstants
                            .Role.EXTERNAL_CHANNEL,
                    EffectiveContractSnapshotConstants.Role.HANDLER,
                    EffectiveContractSnapshotConstants
                            .Role.PROCESS_EMBEDDED,
                    EffectiveContractSnapshotConstants.Role.MARKER,
                    EffectiveContractSnapshotConstants
                            .Role.EXECUTABLE_EXTENSION,
                    GasScheduleConstants.PortableLimit
                            .EFFECTIVE_CONTRACTS_PER_SCOPE,
                    GasScheduleConstants.PortableLimit
                            .EXTERNAL_CHANNELS_PER_SCOPE,
                    GasScheduleConstants.PortableLimit
                            .HANDLERS_PER_DELIVERY,
                    GasScheduleConstants.PortableLimit
                            .SUBSCRIPTION_KEYS_PER_CHANNEL,
                    GasScheduleConstants.PortableLimit
                            .PRESELECTED_EXTERNAL_OCCURRENCES,
                    GasScheduleConstants.PortableLimit
                            .PARTICIPATING_SCOPES_PER_EVENT,
                    GasScheduleConstants.PortableLimit
                            .PROCESS_EMBEDDED_PATHS_PER_SCOPE,
                    GasScheduleConstants.PortableLimit.EMBEDDED_DEPTH,
                    GasScheduleConstants.PortableLimit
                            .RUNTIME_POINTER_SEGMENTS,
                    GasScheduleConstants.PortableLimit
                            .RUNTIME_POINTER_UTF8_BYTES,
                    GasScheduleConstants.PortableLimit
                            .CONTRACT_KEY_CODE_POINTS,
                    GasScheduleConstants.PortableLimit
                            .CONTRACT_KEY_UTF8_BYTES,
                    GasScheduleConstants.PortableLimit
                            .DIRECT_OBJECT_ENTRIES,
                    GasScheduleConstants.PortableLimit.DIRECT_LIST_ITEMS,
                    GasScheduleConstants.PortableLimit
                            .DIRECT_CANONICAL_IDENTITY_INPUT_BYTES,
                    GasScheduleConstants.PortableLimit.TYPE_CHAIN_EDGES,
                    GasScheduleConstants.PortableLimit
                            .PATCHES_PER_CONTRACT_RESULT,
                    GasScheduleConstants.PortableLimit
                            .EVENTS_PER_CONTRACT_RESULT,
                    GasScheduleConstants.PortableLimit
                            .INTERNAL_EVENT_OCCURRENCES,
                    GasScheduleConstants.PortableLimit
                            .ROOT_EVENTS_RETURNED,
                    GasScheduleConstants.PortableLimit
                            .DOCUMENT_UPDATE_CASCADE_DEPTH,
                    GasScheduleConstants.PortableLimit
                            .RUNTIME_CHILD_LEDGER_COUNTER_KINDS,
                    GasScheduleConstants.PortableLimit
                            .DIRECT_OBJECT_KEY_CODE_POINTS,
                    GasScheduleConstants.PortableLimit
                            .DIRECT_INLINE_IDENTITY_TEXT_CODE_POINTS,
                    GasScheduleConstants.FormulaParameter
                            .TEXT_BLOCK_CODE_POINTS,
                    GasScheduleConstants.FormulaParameter
                            .INTEGER_MINIMUM_LIMBS,
                    GasScheduleConstants.FormulaParameter
                            .INTEGER_RADIX_BITS,
                    GasScheduleConstants.FormulaParameter
                            .SORTING_INITIAL_RUN_WIDTH,
                    GasScheduleConstants.FormulaParameter
                            .IDENTITY_HASH_DOMAIN_BYTES,
                    GasScheduleConstants.FormulaParameter
                            .IDENTITY_HASH_BLOCK_BYTES,
                    ProcessorContractConstants
                            .GENERALIZATION_MODE_NEAREST_VALID_ANCESTOR,
                    ProcessorContractConstants
                            .GENERALIZATION_MODE_REJECT
            )));
    private static final Set<String> REGISTRY_MANIFEST_LITERALS =
            Collections.unmodifiableSet(
                    new HashSet<String>(Arrays.asList(
                            RegistryManifestConstants
                                    .FIELD_REGISTRY_KIND,
                            RegistryManifestConstants
                                    .FIELD_SPECIFICATION_VERSION,
                            RegistryManifestConstants
                                    .FIELD_LANGUAGE_VERSION,
                            RegistryManifestConstants
                                    .FIELD_PACKAGE_IDENTITY,
                            RegistryManifestConstants
                                    .FIELD_FIXTURE_PACKAGE_IDENTITY,
                            RegistryManifestConstants
                                    .FIELD_SEMANTIC_DESCRIPTION_IDENTITY_BEARING,
                            RegistryManifestConstants
                                    .FIELD_FIXTURE_ONLY,
                            RegistryManifestConstants
                                    .REGISTRY_LANGUAGE_CORE,
                            RegistryManifestConstants
                                    .KIND_CORE_TYPE,
                            RegistryManifestConstants
                                    .REGISTRY_CONTRACTS_RUNTIME,
                            RegistryManifestConstants
                                    .KIND_RUNTIME_TYPE
                    )));
    private static final Set<String> PROCESSOR_LITERAL_OWNER_FILES =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    "ProcessorContractConstants.java",
                    "ProcessorPointerConstants.java",
                    "ProcessingTraceConstants.java",
                    "EffectiveContractSnapshotConstants.java",
                    "GasScheduleConstants.java"
            )));
    private static final Set<String> PUBLISHED_RUNTIME_IDENTITIES =
            publishedRuntimeIdentities();
    private static final Set<String> PUBLISHED_CORE_BLUE_IDS =
            Collections.unmodifiableSet(
                    new HashSet<>(Properties.CORE_TYPE_BLUE_IDS));
    private static final Set<String> PROCESSOR_TEST_TYPE_BLUE_IDS =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    ProcessorTestTypeBlueIds.APPLY_BATCH_PATCH,
                    ProcessorTestTypeBlueIds.ASSERT_DOCUMENT_UPDATE,
                    ProcessorTestTypeBlueIds.CUT_OFF_PROBE,
                    ProcessorTestTypeBlueIds.EMIT_EVENTS,
                    ProcessorTestTypeBlueIds.INCREMENT_PROPERTY,
                    ProcessorTestTypeBlueIds.MUTATE_EMBEDDED_PATHS,
                    ProcessorTestTypeBlueIds.MUTATE_EVENT,
                    ProcessorTestTypeBlueIds.PROCESSING_FAILURE_MARKER,
                    ProcessorTestTypeBlueIds.RECORD_DOCUMENT_UPDATE,
                    ProcessorTestTypeBlueIds.REMOVE_IF_PRESENT,
                    ProcessorTestTypeBlueIds.REMOVE_PROPERTY,
                    ProcessorTestTypeBlueIds.SET_PROPERTY,
                    ProcessorTestTypeBlueIds.SET_PROPERTY_ON_EVENT,
                    ProcessorTestTypeBlueIds.TERMINATE_SCOPE,
                    ProcessorTestTypeBlueIds.TEST_EVENT,
                    ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL,
                    ProcessorTestTypeBlueIds.LEGACY_BLUE_ID_TYPE
            )));

    @Test
    void shouldNameEveryJunitTestAsReadableBehavior() throws IOException {
        // given
        List<TestMethod> methods = allTestMethods();

        // when
        List<String> violations = methods.stream()
                .filter(method -> !BEHAVIOR_NAME.matcher(method.name).matches())
                .map(method -> method.location
                        + ": expected should* behavior name, found "
                        + method.name)
                .collect(Collectors.toList());

        // then
        assertTrue(violations.isEmpty(), joinViolations(violations));
    }

    @Test
    void shouldGiveEveryJunitTestOneOrderedGivenWhenThenFlow()
            throws IOException {
        // given
        List<TestMethod> methods = allTestMethods();

        // when
        List<String> violations = new ArrayList<>();
        for (TestMethod method : methods) {
            int previous = -1;
            for (String marker : GIVEN_WHEN_THEN) {
                int count = countOccurrences(method.body, marker);
                int position = method.body.indexOf(marker);
                if (count != 1 || position <= previous) {
                    violations.add(
                            method.location + ": expected one ordered "
                                    + marker + " marker, found " + count);
                }
                previous = position;
            }
            for (String filler : GIVEN_WHEN_THEN_FILLER) {
                if (method.body.contains(filler)) {
                    violations.add(
                            method.location + ": replace filler prose with "
                                    + "concrete Given/When/Then code");
                }
            }
        }

        // then
        assertTrue(violations.isEmpty(), joinViolations(violations));
    }

    @Test
    void shouldKeepTestAssertionsInsideThenSections()
            throws IOException {
        // given
        List<TestMethod> methods = allTestMethods();

        // when
        List<String> violations = new ArrayList<>();
        for (TestMethod method : methods) {
            int then = method.body.indexOf(GIVEN_WHEN_THEN.get(2));
            String precedingCode = then >= 0
                    ? method.body.substring(0, then)
                    : method.body;
            if (ASSERTION_CALL.matcher(
                    codeOnly(precedingCode)).find()) {
                violations.add(method.location
                        + ": assertion appears before "
                        + GIVEN_WHEN_THEN.get(2));
            }
        }

        // then
        assertTrue(violations.isEmpty(), joinViolations(violations));
    }

    @Test
    void shouldDocumentEveryProductionSourceFile() throws IOException {
        // given
        List<Path> productionSources = javaSources(
                Paths.get("src/main/java"));

        // when
        List<String> violations = new ArrayList<>();
        for (Path source : productionSources) {
            String content = read(source);
            if (!content.contains("/**")) {
                violations.add(source
                        + ": missing type or API documentation");
            }
        }

        // then
        assertTrue(violations.isEmpty(), joinViolations(violations));
    }

    @Test
    void shouldCentralizeBlueWireVocabulary() throws IOException {
        // given
        List<Path> productionSources = javaSources(
                Paths.get("src/main/java"));

        // when
        List<String> violations = new ArrayList<>();
        for (Path source : productionSources) {
            String fileName = source.getFileName().toString();
            if ("BlueLanguageConstants.java".equals(fileName)
                    || "Properties.java".equals(fileName)) {
                continue;
            }
            Set<String> stringLiterals =
                    stringLiterals(read(source));
            for (String wireLiteral : BLUE_WIRE_LITERALS) {
                if (stringLiterals.contains(wireLiteral)) {
                    violations.add(source + ": Blue wire literal \""
                            + wireLiteral
                            + "\" must use BlueLanguageConstants");
                }
            }
        }

        // then
        assertTrue(violations.isEmpty(), joinViolations(violations));
    }

    @Test
    void shouldCentralizeIdentityAndSchemaVocabulary()
            throws IOException {
        // given
        List<Path> productionSources = javaSources(
                Paths.get("src/main/java"));

        // when
        List<String> violations = new ArrayList<>();
        for (Path source : productionSources) {
            String fileName = source.getFileName().toString();
            String content = read(source);
            Set<String> literals = stringLiterals(content);
            if (!"CanonicalIdentityConstants.java".equals(fileName)) {
                rejectLiterals(
                        source,
                        literals,
                        CANONICAL_IDENTITY_LITERALS,
                        "canonical identity",
                        violations);
            }
            if (!"SchemaPropertyConstants.java".equals(fileName)) {
                Set<String> forbidden =
                        new HashSet<>(SCHEMA_WIRE_LITERALS);
                if ("BlueLanguageErrorClassifier.java".equals(fileName)) {
                    forbidden.remove(
                            SchemaPropertyConstants.KEY_MINIMUM);
                    forbidden.remove(
                            SchemaPropertyConstants.KEY_MAXIMUM);
                }
                if ("ContractsGasSchedule.java".equals(fileName)) {
                    forbidden.remove(
                            SchemaPropertyConstants.KEY_MULTIPLE_OF);
                }
                rejectLiterals(
                        source,
                        literals,
                        forbidden,
                        "schema wire",
                        violations);
            }
            if (!"BlueNumbers.java".equals(fileName)
                    && (content.contains("9007199254740991")
                    || content.contains("9_007_199_254_740_991"))) {
                violations.add(source
                        + ": interoperable integer boundary must use "
                        + "BlueNumbers");
            }
        }

        // then
        assertTrue(violations.isEmpty(), joinViolations(violations));
    }

    @Test
    void shouldCentralizeProcessorWireVocabulary() throws IOException {
        // given
        List<Path> processorSources = javaSources(
                Paths.get("src/main/java/blue/language/processor"));

        // when
        List<String> violations = new ArrayList<>();
        for (Path source : processorSources) {
            String content = read(source);
            if (content.contains("details.put(\"")
                    || content.contains(".detail(\"")
                    || content.contains(".portableLimit(\"")
                    || content.contains(".formulaParameter(\"")
                    || content.contains(".weight(\"")
                    || content.contains(".charge(\"")) {
                violations.add(source
                        + ": processor wire value must use a named constant");
            }
            if (PROCESSOR_LITERAL_OWNER_FILES.contains(
                    source.getFileName().toString())) {
                continue;
            }
            Set<String> stringLiterals = stringLiterals(content);
            for (String magicLiteral : PROCESSOR_MAGIC_LITERALS) {
                if (stringLiterals.contains(magicLiteral)) {
                    violations.add(source + ": reserved literal \""
                            + magicLiteral
                            + "\" must use its named processor constant");
                }
            }
        }

        // then
        assertTrue(violations.isEmpty(), joinViolations(violations));
    }

    @Test
    void shouldCentralizeRegistryManifestVocabulary()
            throws IOException {
        // given
        List<Path> registrySources = new ArrayList<>();
        registrySources.addAll(javaSources(
                Paths.get(
                        "src/main/java/blue/language/registry")));
        registrySources.addAll(javaSources(
                Paths.get(
                        "src/main/java/blue/language/processor/registry")));

        // when
        List<String> violations = new ArrayList<>();
        for (Path source : registrySources) {
            if ("RegistryManifestConstants.java".equals(
                    source.getFileName().toString())) {
                continue;
            }
            rejectLiterals(
                    source,
                    stringLiterals(read(source)),
                    REGISTRY_MANIFEST_LITERALS,
                    "registry manifest",
                    violations);
        }

        // then
        assertTrue(violations.isEmpty(), joinViolations(violations));
    }

    @Test
    void shouldCentralizeContractsFixtureVocabulary()
            throws IOException {
        // given
        Path vocabularyOwner = Paths.get(
                "src/main/java/blue/language/conformance/contracts/"
                        + "ContractsFixtureConstants.java");
        Set<String> vocabulary =
                stringLiterals(read(vocabularyOwner));
        List<Path> coreConsumers = Arrays.asList(
                Paths.get(
                        "src/main/java/blue/language/conformance/contracts/"
                                + "ClosedContractsFixtureValidator.java"),
                Paths.get(
                        "src/main/java/blue/language/conformance/contracts/"
                                + "ContractsFixtureHarness.java"),
                Paths.get(
                        "src/main/java/blue/language/conformance/contracts/"
                                + "ContractsGasSchedule.java"),
                Paths.get(
                        "src/main/java/blue/language/conformance/contracts/"
                                + "ContractsAssertionEvaluator.java"),
                Paths.get(
                        "src/main/java/blue/language/conformance/contracts/"
                                + "ContractsProjectionCatalog.java"),
                Paths.get(
                        "src/main/java/blue/language/conformance/contracts/"
                                + "ContractsConformanceProjection.java"),
                Paths.get(
                        "src/main/java/blue/language/conformance/contracts/"
                                + "ScriptedContractsRuntime.java"));

        // when
        List<String> violations = new ArrayList<>();
        for (Path source : coreConsumers) {
            rejectLiterals(
                    source,
                    stringLiterals(read(source)),
                    vocabulary,
                    "Contracts fixture",
                    violations);
        }

        // then
        assertTrue(violations.isEmpty(), joinViolations(violations));
    }

    @Test
    void shouldCentralizePublishedAndSyntheticTypeBlueIds()
            throws IOException {
        // given
        List<Path> sources = new ArrayList<>();
        sources.addAll(javaSources(Paths.get("src/main/java")));
        sources.addAll(javaSources(Paths.get("src/test/java")));
        sources.addAll(javaSources(Paths.get("src/jmh/java")));

        // when
        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            String fileName = source.getFileName().toString();
            String content = read(source);
            if (!"BlueLanguageConstants.java".equals(fileName)
                    && !"Properties.java".equals(fileName)) {
                rejectContainedLiterals(
                        source,
                        content,
                        PUBLISHED_CORE_BLUE_IDS,
                        "published core BlueId",
                        violations);
            }
            if (!"RuntimeBlueIds.java".equals(fileName)) {
                rejectContainedLiterals(
                        source,
                        content,
                        PUBLISHED_RUNTIME_IDENTITIES,
                        "published runtime identity",
                        violations);
            }
            if (!"ProcessorTestTypeBlueIds.java".equals(fileName)
                    && !"RuntimeBlueIds.java".equals(fileName)) {
                rejectContainedLiterals(
                        source,
                        content,
                        PROCESSOR_TEST_TYPE_BLUE_IDS,
                        "synthetic processor test BlueId",
                        violations);
            }
        }

        // then
        assertTrue(violations.isEmpty(), joinViolations(violations));
    }

    private static Set<String> publishedRuntimeIdentities() {
        Set<String> blueIds = new HashSet<>();
        blueIds.add(RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY);
        for (RuntimeTypeKey key : RuntimeTypeKey.values()) {
            blueIds.add(RuntimeBlueIds.blueId(key));
        }
        return Collections.unmodifiableSet(blueIds);
    }

    private static List<TestMethod> allTestMethods() throws IOException {
        List<TestMethod> result = new ArrayList<>();
        for (Path source : javaSources(Paths.get("src/test/java"))) {
            String content = read(source);
            Matcher annotation = JUNIT_ANNOTATION.matcher(content);
            while (annotation.find()) {
                result.add(readTestMethod(
                        source,
                        content,
                        annotation.end()));
            }
        }
        return result;
    }

    private static void rejectLiterals(
            Path source,
            Set<String> actual,
            Set<String> forbidden,
            String vocabulary,
            List<String> violations) {
        for (String literal : forbidden) {
            if (actual.contains(literal)) {
                violations.add(source + ": " + vocabulary
                        + " literal \"" + literal
                        + "\" must use its named constant");
            }
        }
    }

    private static void rejectContainedLiterals(
            Path source,
            String content,
            Set<String> forbidden,
            String vocabulary,
            List<String> violations) {
        for (String literal : forbidden) {
            if (content.contains(literal)) {
                violations.add(source + ": " + vocabulary
                        + " must use its named constant");
            }
        }
    }

    private static TestMethod readTestMethod(Path source,
                                             String content,
                                             int annotationEnd) {
        int cursor = skipAnnotationArguments(
                content,
                annotationEnd);
        while (true) {
            cursor = skipTrivia(content, cursor);
            if (cursor >= content.length()
                    || content.charAt(cursor) != '@') {
                break;
            }
            cursor = skipAnnotation(content, cursor);
        }
        int parameters = nextCodeCharacter(content, cursor, '(');
        if (parameters < 0) {
            throw sourceFailure(
                    source,
                    content,
                    cursor,
                    "cannot find test method parameters");
        }
        String name = precedingIdentifier(content, parameters);
        int parametersEnd = matchingDelimiter(
                content,
                parameters,
                '(',
                ')');
        int bodyStart = nextCodeCharacter(
                content,
                parametersEnd + 1,
                '{');
        if (bodyStart < 0) {
            throw sourceFailure(
                    source,
                    content,
                    parametersEnd,
                    "cannot find test method body");
        }
        int bodyEnd = matchingDelimiter(
                content,
                bodyStart,
                '{',
                '}');
        int line = 1 + countOccurrences(
                content.substring(0, bodyStart),
                "\n");
        return new TestMethod(
                name,
                content.substring(bodyStart + 1, bodyEnd),
                source + ":" + line);
    }

    private static int skipAnnotationArguments(String content,
                                               int cursor) {
        cursor = skipTrivia(content, cursor);
        if (cursor < content.length()
                && content.charAt(cursor) == '(') {
            return matchingDelimiter(content, cursor, '(', ')') + 1;
        }
        return cursor;
    }

    private static int skipAnnotation(String content, int cursor) {
        cursor++;
        while (cursor < content.length()
                && (Character.isJavaIdentifierPart(
                content.charAt(cursor))
                || content.charAt(cursor) == '.')) {
            cursor++;
        }
        return skipAnnotationArguments(content, cursor);
    }

    private static int skipTrivia(String content, int cursor) {
        boolean advanced;
        do {
            advanced = false;
            while (cursor < content.length()
                    && Character.isWhitespace(
                    content.charAt(cursor))) {
                cursor++;
                advanced = true;
            }
            if (content.startsWith("//", cursor)) {
                int newline = content.indexOf('\n', cursor + 2);
                cursor = newline >= 0 ? newline + 1 : content.length();
                advanced = true;
            } else if (content.startsWith("/*", cursor)) {
                int end = content.indexOf("*/", cursor + 2);
                cursor = end >= 0 ? end + 2 : content.length();
                advanced = true;
            }
        } while (advanced);
        return cursor;
    }

    private static int nextCodeCharacter(String content,
                                         int cursor,
                                         char target) {
        ScanState state = ScanState.CODE;
        for (int index = cursor; index < content.length(); index++) {
            char current = content.charAt(index);
            char next = index + 1 < content.length()
                    ? content.charAt(index + 1)
                    : '\0';
            state = state.advance(current, next);
            if (state == ScanState.CODE && current == target) {
                return index;
            }
            if (state.consumesNext(current, next)) {
                index++;
            }
        }
        return -1;
    }

    private static int matchingDelimiter(String content,
                                         int opening,
                                         char open,
                                         char close) {
        int depth = 0;
        ScanState state = ScanState.CODE;
        for (int index = opening; index < content.length(); index++) {
            char current = content.charAt(index);
            char next = index + 1 < content.length()
                    ? content.charAt(index + 1)
                    : '\0';
            state = state.advance(current, next);
            if (state == ScanState.CODE) {
                if (current == open) {
                    depth++;
                } else if (current == close && --depth == 0) {
                    return index;
                }
            }
            if (state.consumesNext(current, next)) {
                index++;
            }
        }
        throw new IllegalArgumentException(
                "Unbalanced delimiter " + open);
    }

    private static String precedingIdentifier(String content,
                                              int before) {
        int end = before;
        while (end > 0
                && Character.isWhitespace(
                content.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0
                && Character.isJavaIdentifierPart(
                content.charAt(start - 1))) {
            start--;
        }
        return content.substring(start, end);
    }

    private static Set<String> stringLiterals(String content) {
        Set<String> result = new HashSet<>();
        ScanState state = ScanState.CODE;
        StringBuilder literal = null;
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            char next = index + 1 < content.length()
                    ? content.charAt(index + 1)
                    : '\0';
            ScanState previous = state;
            state = state.advance(current, next);
            if (previous == ScanState.CODE
                    && state == ScanState.STRING) {
                literal = new StringBuilder();
            } else if (previous == ScanState.STRING
                    && state == ScanState.CODE) {
                result.add(literal.toString());
                literal = null;
            } else if (state == ScanState.STRING
                    && literal != null) {
                if (current == '\\') {
                    literal.append(current).append(next);
                } else {
                    literal.append(current);
                }
            }
            if (state.consumesNext(current, next)) {
                index++;
            }
        }
        return result;
    }

    private static String codeOnly(String content) {
        StringBuilder result = new StringBuilder(
                content.length());
        ScanState state = ScanState.CODE;
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            char next = index + 1 < content.length()
                    ? content.charAt(index + 1)
                    : '\0';
            ScanState previous = state;
            state = state.advance(current, next);
            result.append(previous == ScanState.CODE
                    && state == ScanState.CODE
                    ? current
                    : ' ');
            if (state.consumesNext(current, next)) {
                result.append(' ');
                index++;
            }
        }
        return result.toString();
    }

    private static List<Path> javaSources(Path root)
            throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static String read(Path path) throws IOException {
        return new String(
                Files.readAllBytes(path),
                StandardCharsets.UTF_8);
    }

    private static int countOccurrences(String value,
                                        String fragment) {
        int count = 0;
        int cursor = 0;
        while ((cursor = value.indexOf(fragment, cursor)) >= 0) {
            count++;
            cursor += fragment.length();
        }
        return count;
    }

    private static IllegalArgumentException sourceFailure(
            Path source,
            String content,
            int offset,
            String message) {
        int line = 1 + countOccurrences(
                content.substring(
                        0,
                        Math.min(offset, content.length())),
                "\n");
        return new IllegalArgumentException(
                source + ":" + line + ": " + message);
    }

    private static String joinViolations(List<String> violations) {
        return violations.isEmpty()
                ? ""
                : "\n" + String.join("\n", violations);
    }

    private static final class TestMethod {
        private final String name;
        private final String body;
        private final String location;

        private TestMethod(String name,
                           String body,
                           String location) {
            this.name = name;
            this.body = body;
            this.location = location;
        }
    }

    private enum ScanState {
        CODE,
        STRING,
        CHARACTER,
        LINE_COMMENT,
        BLOCK_COMMENT;

        private ScanState advance(char current, char next) {
            switch (this) {
                case CODE:
                    if (current == '"') {
                        return STRING;
                    }
                    if (current == '\'') {
                        return CHARACTER;
                    }
                    if (current == '/' && next == '/') {
                        return LINE_COMMENT;
                    }
                    if (current == '/' && next == '*') {
                        return BLOCK_COMMENT;
                    }
                    return CODE;
                case STRING:
                    if (current == '\\') {
                        return STRING;
                    }
                    return current == '"' ? CODE : STRING;
                case CHARACTER:
                    if (current == '\\') {
                        return CHARACTER;
                    }
                    return current == '\'' ? CODE : CHARACTER;
                case LINE_COMMENT:
                    return current == '\n' ? CODE : LINE_COMMENT;
                case BLOCK_COMMENT:
                    return current == '*' && next == '/'
                            ? CODE
                            : BLOCK_COMMENT;
                default:
                    throw new IllegalStateException(
                            "Unhandled scan state " + this);
            }
        }

        private boolean consumesNext(char current, char next) {
            return (this == LINE_COMMENT
                    && current == '/'
                    && next == '/')
                    || (this == BLOCK_COMMENT
                    && current == '/'
                    && next == '*')
                    || (this == CODE
                    && current == '*'
                    && next == '/')
                    || ((this == STRING || this == CHARACTER)
                    && current == '\\');
        }
    }
}
