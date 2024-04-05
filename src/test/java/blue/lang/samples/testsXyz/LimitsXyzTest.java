package blue.lang.samples.testsXyz;

import blue.lang.Merger;
import blue.lang.MergingProcessor;
import blue.lang.Node;
import blue.lang.model.limits.Limits;
import blue.lang.processor.BlueIdResolver;
import blue.lang.processor.SequentialMergingProcessor;
import blue.lang.processor.TypeAssigner;
import blue.lang.processor.ValuePropagator;
import blue.lang.utils.DirectoryBasedNodeProvider;
import blue.lang.utils.NodeToObject;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import static blue.lang.TestUtils.samplesDirectoryNodeProvider;
import static blue.lang.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

public class LimitsXyzTest {

    private static final boolean PRINT = false;

    @Test
    public void testSlash() throws Exception {
        Node node = resolve(Limits.path("/"));

        print(node);

        assertNotNull(node.getBlueId());
    }

    @Test
    public void testSlashStar() throws Exception {
        Node node = resolve(Limits.path("/*"));

        print(node);

        assertNotNull(node.getName());
        assertNotNull(node.getProperties().get("a").getBlueId());
        assertNotNull(node.getProperties().get("b").getValue());
        assertNotNull(node.getProperties().get("c").getBlueId());

    }

    @Test
    public void testSlashB() throws Exception {
        Node node = resolve(Limits.path("/b"));

        print(node);

        assertNotNull(node.getName());
        assertNull(node.getProperties().get("a"));
        assertNotNull(node.getProperties().get("b").getValue());
        assertNull(node.getProperties().get("c"));

    }

    @Test
    public void testAStarBStar() throws Exception {
        Node node = resolve(Limits.path("/a/*").and(Limits.path("/b/*")));

        print(node);

        assertNotNull(node.getName());
        assertNotNull(node.getProperties().get("a").getName());
        assertNotNull(node.getProperties().get("a").getDescription());
        assertNotNull(node.getProperties().get("a").getProperties().get("a1").getBlueId());
        assertNotNull(node.getProperties().get("a").getProperties().get("a2").getValue());
        assertNotNull(node.getProperties().get("b").getValue());
    }

    @Test
    public void testAA1() throws Exception {
        Node node = resolve(Limits.path("/a/a1"));

        print(node);

        assertNotNull(node.getName());
        assertNull(node.getProperties().get("a").getName());
        assertNull(node.getProperties().get("a").getDescription());
        assertNotNull(node.getProperties().get("a").getProperties().get("a1").getBlueId());
        assertNull(node.getProperties().get("a").getProperties().get("a2"));
        assertNull(node.getProperties().get("c"));
    }

    @Test
    public void testC() throws Exception {
        Node node = resolve(Limits.path("/c"));

        print(node);

        assertNotNull(node.getName());
        assertNull(node.getProperties().get("a"));
        assertNull(node.getProperties().get("b"));
        assertNotNull(node.getProperties().get("c").getBlueId());
    }

    @Test
    public void testCStar() throws Exception {
        Node node = resolve(Limits.path("/c/*"));

        print(node);

        assertNotNull(node.getName());
        assertNotNull(node.getProperties().get("c").getItems().get(0).getBlueId());
        assertNotNull(node.getProperties().get("c").getItems().get(1).getBlueId());
    }

    @Test
    public void testCZeroStar() throws Exception {
        Node node = resolve(Limits.path("/c/0/*"));
        print(node);

        assertNotNull(node.getName());
        assertNotNull(node.getProperties().get("c").getItems().get(0).getName());
        assertNotNull(node.getProperties().get("c").getItems().get(0).getType().getBlueId());
        assertNotNull(node.getProperties().get("c").getItems().get(0).getProperties().get("price").getBlueId());
        assertThrows(IndexOutOfBoundsException.class, () -> {
            node.getProperties().get("c").getItems().get(1);
        });
    }

    @Test
    public void testCStarStar() throws Exception {
        Node node = resolve(Limits.path("/c/*/*"));
        print(node);

        assertNotNull(node.getName());
        assertNotNull(node.getProperties().get("c").getItems().get(0).getName());
        assertNotNull(node.getProperties().get("c").getItems().get(0).getType().getBlueId());
        assertNotNull(node.getProperties().get("c").getItems().get(0).getProperties().get("price").getBlueId());
        assertNotNull(node.getProperties().get("c").getItems().get(1).getName());
        assertNotNull(node.getProperties().get("c").getItems().get(1).getType().getBlueId());
        assertNotNull(node.getProperties().get("c").getItems().get(1).getProperties().get("price").getBlueId());

    }

    @Test
    public void testC1StarStar() throws Exception {
        Node node = resolve(Limits.path("/c/1/*/*"));

        print(node);

        assertNotNull(node.getName());
        assertNotNull(node.getProperties().get("c").getItems().get(0).getName());
        assertNotNull(node.getProperties().get("c").getItems().get(0).getType().getName());
        assertNotNull(node.getProperties().get("c").getItems().get(0).getProperties().get("price").getProperties().get("amount").getBlueId());
        assertNotNull(node.getProperties().get("c").getItems().get(0).getProperties().get("details").getProperties().get("specification").getBlueId());
        assertNotNull(node.getProperties().get("c").getItems().get(0).getProperties().get("price").getProperties().get("amount").getValue());
        assertThrows(IndexOutOfBoundsException.class, () -> {
            node.getProperties().get("c").getItems().get(1);
        });
    }

    @Test
    public void testC1PriceAmount() throws Exception {
        Node node = resolve(Limits.path("/c/1/price/amount/**"));

        print(node);

        assertNotNull(node.getName());
        assertNull(node.getProperties().get("c").getItems().get(0).getName());
        assertNotNull(node.getProperties().get("c").getItems().get(0).getType());
        assertNull(node.getProperties().get("c").getItems().get(0).getProperties().get("price").getProperties().get("amount").getBlueId());
        assertNull(node.getProperties().get("c").getItems().get(0).getProperties().get("details"));
        assertNotNull(node.getProperties().get("c").getItems().get(0).getProperties().get("price").getProperties().get("amount").getValue());
        assertNotNull(node.getProperties().get("c").getItems().get(0).getProperties().get("price").getProperties().get("amount").getType().getName());
        assertThrows(IndexOutOfBoundsException.class, () -> {
            node.getProperties().get("c").getItems().get(1);
        });
    }

    @Test
    public void testCStarCompound() throws Exception {
        Node node = resolve(Limits.path("/c/1/price/amount")
                .and(Limits.path("/c/1/price/currency")));

        print(node);

        assertNotNull(node.getName());
        assertNull(node.getProperties().get("c").getItems().get(0).getName());
        assertNotNull(node.getProperties().get("c").getItems().get(0).getType());
        assertNotNull(node.getProperties().get("c").getItems().get(0).getProperties().get("price").getProperties().get("amount").getBlueId());
        assertNull(node.getProperties().get("c").getItems().get(0).getProperties().get("details"));
        assertNotNull(node.getProperties().get("c").getItems().get(0).getProperties().get("price").getProperties().get("amount").getValue());
        assertNotNull(node.getProperties().get("c").getItems().get(0).getProperties().get("price").getProperties().get("currency").getValue());
        assertThrows(IndexOutOfBoundsException.class, () -> {
            node.getProperties().get("c").getItems().get(1);
        });
    }

    @Test
    public void testNoLimits() throws Exception {
        Node node = resolve(Limits.NO_LIMITS);

        print(node);

        assertNotNull(node.getName());
    }

    private Node resolve(Limits limits) throws IOException {
        DirectoryBasedNodeProvider dirNodeProvider = null;
        try {
            dirNodeProvider = samplesDirectoryNodeProvider();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new BlueIdResolver(),
                        new ValuePropagator(),
                        new TypeAssigner()
                )
        );

        Merger merger = new Merger(mergingProcessor, dirNodeProvider);
        String filename = "src/test/java/blue/lang/samples/testsXyz/Xyz.blue";
        Node node = YAML_MAPPER.readValue(new File(filename), Node.class);

        return merger.resolve(node, limits);
    }

    private void print(Node node) {
        if (!PRINT) return;
        System.out.println(YAML_MAPPER.writeValueAsString(NodeToObject.get(node)));
    }
}
