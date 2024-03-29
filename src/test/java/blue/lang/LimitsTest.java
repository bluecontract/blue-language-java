package blue.lang;

import blue.lang.model.limits.Limits;
import blue.lang.processor.*;
import blue.lang.utils.DirectoryBasedNodeProvider;
import blue.lang.utils.NodeToObject;
import blue.lang.utils.Nodes;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import static blue.lang.TestUtils.samplesDirectoryNodeProvider;
import static blue.lang.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class LimitsTest {
    private static final boolean PRINT = false;

    @Test
    public void testZeroDepthLimits() throws Exception {
        Node node = resolve(Limits.depth(0));

        print(node);

        assertNotNull(node.getBlueId());
    }

    @Test
    public void testDepthLimit1() throws Exception {
        Node node = resolve(Limits.depth(1));

        print(node);

        assertNotNull(node.getProperties().get("purchaseDate").getBlueId());

        assertThrows(NullPointerException.class, () -> {
            node.getProperties().get("details")
                    .getProperties().get("customerSupport")
                    .getProperties().get("email").getValue();
        });
    }

    @Test
    public void testDepthLimit2() throws Exception {
        Node node = resolve(Limits.depth(2));

        print(node);

        assertNotNull(node.getProperties().get("purchaseDate").getValue());
        assertNotNull(node.getProperties().get("availableMenuItems").getBlueId());

        assertThrows(NullPointerException.class, () -> {
            node.getProperties().get("details")
                    .getProperties().get("customerSupport")
                    .getProperties().get("email").getValue();
        });
    }

    @Test
    public void testDepthLimit3() throws Exception {
        Node node = resolve(Limits.depth(3));

        print(node);

        assertNotNull(node.getProperties().get("details").getBlueId());

        assertThrows(NullPointerException.class, () -> {
            node.getProperties().get("details")
                    .getProperties().get("customerSupport")
                    .getProperties().get("email").getValue();
        });

        assertThrows(NullPointerException.class, () -> {
            node.getProperties().get("details")
                    .getProperties().get("customerSupport")
                    .getProperties().get("phone").getValue();
        });

    }

    @Test
    public void testPathThrowsSlashBegin() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> {
            Limits.path("details/customerSupport/phone/*");
        });
        assertThrows(IllegalArgumentException.class, () -> {
            Limits.path("");
        });
        assertThrows(IllegalArgumentException.class, () -> {
            Limits.query(Collections.singletonList(""));
        });
        assertDoesNotThrow(() -> {
            Limits l = Limits.path("/details/customerSupport/phone/*");
            Limits copy = l.copy();
        });
    }

    @Test
    public void testPathLimits() throws Exception {
        Node node = resolve(Limits.path("/details/customerSupport/phone/*"));

        print(node);

        assertEquals("+1234567890", node.getProperties().get("details")
                .getProperties().get("customerSupport")
                .getProperties().get("phone").getValue());

        assertThrows(NullPointerException.class, () -> {
            node.getProperties().get("details")
                    .getProperties().get("customerSupport")
                    .getProperties().get("email").getValue();
        });
    }

    @Test
    public void testQueryLimits() throws Exception {
        Node node = resolve(Limits.query(
                Arrays.asList(
                        "/details/customerSupport/phone/*",
                        "/details/customerSupport/*"
                )
        ));

        print(node);

        assertEquals("+1234567890", node.getProperties().get("details")
                .getProperties().get("customerSupport")
                .getProperties().get("phone").getValue());

        assertNotNull(node.getProperties().get("details")
                        .getProperties().get("customerSupport")
                        .getProperties().get("email").getBlueId()
        );
    }

    @Test
    public void testQueryLimitsMultiStar() throws Exception {
        Node node = resolve(Limits.query(
                Arrays.asList(
                        "/details/customerSupport/phone/*",
                        "/details/customerSupport/**",
                        "/availableMenuItems"
                )
        ));

        print(node);

        assertEquals("+1234567890", node.getProperties().get("details")
                .getProperties().get("customerSupport")
                .getProperties().get("phone").getValue());

        assertEquals("support@hattorihanzo.com",
                node.getProperties().get("details")
                        .getProperties().get("customerSupport")
                        .getProperties().get("email").getValue()
        );

        assertNotNull(node.getProperties().get("availableMenuItems")
                .getBlueId());
    }

    @Test
    public void testQueryLimitsMultiStarDepthLimit() throws Exception {
        Node node = resolve(Limits.query(
                Collections.singletonList(
                        "/details/customerSupport/**"
                ), 5
        ));

        print(node);

        assertNotNull(node.getProperties().get("details")
                .getProperties().get("customerSupport")
                .getProperties().get("phone")
                .getBlueId());

        assertNotNull(node.getProperties().get("details")
                .getProperties().get("customerSupport")
                .getProperties().get("email")
                .getBlueId());
    }

    @Test
    public void testQueryLimitsMultiStarDepthLimitAnd() throws Exception {
        Limits limits = Limits.path("/details/customerSupport/**")
                .and(Limits.query(Arrays.asList("/availableMenuItems"), 1))
                .and(Limits.depth(5))
                .and(Limits.depth(2))
                .and(Limits.END_LIMITS) // should not affect the result
                .and(Limits.NO_LIMITS); // should not affect the result

        Node node = resolve(limits);

        print(node);

        assertNotNull(node.getProperties().get("details")
                .getProperties().get("customerSupport")
                .getProperties().get("phone")
                .getBlueId());

        assertNotNull(node.getProperties().get("details")
                .getProperties().get("customerSupport")
                .getProperties().get("email")
                .getBlueId());

        assertNotNull(node.getProperties().get("availableMenuItems").getBlueId());
    }

    @Test
    public void testQueryLimitsItems() throws Exception {
        Node node = resolve(Limits.path("/availableMenuItems/appetizers/1/**"));

        print(node);

        assertEquals(1, node.getProperties()
                .get("availableMenuItems")
                .getProperties().get("appetizers")
                .getItems().size());

        assertNotNull(node.getProperties()
                .get("availableMenuItems")
                .getProperties().get("appetizers")
                .getItems().get(0).getProperties().get("EdamameWithSeaSalt"));
    }

    @Test
    public void testQueryLimitsItemsRange() throws Exception {
        Node node = resolve(Limits.path("/availableMenuItems/appetizers/1-2/**"));

        print(node);

        assertEquals(2, node.getProperties()
                .get("availableMenuItems")
                .getProperties().get("appetizers")
                .getItems().size());

        assertNotNull(node.getProperties()
                .get("availableMenuItems")
                .getProperties().get("appetizers")
                .getItems().get(0).getProperties().get("EdamameWithSeaSalt"));

        assertNotNull(node.getProperties()
                .get("availableMenuItems")
                .getProperties().get("appetizers")
                .getItems().get(1).getProperties().get("TunaTataki"));
    }

    private Node resolve(Limits limits) {
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
        Node source = dirNodeProvider.getNodes().stream().filter(e -> "My Voucher".equals(e.getName())).findAny().get();

        return merger.resolve(source, limits);
    }
    private void print(Node node) {
        if (!PRINT) return;
        System.out.println(YAML_MAPPER.writeValueAsString(NodeToObject.get(node)));
    }
}
