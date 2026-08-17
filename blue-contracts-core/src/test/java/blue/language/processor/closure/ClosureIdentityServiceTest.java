package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Released C-CLO-01/02 vectors for platform evidence construction. */
final class ClosureIdentityServiceTest {

    private static final ClosureIdentityService IDS =
            ClosureIdentityService.INSTANCE;
    private static final String BINDING_POLICY =
            "sha256:c1e8d880499cbafc595e1fb213ee73acc6ddb8d1d9850c7ddff2224c88a03d35";
    private static final String INVOCATION =
            "sha256:6d08aa24f386414deb0dadffdfc94f355036b217a1542c062eb3c8ee7e71e432";

    @Test
    void shouldMatchReleasedPolicyOccurrenceAndScopeVectors() {
        assertEquals(
                "sha256:44ed2def49e63a24f3b818cf3d2854a3ceb8b40f679bf5b77a0be52b538258ac",
                IDS.labeledIdentity(
                        ClosureIdentityService.Constructor
                                .MANAGED_DOCUMENT_IDENTITY_POLICY,
                        "nfc-document-lineage-v1"));
        assertEquals(BINDING_POLICY,
                IDS.labeledIdentity(
                        ClosureIdentityService.Constructor
                                .MANAGED_BINDING_POLICY,
                        "exact-document-lineage"));
        assertEquals(
                "sha256:3cf3044ce503a6a8d6924c4739188b2780c74eaa2f74b3f0fd3247b3d3c0d3ec",
                IDS.labeledIdentity(
                        ClosureIdentityService.Constructor
                                .EXACT_NODE_PROVIDER_DOMAIN,
                        "fixture-exact-node-provider-v1"));
        assertEquals(
                "sha256:2d8d984a7c93db9aa076dbfdeb6617161b91c224db6a1366d3636462b762a0f9",
                IDS.labeledIdentity(
                        ClosureIdentityService.Constructor
                                .EXTERNAL_ORDER_POLICY,
                        "canonical-source-order-v1"));

        assertEquals(
                "sha256:f1e82f9a16ec41c5b9d4c5d37d0e412f4c0cdf05a639cc507aedfee2e8d3f232",
                IDS.managedOccurrenceIdentity(
                        id("b"), ScopeAddress.embedded("/a", 1L), id("a"),
                        BINDING_POLICY));
        assertEquals(
                "sha256:fcaa81273fa0de871bacd9150a4110c2d680d5498a1595940a72dd79d40e2fb7",
                IDS.managedOccurrenceBindingIdentity(
                        id("b"), ScopeAddress.embedded("/a", 1L), id("a"),
                        "8XQVkfrtGJ5kK3UBM7yR33SBME13vkumTvXo7kRJe3p8",
                        BINDING_POLICY));
        assertEquals(
                "sha256:1daa50609cd58f0d3ffb483be2ed6b3cf340e3d1f3de96d5101778a58fa4aa9e",
                IDS.managedScopeKeyIdentity(ManagedScopeKey.root(id("a"))));
    }

    @Test
    void shouldMatchReleasedComponentCauseDeliveryAndWorkVectors() {
        assertEquals(
                "sha256:7f65ed079664f9e8c2c38b1294f739ad105bc718dd96d98ec67e0a91cd4cd9d7",
                IDS.componentIdentity(
                        ComponentKind.ACYCLIC, 1L,
                        Collections.singletonList(id("a"))));
        assertEquals(
                "sha256:1cabaeee5d923ae59beee6f049ee2a69fefdd44ae4752428f184d3fb4496f058",
                IDS.componentIdentity(
                        ComponentKind.ACYCLIC, 1L,
                        Collections.singletonList(id("b"))));

        assertEquals(
                "sha256:08502d369b6bfee172fd72c48637ce4a241fa88348024904e6113f333d3cd57b",
                IDS.externalCauseIdentity(
                        "4za3n8bAtn5YGRqAEeW6iRswtRnLgGkyZg9EN64gwLy2",
                        ExternalOrderKey.of(Arrays.asList(
                                BigInteger.valueOf(100L), "timeline-a",
                                BigInteger.ONE)),
                        "sha256:2d8d984a7c93db9aa076dbfdeb6617161b91c224db6a1366d3636462b762a0f9"));

        DirectLogicalDelivery delivery = new DirectLogicalDelivery(
                ManagedScopeKey.root(id("a")), "source", "start", 0L);
        assertEquals(
                "sha256:8fc445f892c5c6962bcf955f1cd6f33104dbaa7492d2a6e2bdc61d062c0194ff",
                IDS.directDeliveryIdentity(delivery));
        assertEquals(
                "sha256:c8047b98a89fd94bc32a8f546c7d623d59254615962e60bf562f6d79c8eee439",
                IDS.directDeliverySnapshotIdentity(
                        Collections.singletonList(delivery)));

        String rootScope = IDS.managedScopeKeyIdentity(
                ManagedScopeKey.root(id("a")));
        assertEquals(
                "sha256:fb069ce644016bb25c48de7d0e8e7558e1dccfba27767d9d6a958f4e9d58b5f1",
                IDS.eventOccurrenceIdentity(
                        INVOCATION, 0L,
                        "EUX3vKa2wK4c1ZQvryFrytQWuVAwZ2vbCk4BvgMjzKAR"));
        assertEquals(
                "sha256:f7d010ef6e251088f345137ef5ebdace6d7ee90fcede2b054b4a4ed6576b98a3",
                IDS.workOccurrenceIdentity(
                        INVOCATION, 0L, WorkKind.EXTERNAL_DELIVERY,
                        rootScope,
                        "sha256:8fc445f892c5c6962bcf955f1cd6f33104dbaa7492d2a6e2bdc61d062c0194ff"));

        Map<String, Object> checkpointDomain = map();
        checkpointDomain.put("contractsVersion", "1.0");
        checkpointDomain.put("effectiveTypeBlueId",
                "2hesjWGVbvcJSu6woCUTssU9S7A69ep93UzdgvwosDLt");
        checkpointDomain.put("sourceContributionNodeBlueIds",
                Collections.singletonList(
                        "dbZBWveNT7dd52EhkqXh2b5FX94nofYDoVexpx2nSw9"));
        checkpointDomain.put("runtimeDiscriminator", "domain");
        assertEquals("ZuBZcL91HsnHvftRjirviKdby2RHiDMsW52SGkt2VWo",
                IDS.checkpointDomainBlueId(checkpointDomain));
    }

    @Test
    void shouldMatchReleasedBindingSetAndAffectedClosureVectors() {
        ManagedOccurrenceBinding bToA = binding(
                "sha256:f1e82f9a16ec41c5b9d4c5d37d0e412f4c0cdf05a639cc507aedfee2e8d3f232",
                "sha256:fcaa81273fa0de871bacd9150a4110c2d680d5498a1595940a72dd79d40e2fb7",
                "b", "/a", "a",
                "8XQVkfrtGJ5kK3UBM7yR33SBME13vkumTvXo7kRJe3p8", true);
        ManagedOccurrenceBinding aToB = binding(
                "sha256:f5d1cd1ca17ac4fa6547d53f85dadb18f4b37e1bca42588f5cb4fb9090023eca",
                "sha256:161a924802ff3740fe56c60641fdd72368ca6d1000f6a9721a518e440bf2d464",
                "a", "/b", "b",
                "6ZEqCbcDrgozabAdvxqbUVsG8z8NGxFv86ot2Go55xRZ", false);
        List<ManagedOccurrenceBinding> occurrences = Arrays.asList(bToA, aToB);
        String bindingSet = IDS.occurrenceBindingSetIdentity(occurrences);
        assertEquals(
                "sha256:bf684fafcc3901aedb2c6f7154b15a1cfa36958dbc8d479113317fd5cd0588ee",
                bindingSet);

        ComponentSnapshot componentA = acyclic(
                "sha256:7f65ed079664f9e8c2c38b1294f739ad105bc718dd96d98ec67e0a91cd4cd9d7",
                "sha256:f201416a678dd218ee3fb60b25eab0ce4e51f8c4ee25ad966c214ed6ed8a6858",
                "a", "8XQVkfrtGJ5kK3UBM7yR33SBME13vkumTvXo7kRJe3p8");
        ComponentSnapshot componentB = acyclic(
                "sha256:1cabaeee5d923ae59beee6f049ee2a69fefdd44ae4752428f184d3fb4496f058",
                "sha256:23c1237df89ee70ad0257df724270fc614a74964a3254935de319f553ec77e29",
                "b", "6ZEqCbcDrgozabAdvxqbUVsG8z8NGxFv86ot2Go55xRZ");
        assertEquals(componentA.componentStateIdentity(),
                IDS.componentStateIdentity(componentA));
        assertEquals(componentB.componentStateIdentity(),
                IDS.componentStateIdentity(componentB));

        AffectedClosureSnapshot snapshot = new AffectedClosureSnapshot(
                "sha256:ed4ac428e31d3960b96bb4d26d816e77247942b7438404a79fb27cba3d67726d",
                1L,
                Arrays.asList(
                        document("a",
                                "8XQVkfrtGJ5kK3UBM7yR33SBME13vkumTvXo7kRJe3p8",
                                true),
                        document("b",
                                "6ZEqCbcDrgozabAdvxqbUVsG8z8NGxFv86ot2Go55xRZ",
                                false)),
                occurrences,
                bindingSet,
                Arrays.asList(componentB, componentA),
                Collections.singletonList(id("a")));
        assertEquals(snapshot.closureIdentity(),
                IDS.affectedClosureIdentity(snapshot));
    }

    @Test
    void shouldRetainNullsIgnoreMapInsertionOrderAndRejectNonPortableInput() {
        Map<String, Object> first = map();
        first.put("admissionKind", "TOP_LEVEL_ADMISSION");
        first.put("label", "static-cycle");
        first.put("triggeringEventBlueId", null);
        first.put("parentTransitionIdentity", null);
        first.put("policyIdentity",
                "sha256:5ae91a56fccc3a2b3c734d2100c8bd7b7bc35cf0ce7ceee8688be519d46611a7");
        Map<String, Object> reversed = map();
        List<String> keys = new ArrayList<String>(first.keySet());
        Collections.reverse(keys);
        for (String key : keys) {
            reversed.put(key, first.get(key));
        }
        String expected =
                "sha256:9caec6d9fef14be8030fa3f88cb5a0e5eda76502943b3a8c7347e28872e10f42";
        assertEquals(expected, IDS.identity(
                ClosureIdentityService.Constructor.ADMISSION_CAUSE, first));
        assertEquals(expected, IDS.identity(
                ClosureIdentityService.Constructor.ADMISSION_CAUSE,
                reversed));
        assertEquals(expected, IDS.admissionCauseIdentity(
                AdmissionKind.TOP_LEVEL_ADMISSION, "static-cycle", null,
                null,
                "sha256:5ae91a56fccc3a2b3c734d2100c8bd7b7bc35cf0ce7ceee8688be519d46611a7"));

        Map<String, Object> missingNull = new LinkedHashMap<String, Object>(first);
        missingNull.remove("parentTransitionIdentity");
        assertThrows(IllegalArgumentException.class, () -> IDS.identity(
                ClosureIdentityService.Constructor.ADMISSION_CAUSE,
                missingNull));

        String decomposed = Normalizer.normalize("café",
                Normalizer.Form.NFD);
        assertThrows(IllegalArgumentException.class,
                () -> IDS.labeledIdentity(
                        ClosureIdentityService.Constructor.ADMISSION_POLICY,
                        decomposed));
        assertThrows(IllegalArgumentException.class,
                () -> IDS.eventOccurrenceIdentity(
                        INVOCATION, -1L, "event"));

        Map<String, Object> aboveSafe = map();
        aboveSafe.put("invocationIdentity", INVOCATION);
        aboveSafe.put("eventOccurrenceOrdinal",
                BigInteger.valueOf(9_007_199_254_740_991L)
                        .add(BigInteger.ONE));
        aboveSafe.put("eventBlueId", "event");
        assertThrows(IllegalArgumentException.class, () -> IDS.identity(
                ClosureIdentityService.Constructor.EVENT_OCCURRENCE,
                aboveSafe));

        Map<String, Object> withNull = map();
        withNull.put("admissionKind", "TOP_LEVEL_ADMISSION");
        withNull.put("label", "x");
        withNull.put("triggeringEventBlueId", null);
        withNull.put("parentTransitionIdentity", null);
        withNull.put("policyIdentity",
                "sha256:0000000000000000000000000000000000000000000000000000000000000000");
        Map<String, Object> withValues = new LinkedHashMap<String, Object>(
                withNull);
        withValues.put("triggeringEventBlueId", "event");
        assertNotEquals(IDS.identity(
                        ClosureIdentityService.Constructor.ADMISSION_CAUSE,
                        withNull),
                IDS.identity(
                        ClosureIdentityService.Constructor.ADMISSION_CAUSE,
                        withValues));
    }

    private static ManagedDocumentSnapshot document(
            String documentId, String blueId, boolean publicRoot) {
        return new ManagedDocumentSnapshot(
                id(documentId), blueId, new Node().name(documentId),
                true, false, publicRoot, 0L, 1L);
    }

    private static ComponentSnapshot acyclic(
            String identity,
            String stateIdentity,
            String documentId,
            String blueId) {
        return new ComponentSnapshot(
                identity, stateIdentity, 1L, ComponentKind.ACYCLIC,
                Collections.singletonList(id(documentId)),
                Collections.singletonList(blueId),
                null, null, null);
    }

    private static ManagedOccurrenceBinding binding(
            String occurrenceIdentity,
            String bindingIdentity,
            String source,
            String path,
            String target,
            String targetBlueId,
            boolean active) {
        return new ManagedOccurrenceBinding(
                occurrenceIdentity, bindingIdentity, BINDING_POLICY,
                id(source), ScopeAddress.embedded(path, 1L), id(target),
                targetBlueId, active, null);
    }

    private static DocumentId id(String value) {
        return new DocumentId(value);
    }

    private static Map<String, Object> map() {
        return new LinkedHashMap<String, Object>();
    }
}
