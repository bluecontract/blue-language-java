package blue.language;

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

import blue.language.model.Node;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class BlueViewPathTest {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    @Test
    void shouldSelectRootForEmptyStringAndEmptyKeyMemberForSlash() throws Exception {
        // given
        Node root = YAML_MAPPER.readValue(
                "\"\": empty-key\n" +
                "regular: value", Node.class);

        // when
        Node selectedRoot = BlueViewPath.select(root, "");
        Node emptyKey = BlueViewPath.select(root, "/");
        Node regular = BlueViewPath.select(root, "/regular");

        // then
        assertSame(root, selectedRoot);
        assertEquals("empty-key", emptyKey.getValue());
        assertEquals("value", regular.getValue());
    }

    @Test
    void shouldSelectListPayloadItemsForItemsSegmentInAbstractNodeModel() throws Exception {
        // given
        Node root = YAML_MAPPER.readValue(
                "regular:\n" +
                "  items:\n" +
                "    - first\n" +
                "    - second", Node.class);

        // when
        Node first = BlueViewPath.select(
                root, "/regular/items/0");
        Node second = BlueViewPath.select(
                root, "/regular/items/1");

        // then
        assertEquals("first", first.getValue());
        assertEquals("second", second.getValue());
    }

    @Test
    void shouldEscapeTildeAndSlashPerRfc6901() throws Exception {
        // given
        Node root = YAML_MAPPER.readValue(
                "\"a/b\":\n" +
                "  \"c~d\": escaped", Node.class);

        // when
        Node selected = BlueViewPath.select(
                root, "/a~1b/c~0d");

        // then
        assertEquals("escaped", selected.getValue());
    }

    @Test
    void shouldRejectBadEscapes() {
        // given
        String badEscape = "/bad~2escape";
        String truncatedEscape = "/bad~";

        // when
        Throwable badEscapeFailure =
                captureFailure(() -> BlueViewPath.split(badEscape));
        Throwable truncatedEscapeFailure =
                captureFailure(() -> BlueViewPath.split(truncatedEscape));

        // then
        assertEquals(IllegalArgumentException.class,
                badEscapeFailure.getClass());
        assertEquals(IllegalArgumentException.class,
                truncatedEscapeFailure.getClass());
    }

    @Test
    void shouldRequireCanonicalAsciiDecimalsForArrayIndexes() throws Exception {
        // given
        Node root = YAML_MAPPER.readValue(
                "array:\n" +
                "  items:\n" +
                "    - first", Node.class);

        // when
        Node first = BlueViewPath.select(
                root, "/array/items/0");
        Throwable leadingZeroFailure = captureFailure(
                () -> BlueViewPath.select(
                        root, "/array/items/00"));
        Throwable nonAsciiFailure = captureFailure(
                () -> BlueViewPath.select(
                        root, "/array/items/\u0660"));

        // then
        assertEquals("first", first.getValue());
        assertEquals(IllegalArgumentException.class,
                leadingZeroFailure.getClass());
        assertEquals(IllegalArgumentException.class,
                nonAsciiFailure.getClass());
    }

    @Test
    void shouldExcludeAbsentMetadataValueAndReferenceWrapperBlueIdFromSemanticChildren() {
        // given
        Node plain = new Node();
        Node reference = new Node().blueId(
                "5nWrS5wTB22MN7HHhyRUy7zQ83Qbf6QEcUY4soFir2Sq");

        // when
        Node name = BlueViewPath.select(plain, "/name");
        Node description = BlueViewPath.select(
                plain, "/description");
        Node value = BlueViewPath.select(plain, "/value");
        Node items = BlueViewPath.select(plain, "/items");
        Node referenceBlueId = BlueViewPath.select(
                reference, "/blueId");

        // then
        assertNull(name);
        assertNull(description);
        assertNull(value);
        assertNull(items);
        assertNull(referenceBlueId);
    }
}
