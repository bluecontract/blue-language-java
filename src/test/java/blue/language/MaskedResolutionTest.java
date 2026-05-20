package blue.language;

import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.limits.PathLimits;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static blue.language.utils.Properties.INTEGER_TYPE_BLUE_ID;
import static blue.language.utils.Properties.LIST_TYPE_BLUE_ID;
import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaskedResolutionTest {

    @Test
    void normalResolutionStillRejectsAuthoredScalarWhereTypeRequiresList() {
        ContractTypes types = contractTypes();
        Blue blue = new Blue(types.provider);

        Node document = blue.yamlToNode(
                "contracts:\n" +
                "  apply:\n" +
                "    type:\n" +
                "      blueId: " + types.maskedContractId + "\n" +
                "    payload: \"${steps.Prepare.payload}\"");

        assertThrows(IllegalArgumentException.class, () -> blue.resolve(document));
    }

    @Test
    void preservedPathKeepsExpressionValueWithoutMergingDeclaredListType() {
        ContractTypes types = contractTypes();
        Blue blue = new Blue(types.provider);

        Node document = blue.yamlToNode(
                "contracts:\n" +
                "  apply:\n" +
                "    type:\n" +
                "      blueId: " + types.maskedContractId + "\n" +
                "    payload: \"${steps.Prepare.payload}\"");

        Node resolved = blue.resolvePreservingPaths(document,
                Collections.singleton("/contracts/apply/payload"));
        Node apply = resolved.getAsNode("/contracts/apply");
        Node payload = apply.getProperties().get("payload");

        assertEquals("${steps.Prepare.payload}", payload.getValue());
        assertEquals(TEXT_TYPE_BLUE_ID, payload.getType().getBlueId());
        assertNull(payload.getItemType());
        assertNull(payload.getItems());
        assertEquals("inherited", apply.getProperties().get("label").getValue());
    }

    @Test
    void preservedPathsUseJsonPointerEscaping() {
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(
                "name: Escaped Contract\n" +
                "\"a/b\":\n" +
                "  type:\n" +
                "    blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "regular: inherited");
        String typeId = provider.getBlueIdByName("Escaped Contract");
        Blue blue = new Blue(provider);

        Node document = blue.yamlToNode(
                "type:\n" +
                "  blueId: " + typeId + "\n" +
                "\"a/b\": \"${deferred.list}\"");

        assertThrows(IllegalArgumentException.class, () -> blue.resolve(document.clone()));

        Node resolved = blue.resolvePreservingPaths(document, Collections.singleton("/a~1b"));

        assertEquals("${deferred.list}", resolved.getProperties().get("a/b").getValue());
        assertEquals("inherited", resolved.getProperties().get("regular").getValue());
    }

    @Test
    void preservedResolutionCanCombineWithNormalPathLimits() {
        ContractTypes types = contractTypes();
        Blue blue = new Blue(types.provider);

        Node document = node(
                "contracts:\n" +
                "  apply:\n" +
                "    type:\n" +
                "      blueId: " + types.maskedContractId + "\n" +
                "    payload: \"${steps.Prepare.payload}\"\n" +
                "  untouched:\n" +
                "    type:\n" +
                "      blueId: " + types.maskedContractId + "\n" +
                "    payload:\n" +
                "      - amount: 1\n" +
                "        memo: ok");

        Node resolved = blue.resolvePreservingPaths(
                document,
                PathLimits.withSinglePath("/contracts/apply"),
                Collections.singleton("/contracts/apply/payload"));

        Node apply = resolved.getAsNode("/contracts/apply");
        assertEquals("${steps.Prepare.payload}", apply.getProperties().get("payload").getValue());
        assertFalse(resolved.getAsNode("/contracts").getProperties().containsKey("untouched"));
    }

    @Test
    void matchingPathPatternsPreserveOnlyExpressionLeavesInsideAList() {
        ProductTypes types = productTypes();
        Blue blue = new Blue(types.provider);
        List<String> patterns = Arrays.asList("/products", "/products/-/ean");

        Node document = blue.yamlToNode(
                "type:\n" +
                "  blueId: " + types.inventoryId + "\n" +
                "products:\n" +
                "  - name: product 1\n" +
                "    ean: \"${event.ean}\"");

        assertEquals(Collections.singletonList("/products/0/ean"),
                blue.selectPaths(document, patterns, this::isExpressionText));

        Node resolved = blue.resolvePreservingMatchingPaths(document, patterns, this::isExpressionText);
        Node products = resolved.getProperties().get("products");
        Node product = products.getItems().get(0);
        Node ean = product.getProperties().get("ean");

        assertEquals(LIST_TYPE_BLUE_ID, products.getType().getBlueId());
        assertEquals(types.productId, products.getItemType().getBlueId());
        assertEquals(types.productId, product.getType().getBlueId());
        assertEquals("${event.ean}", ean.getValue());
        assertEquals(TEXT_TYPE_BLUE_ID, ean.getType().getBlueId());
    }

    @Test
    void matchingPathPatternsKeepLiteralListFullyValidatedWhenNoNodesMatchPredicate() {
        ProductTypes types = productTypes();
        Blue blue = new Blue(types.provider);
        List<String> patterns = Arrays.asList("/products", "/products/-/ean");

        Node document = blue.yamlToNode(
                "type:\n" +
                "  blueId: " + types.inventoryId + "\n" +
                "products:\n" +
                "  - name: product 1\n" +
                "    ean: 1234");

        assertTrue(blue.selectPaths(document, patterns, this::isExpressionText).isEmpty());

        Node resolved = blue.resolvePreservingMatchingPaths(document, patterns, this::isExpressionText);
        Node product = resolved.getAsNode("/products").getItems().get(0);
        Node ean = product.getProperties().get("ean");

        assertEquals(types.productId, product.getType().getBlueId());
        assertEquals(INTEGER_TYPE_BLUE_ID, ean.getType().getBlueId());
        assertEquals(new BigInteger("1234"), ean.getValue());
    }

    @Test
    void matchingPathPatternsDoNotPreserveInvalidNonExpressionLeaf() {
        ProductTypes types = productTypes();
        Blue blue = new Blue(types.provider);
        List<String> patterns = Arrays.asList("/products", "/products/-/ean");

        Node document = blue.yamlToNode(
                "type:\n" +
                "  blueId: " + types.inventoryId + "\n" +
                "products:\n" +
                "  - name: product 1\n" +
                "    ean: not-a-number");

        assertTrue(blue.selectPaths(document, patterns, this::isExpressionText).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> blue.resolvePreservingMatchingPaths(document, patterns, this::isExpressionText));
    }

    private Node node(String yaml) {
        return YAML_MAPPER.readValue(yaml, Node.class);
    }

    private boolean isExpressionText(Node node) {
        Object value = node.getRawValue();
        if (!(value instanceof String)) {
            return false;
        }
        String text = ((String) value).trim();
        return text.startsWith("${") && text.endsWith("}") && text.length() > 3;
    }

    private ContractTypes contractTypes() {
        BasicNodeProvider provider = new BasicNodeProvider();

        provider.addSingleDocs(
                "name: Patch Entry\n" +
                "amount:\n" +
                "  type:\n" +
                "    blueId: " + INTEGER_TYPE_BLUE_ID + "\n" +
                "memo:\n" +
                "  type: Text");

        String patchEntryId = provider.getBlueIdByName("Patch Entry");

        provider.addSingleDocs(
                "name: Masked Contract\n" +
                "payload:\n" +
                "  type:\n" +
                "    blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "  itemType:\n" +
                "    blueId: " + patchEntryId + "\n" +
                "label: inherited");

        return new ContractTypes(
                provider,
                provider.getBlueIdByName("Masked Contract"));
    }

    private ProductTypes productTypes() {
        BasicNodeProvider provider = new BasicNodeProvider();

        provider.addSingleDocs(
                "name: Product\n" +
                "ean:\n" +
                "  type:\n" +
                "    blueId: " + INTEGER_TYPE_BLUE_ID);

        String productId = provider.getBlueIdByName("Product");

        provider.addSingleDocs(
                "name: Product Inventory\n" +
                "products:\n" +
                "  type:\n" +
                "    blueId: " + LIST_TYPE_BLUE_ID + "\n" +
                "  itemType:\n" +
                "    blueId: " + productId + "\n" +
                "status: open");

        return new ProductTypes(
                provider,
                productId,
                provider.getBlueIdByName("Product Inventory"));
    }

    private static final class ContractTypes {
        private final BasicNodeProvider provider;
        private final String maskedContractId;

        private ContractTypes(BasicNodeProvider provider, String maskedContractId) {
            this.provider = provider;
            this.maskedContractId = maskedContractId;
        }
    }

    private static final class ProductTypes {
        private final BasicNodeProvider provider;
        private final String productId;
        private final String inventoryId;

        private ProductTypes(BasicNodeProvider provider, String productId, String inventoryId) {
            this.provider = provider;
            this.productId = productId;
            this.inventoryId = inventoryId;
        }
    }
}
