package blue.language;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import blue.language.dictionary.ExportContext;
import blue.language.dictionary.TypeDictionary;
import blue.language.model.Node;
import blue.language.model.Schema;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

public class DictionaryExportTest {

    @Test
    void shouldExportSupportedDictionaryTypesAsTargetBlueIds() {
        // given
        FakeDictionary dictionary = new FakeDictionary("repo.test", "repo-v1")
                .type("type-current", new Node().name("Known Type"))
                .version("repo-v1", "type-current", "type-current");
        Blue blue = new Blue().registerTypeDictionary(dictionary);

        Node document = new Node()
                .type(new Node().blueId("type-current"))
                .properties("value", new Node().value("hello"));

        // when
        Node exported = blue.exportNode(document, ExportContext.builder()
                .dictionary("repo.test", "repo-v1")
                .build());

        // then
        assertEquals("type-current", exported.getType().getBlueId());
        assertNull(exported.getType().getName());
        assertEquals("type-current", document.getType().getBlueId(), "export must not mutate the input");
    }

    @Test
    void shouldExportHistoricalTypeIdsToRequestedDictionaryVersion() {
        // given
        FakeDictionary dictionary = new FakeDictionary("repo.test", "repo-v0", "repo-v1")
                .type("type-v1", new Node().name("Versioned Type"))
                .historical("type-v0", "type-v1")
                .version("repo-v0", "type-v1", "type-v0")
                .version("repo-v1", "type-v1", "type-v1");
        Blue blue = new Blue().registerTypeDictionary(dictionary);

        Node currentDocument = new Node().type(new Node().blueId("type-v1"));
        Node historicalDocument = new Node().type(new Node().blueId("type-v0"));

        // when
        Node currentAsOld = blue.exportNode(currentDocument, ExportContext.builder()
                .dictionary("repo.test", "repo-v0")
                .build());
        Node historicalAsCurrent = blue.exportNode(historicalDocument, ExportContext.builder()
                .dictionary("repo.test", "repo-v1")
                .build());

        // then
        assertEquals("type-v0", currentAsOld.getType().getBlueId());
        assertEquals("type-v1", historicalAsCurrent.getType().getBlueId());
    }

    @Test
    void shouldInlineUnsupportedDictionaryTypesRecursively() {
        // given
        FakeDictionary dictionary = new FakeDictionary("repo.test", "repo-v1")
                .type("child", new Node()
                        .name("Child")
                        .properties("text", new Node().type(new Node().blueId(TEXT_TYPE_BLUE_ID))))
                .type("parent", new Node()
                        .name("Parent")
                        .properties("child", new Node().type(new Node().blueId("child"))))
                .version("repo-v1", "child", "child")
                .version("repo-v1", "parent", "parent");
        Blue blue = new Blue().registerTypeDictionary(dictionary);

        Node document = new Node().type(new Node().blueId("parent"));
        // when
        Node exported = blue.exportNode(document, ExportContext.empty());
        Node childSchema =
                exported.getType().getProperties().get("child");

        // then
        assertNull(exported.getType().getBlueId());
        assertEquals("Parent", exported.getType().getName());
        assertEquals("Child", childSchema.getType().getName());
        assertNull(childSchema.getType().getBlueId());
        assertEquals(TEXT_TYPE_BLUE_ID, childSchema.getType().getProperties().get("text").getType().getBlueId());
    }

    @Test
    void shouldMixSupportedAndUnsupportedDictionariesInOneDocument() {
        // given
        FakeDictionary supported = new FakeDictionary("repo.supported", "supported-v1")
                .type("supported-type", new Node().name("Supported"))
                .version("supported-v1", "supported-type", "supported-type");
        FakeDictionary unsupported = new FakeDictionary("repo.unsupported", "unsupported-v1")
                .type("unsupported-type", new Node().name("Unsupported"))
                .version("unsupported-v1", "unsupported-type", "unsupported-type");
        Blue blue = new Blue()
                .registerTypeDictionary(supported)
                .registerTypeDictionary(unsupported);

        Node document = new Node()
                .type(new Node().blueId("supported-type"))
                .properties("payload", new Node().type(new Node().blueId("unsupported-type")));

        // when
        Node exported = blue.exportNode(document, ExportContext.builder()
                .dictionary("repo.supported", "supported-v1")
                .build());
        Node payloadType =
                exported.getProperties().get("payload").getType();

        // then
        assertEquals("supported-type", exported.getType().getBlueId());
        assertNull(payloadType.getBlueId());
        assertEquals("Unsupported", payloadType.getName());
    }

    @Test
    void shouldThrowForUnsupportedDictionaryTypeWhenInliningIsDisabled() {
        // given
        FakeDictionary dictionary = new FakeDictionary("repo.test", "repo-v1")
                .type("type-current", new Node().name("Known Type"))
                .version("repo-v1", "type-current", "type-current");
        Blue blue = new Blue().registerTypeDictionary(dictionary);
        Node document = new Node().type(new Node().blueId("type-current"));

        // when
        IllegalArgumentException exception = captureFailure(
                () -> blue.exportNode(document, ExportContext.builder()
                        .inlineUnsupportedTypes(false)
                        .build()));

        // then
        assertEquals(IllegalArgumentException.class,
                exception.getClass());
        assertTrue(exception.getMessage().contains("cannot be represented"));
    }

    @Test
    void shouldThrowForUnknownDictionaryVersionBeforeExporting() {
        // given
        FakeDictionary dictionary = new FakeDictionary("repo.test", "repo-v1")
                .type("type-current", new Node().name("Known Type"))
                .version("repo-v1", "type-current", "type-current");
        Blue blue = new Blue().registerTypeDictionary(dictionary);
        Node document = new Node().type(new Node().blueId("type-current"));

        // when
        IllegalArgumentException exception = captureFailure(
                () -> blue.exportNode(document, ExportContext.builder()
                        .dictionary("repo.test", "repo-missing")
                        .build()));

        // then
        assertEquals(IllegalArgumentException.class,
                exception.getClass());
        assertTrue(exception.getMessage().contains("Unknown dictionary BlueId"));
    }

    @Test
    void shouldRejectInliningCycles() {
        // given
        FakeDictionary dictionary = new FakeDictionary("repo.test", "repo-v1")
                .type("a", new Node().name("A").properties("b", new Node().type(new Node().blueId("b"))))
                .type("b", new Node().name("B").properties("a", new Node().type(new Node().blueId("a"))))
                .version("repo-v1", "a", "a")
                .version("repo-v1", "b", "b");
        Blue blue = new Blue().registerTypeDictionary(dictionary);
        Node document = new Node().type(new Node().blueId("a"));

        // when
        IllegalArgumentException exception = captureFailure(
                () -> blue.exportNode(document, ExportContext.empty()));

        // then
        assertEquals(IllegalArgumentException.class,
                exception.getClass());
        assertTrue(exception.getMessage().contains("Cycle detected"));
    }

    @Test
    void shouldExportSchemaEnumValues() {
        // given
        FakeDictionary dictionary = new FakeDictionary("repo.test", "repo-v1")
                .type("enum-type", new Node().name("Enum Type"))
                .version("repo-v1", "enum-type", "enum-type");
        Blue blue = new Blue().registerTypeDictionary(dictionary);

        Node document = new Node().schema(new Schema().enumValues(Collections.singletonList(
                new Node().type(new Node().blueId("enum-type")).value("one")
        )));

        // when
        Node exported = blue.exportNode(document, ExportContext.empty());
        Node enumType = exported.getSchema().getEnum().get(0).getType();

        // then
        assertEquals("Enum Type", enumType.getName());
        assertNull(enumType.getBlueId());
    }

    @Test
    void shouldUseExportContextForNodeToJsonAndYaml() throws Exception {
        // given
        FakeDictionary dictionary = new FakeDictionary("repo.test", "repo-v1")
                .type("type-current", new Node().name("Known Type"))
                .version("repo-v1", "type-current", "type-current");
        Blue blue = new Blue().registerTypeDictionary(dictionary);
        Node document = new Node().type(new Node().blueId("type-current"));

        // when
        String json = blue.nodeToJson(document, ExportContext.empty());
        JsonNode jsonNode = JSON_MAPPER.readTree(json);
        String yaml = blue.nodeToYaml(document, ExportContext.builder()
                .dictionary("repo.test", "repo-v1")
                .build());

        // then
        assertEquals("Known Type", jsonNode.get("type").get("name").asText());
        assertTrue(yaml.contains("blueId: \"type-current\"") || yaml.contains("blueId: type-current"));
    }

    private static final class FakeDictionary implements TypeDictionary {
        private final String name;
        private final Set<String> dictionaryBlueIds = new LinkedHashSet<>();
        private final Map<String, String> currentByBlueId = new LinkedHashMap<>();
        private final Map<String, Node> definitions = new LinkedHashMap<>();
        private final Map<String, Map<String, String>> targetTypeBlueIdsByDictionaryBlueId = new LinkedHashMap<>();

        private FakeDictionary(String name, String... dictionaryBlueIds) {
            this.name = name;
            Collections.addAll(this.dictionaryBlueIds, dictionaryBlueIds);
        }

        private FakeDictionary type(String currentBlueId, Node definition) {
            currentByBlueId.put(currentBlueId, currentBlueId);
            definitions.put(currentBlueId, definition);
            return this;
        }

        private FakeDictionary historical(String historicalBlueId, String currentBlueId) {
            currentByBlueId.put(historicalBlueId, currentBlueId);
            return this;
        }

        private FakeDictionary version(String dictionaryBlueId, String currentBlueId, String targetBlueId) {
            Map<String, String> targetTypeBlueIds = targetTypeBlueIdsByDictionaryBlueId.get(dictionaryBlueId);
            if (targetTypeBlueIds == null) {
                targetTypeBlueIds = new LinkedHashMap<>();
                targetTypeBlueIdsByDictionaryBlueId.put(dictionaryBlueId, targetTypeBlueIds);
            }
            targetTypeBlueIds.put(currentBlueId, targetBlueId);
            return this;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Set<String> dictionaryBlueIds() {
            return dictionaryBlueIds;
        }

        @Override
        public Optional<String> currentBlueId(String blueId) {
            return Optional.ofNullable(currentByBlueId.get(blueId));
        }

        @Override
        public Optional<String> typeBlueIdFor(String currentBlueId, String dictionaryBlueId) {
            Map<String, String> targetTypeBlueIds = targetTypeBlueIdsByDictionaryBlueId.get(dictionaryBlueId);
            return targetTypeBlueIds == null ? Optional.empty() : Optional.ofNullable(targetTypeBlueIds.get(currentBlueId));
        }

        @Override
        public Optional<Node> definition(String currentBlueId) {
            Node definition = definitions.get(currentBlueId);
            return definition == null ? Optional.empty() : Optional.of(definition.clone());
        }
    }
}
