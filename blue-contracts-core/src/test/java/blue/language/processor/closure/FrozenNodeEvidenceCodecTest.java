package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.snapshot.FrozenNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrozenNodeEvidenceCodecTest {
    private static final FrozenNodeEvidenceCodec.Limits LIMITS = FrozenNodeEvidenceCodec.Limits.defaults();

    @Test
    void publicBlobReadCannotMutateTheVerifiedDecoderCacheOrForgeAnotherValueUnderItsDigest() {
        Map<String, byte[]> store = new LinkedHashMap<>();
        String root = FrozenNodeEvidenceCodec.encode(FrozenNode.fromResolvedNode(new Node().name("safe")), store::put, LIMITS);
        FrozenNodeEvidenceCodec.Decoder decoder = new FrozenNodeEvidenceCodec.Decoder(store::get, LIMITS);
        byte[] exposed = decoder.blob(root);
        byte[] replacement = new String(exposed, java.nio.charset.StandardCharsets.UTF_8).replace("\"safe\"", "\"evil\"")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(exposed.length, replacement.length);
        System.arraycopy(replacement, 0, exposed, 0, exposed.length);
        assertEquals("safe", decoder.node(root).getName());
        assertArrayEquals(store.get(root), decoder.blob(root));
        assertNotSame(decoder.blob(root), decoder.blob(root));
    }

    @Test
    void largeConfiguredDepthCannotBypassOperationalStackSafetyOrMemoizedSubtreeHeight() throws Exception {
        FrozenNodeEvidenceCodec.Limits large = new FrozenNodeEvidenceCodec.Limits(10000, 10000000, 10000, 10000);
        FrozenNode value = FrozenNode.fromResolvedNode(new Node());
        for (int depth = 0; depth < 257; depth++) value = blue.language.snapshot.FrozenNodeBuilder.fromResolvedParts(
                FrozenNode.fromResolvedNode(new Node()), Collections.emptyMap(), null, Collections.singletonMap("child", value));
        final FrozenNode tooDeep = value;
        assertThrows(FrozenNodeEvidenceCodec.CapacityExceeded.class,
                () -> FrozenNodeEvidenceCodec.encode(tooDeep, (key, bytes) -> { }, large));

        // Valid data-only fragments isolate decoder bounds without asking the bounded encoder
        // to construct an out-of-capacity tree. The expected roots are this fixture's authority.
        Map<String, byte[]> store = new LinkedHashMap<>();
        FrozenNodeEvidenceCodec.Encoder fragments = new FrozenNodeEvidenceCodec.Encoder(store::put, large);
        String root = fragments.node(FrozenNode.fromResolvedNode(new Node()));
        com.fasterxml.jackson.databind.node.ObjectNode template = (com.fasterxml.jackson.databind.node.ObjectNode)
                FrozenNodeEvidenceCodec.JSON.readTree(store.get(root));
        List<String> roots = new ArrayList<>(); roots.add(root);
        for (int depth = 0; depth < 257; depth++) {
            com.fasterxml.jackson.databind.node.ObjectNode next = template.deepCopy();
            next.putObject("properties").put("child", root);
            root = fragments.blob(FrozenNodeEvidenceCodec.bytes(next)); roots.add(root);
        }
        final String tooDeepRoot = root;
        assertThrows(FrozenNodeEvidenceCodec.CapacityExceeded.class,
                () -> FrozenNodeEvidenceCodec.decode(tooDeepRoot, store::get, large));
        FrozenNodeEvidenceCodec.Decoder cached = new FrozenNodeEvidenceCodec.Decoder(store::get, large);
        for (int depth = 0; depth <= 256; depth++) assertNotNull(cached.node(roots.get(depth)));
        assertThrows(FrozenNodeEvidenceCodec.CapacityExceeded.class, () -> cached.node(tooDeepRoot));
    }

    @Test
    void preservesNonemptyListsAndListElementConstructionContext() {
        FrozenNode original = FrozenNode.fromResolvedNode(new Node().items(Arrays.asList(
                new Node().previousBlueId("retained-prefix"),
                new Node().value(1), new Node().properties("$empty", new Node().value(true)))));
        Map<String, byte[]> store = new LinkedHashMap<String, byte[]>();
        String id = FrozenNodeEvidenceCodec.encode(original, store::put, LIMITS);
        FrozenNode restored = FrozenNodeEvidenceCodec.decode(id, store::get, LIMITS);
        assertTrue(original.sameResolvedStructure(restored));
        for (FrozenNode item : restored.getItems()) assertTrue(item.isListElementContext());
    }

    @Test
    void schemaChildUsesTheSameDepthOnEncodeAndDecode() {
        FrozenNode original = FrozenNode.fromResolvedNode(new Node().schema(new Schema().minimum(new Node().value(1))));
        FrozenNodeEvidenceCodec.Limits one = new FrozenNodeEvidenceCodec.Limits(10000, 100000, 1, 100);
        Map<String, byte[]> store = new LinkedHashMap<String, byte[]>();
        String id = FrozenNodeEvidenceCodec.encode(original, store::put, one);
        assertTrue(original.sameResolvedStructure(FrozenNodeEvidenceCodec.decode(id, store::get, one)));
    }

    @Test
    void cachedSubtreeCannotBypassDepthCapacity() {
        Map<String, byte[]> store = new LinkedHashMap<String, byte[]>();
        FrozenNodeEvidenceCodec.Encoder encoder = new FrozenNodeEvidenceCodec.Encoder(store::put, LIMITS);
        List<String> roots = new ArrayList<String>();
        FrozenNode node = FrozenNode.fromResolvedNode(new Node().value(1));
        roots.add(encoder.node(node));
        for (int depth = 0; depth < 6; depth++) {
            node = blue.language.snapshot.FrozenNodeBuilder.fromResolvedParts(FrozenNode.fromResolvedNode(new Node()),
                    Collections.<String, FrozenNode>emptyMap(), null, Collections.singletonMap("child", node));
            roots.add(encoder.node(node));
        }
        FrozenNodeEvidenceCodec.Limits low = new FrozenNodeEvidenceCodec.Limits(10000, 100000, 4, 100);
        FrozenNodeEvidenceCodec.Decoder decoder = new FrozenNodeEvidenceCodec.Decoder(store::get, low);
        for (int i = 0; i <= 4; i++) decoder.node(roots.get(i));
        assertThrows(FrozenNodeEvidenceCodec.CapacityExceeded.class, () -> decoder.node(roots.get(5)));
        FrozenNodeEvidenceCodec.Encoder limited = new FrozenNodeEvidenceCodec.Encoder((id, bytes) -> { }, low);
        for (int i = 0; i <= 4; i++) limited.node(FrozenNodeEvidenceCodec.decode(roots.get(i), store::get, LIMITS));
        assertThrows(FrozenNodeEvidenceCodec.CapacityExceeded.class,
                () -> limited.node(FrozenNodeEvidenceCodec.decode(roots.get(5), store::get, LIMITS)));
    }

    @Test
    void sharedDagCannotExpandPastMaterializationCapacity() {
        Map<String, byte[]> store = new LinkedHashMap<String, byte[]>();
        FrozenNode node = FrozenNode.fromResolvedNode(new Node().value(1));
        for (int depth = 0; depth < 20; depth++) {
            Map<String, FrozenNode> children = new LinkedHashMap<String, FrozenNode>();
            children.put("left", node); children.put("right", node);
            node = blue.language.snapshot.FrozenNodeBuilder.fromResolvedParts(FrozenNode.fromResolvedNode(new Node()),
                    Collections.<String, FrozenNode>emptyMap(), null, children);
        }
        String root = FrozenNodeEvidenceCodec.encode(node, store::put, LIMITS);
        assertEquals(21, store.size());
        assertThrows(FrozenNodeEvidenceCodec.CapacityExceeded.class,
                () -> FrozenNodeEvidenceCodec.decode(root, store::get, LIMITS));
    }
    @Test
    void preservesExactResolvedShapeNumbersSchemaAndMetadata() {
        Node root = new Node().name("exact source").description("description")
                .properties("integer",new Node().value(new BigInteger("123456789012345678901234567890")))
                .properties("decimal",new Node().value(new BigDecimal("1.234500000000000000000001")))
                .properties("boolean",new Node().value(true))
                .properties("byte",new Node().value((Object) Byte.valueOf((byte) 7)))
                .properties("short",new Node().value((Object) Short.valueOf((short) 9)))
                .properties("emptyObject",new Node().properties(new LinkedHashMap<String,Node>()))
                .properties("emptyList",new Node().items(new ArrayList<Node>()))
                .properties("typed",new Node().type(new Node().blueId("type-reference").name("resolved type"))
                        .value("text").schema(new Schema().minLength(new Node().value(2)).enumValues(Arrays.asList(new Node().value("text")))))
                .contracts(new Node().properties("channel",new Node().name("channel")));
        FrozenNode original=FrozenNode.fromResolvedNode(root);
        Map<String,byte[]> store=new LinkedHashMap<String,byte[]>();
        String id=FrozenNodeEvidenceCodec.encode(original,store::put,LIMITS);
        FrozenNode restored=FrozenNodeEvidenceCodec.decode(id,store::get,LIMITS);
        assertTrue(original.sameResolvedStructure(restored));
        Map<String,byte[]> second=new LinkedHashMap<String,byte[]>();
        assertEquals(id,FrozenNodeEvidenceCodec.encode(restored,second::put,LIMITS));
        assertEquals(store.keySet(),second.keySet());
        for(String key:store.keySet())assertArrayEquals(store.get(key),second.get(key));
    }

    @Test
    void changedViewWritesOnlyNewPathFragmentsNotColdSubtrees() {
        Node cold=new Node(); for(int i=0;i<200;i++)cold.properties("field"+i,new Node().value("payload-"+i));
        Node first=new Node().properties("cold",cold).properties("counter",new Node().value(1));
        Node second=first.clone().properties("counter",new Node().value(2));
        Map<String,byte[]> store=new LinkedHashMap<String,byte[]>();
        FrozenNodeEvidenceCodec.Encoder e=new FrozenNodeEvidenceCodec.Encoder(store::put,LIMITS);
        String before=e.node(FrozenNode.fromResolvedNode(first));int initial=store.size();
        String after=e.node(FrozenNode.fromResolvedNode(second));
        assertNotEquals(before,after);assertEquals(2,store.size()-initial,"only changed scalar and root are new");
        assertTrue(FrozenNode.fromResolvedNode(second).sameResolvedStructure(FrozenNodeEvidenceCodec.decode(after,store::get,LIMITS)));
    }

    @Test
    void missingAndCorruptFragmentsCannotBecomeAUsableState() {
        Map<String,byte[]> store=new LinkedHashMap<String,byte[]>();
        String root=FrozenNodeEvidenceCodec.encode(FrozenNode.fromResolvedNode(new Node().properties("child",new Node().value(7))),store::put,LIMITS);
        String child=store.keySet().iterator().next();assertNotEquals(root,child);
        byte[] exact=store.remove(child);
        assertThrows(ExecutionEvidenceUnavailableException.class,()->FrozenNodeEvidenceCodec.decode(root,store::get,LIMITS));
        byte[] corrupt=exact.clone();corrupt[0]^=1;store.put(child,corrupt);
        assertThrows(InvalidExecutionEvidenceException.class,()->FrozenNodeEvidenceCodec.decode(root,store::get,LIMITS));
        store.put(child,exact);
        assertNotNull(FrozenNodeEvidenceCodec.decode(root,store::get,LIMITS));
    }

    @Test
    void acquisitionCapacityIsNoncommittingAndDoesNotChangeExactIdentity() {
        Node node=new Node();for(int i=0;i<12;i++)node=new Node().properties("child",node);
        FrozenNode value=FrozenNode.fromResolvedNode(node);Map<String,byte[]> store=new LinkedHashMap<String,byte[]>();
        FrozenNodeEvidenceCodec.Limits low=new FrozenNodeEvidenceCodec.Limits(1024*1024,1024*1024,4,1000);
        assertThrows(FrozenNodeEvidenceCodec.CapacityExceeded.class,()->FrozenNodeEvidenceCodec.encode(value,store::put,low));
        String id=FrozenNodeEvidenceCodec.encode(value,store::put,LIMITS);
        assertThrows(FrozenNodeEvidenceCodec.CapacityExceeded.class,()->FrozenNodeEvidenceCodec.decode(id,store::get,low));
        assertTrue(value.sameResolvedStructure(FrozenNodeEvidenceCodec.decode(id,store::get,LIMITS)));
    }
}
