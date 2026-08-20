package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.ClosureRuntimeDescriptor;
import blue.language.processor.ExternalOrderKey;
import blue.language.provider.CyclicSetProof;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.erdtman.jcs.JsonCanonicalizer;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Released admission-candidate and full invocation identity vectors. */
final class ClosureInvocationVerifierTest {

    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;
    private static final String BINDING_POLICY =
            "sha256:c1e8d880499cbafc595e1fb213ee73acc6ddb8d1d9850c7ddff2224c88a03d35";
    private static final String EXTERNAL_ORDER_POLICY =
            "sha256:2d8d984a7c93db9aa076dbfdeb6617161b91c224db6a1366d3636462b762a0f9";
    private static final String BLUE_A =
            "8XQVkfrtGJ5kK3UBM7yR33SBME13vkumTvXo7kRJe3p8";
    private static final String BLUE_B =
            "6ZEqCbcDrgozabAdvxqbUVsG8z8NGxFv86ot2Go55xRZ";
    private static final String C_CLO_34_INVOCATION_IDENTITY =
            "sha256:e912586ad514329cac9fd200af39d6ff67f9c1333d38536e2b24008c90092ff8";
    private static final String C_CLO_34_CANONICAL_INVOCATION_ENVELOPE =
            "{\"domain\":\"blue-contracts-invocation/1.0\",\"value\":{\"admissionCandidateIdenti"
                    + "ty\":null,\"blueLanguageSpecificationIdentity\":\"sha256:01b038b64e3f0a9a11f3f70"
                    + "d544a63ff78a01d5169f1a03f8b8629cf73645a7d\",\"causeIdentity\":\"sha256:08502d369"
                    + "b6bfee172fd72c48637ce4a241fa88348024904e6113f333d3cd57b\",\"contractsSpecifica"
                    + "tionIdentity\":\"sha256:dfb444962a5a17b3a6519e8d148c2bf4a975a921b1fcb1277710052caa"
                    + "ecd930\",\"cyclicFinalizerIdentity\":\"sha256:0b4bd3bbe4380faa52d14bc6baf8bb"
                    + "0a6dbc01acc576985676155ea0115969b4\",\"cyclicProofVerifierIdentity\":\"sha256:eb"
                    + "0501a25ec5ac6a18fc86584c0afb6ecc2e6c1201c723f28ec56c80a2ae3bc5\",\"directDeliv"
                    + "erySnapshotIdentity\":\"sha256:c8047b98a89fd94bc32a8f546c7d623d59254615962e60b"
                    + "f562f6d79c8eee439\",\"documents\":[{\"blueId\":\"8XQVkfrtGJ5kK3UBM7yR33SBME13vkumT"
                    + "vXo7kRJe3p8\",\"componentGeneration\":1,\"documentId\":\"a\",\"epoch\":0,\"initialized"
                    + "\":true,\"publicRoot\":true,\"terminated\":false},{\"blueId\":\"6ZEqCbcDrgozabAdvxqb"
                    + "UVsG8z8NGxFv86ot2Go55xRZ\",\"componentGeneration\":1,\"documentId\":\"b\",\"epoch\":0"
                    + ",\"initialized\":true,\"publicRoot\":false,\"terminated\":false}],\"exactNodeProvid"
                    + "erDomainIdentity\":\"sha256:3cf3044ce503a6a8d6924c4739188b2780c74eaa2f74b3f0fd"
                    + "3247b3d3c0d3ec\",\"externalOrderPolicyIdentity\":\"sha256:2d8d984a7c93db9aa076db"
                    + "fdeb6617161b91c224db6a1366d3636462b762a0f9\",\"gasManifestIdentity\":\"sha256:54"
                    + "310113bbfc0c6529802fa134a40d7131a4c72e52ccd11d16b20733db60bad8\",\"gasPolicyId"
                    + "entity\":\"sha256:06be3c4e41fbbc52cf8289ff95cc52264b2f5093d75c590501173942490c"
                    + "1685\",\"inputClosureIdentity\":\"sha256:ed4ac428e31d3960b96bb4d26d816e77247942b"
                    + "7438404a79fb27cba3d67726d\",\"inputGraphGeneration\":1,\"managedBindingPolicyIde"
                    + "ntity\":\"sha256:c1e8d880499cbafc595e1fb213ee73acc6ddb8d1d9850c7ddff2224c88a03"
                    + "d35\",\"managedDocumentIdentityPolicyIdentity\":\"sha256:44ed2def49e63a24f3b818c"
                    + "f3d2854a3ceb8b40f679bf5b77a0be52b538258ac\",\"occurrenceBindingSetIdentity\":\"s"
                    + "ha256:bf684fafcc3901aedb2c6f7154b15a1cfa36958dbc8d479113317fd5cd0588ee\",\"ope"
                    + "ration\":\"process-closure\",\"portableLimitPolicyIdentity\":\"sha256:fcd17a9a3270"
                    + "82d9a563c59be1089a7f4ee09275d74915b2c84563be42c575a5\",\"runtimeRegistryIdenti"
                    + "ty\":\"sha256:46a7744c1cbfa4b00e1d8a99f6ca3f0089ef697de968fee08547894ab02b0ca1"
                    + "\"}}";

    @Test
    void shouldMatchReleasedCclo14Cclo15AndCclo29CandidateIdentities() {
        ComponentSnapshot authoritativeCyclic = new ComponentSnapshot(
                "sha256:b27694341619d5d0835f99889e08eed563c00a8124aaf5945b115826c8b7e4b9",
                "sha256:d92f1fdf1384e6066c507ce8e5172434ac0da4578c8ae0eb03495328f5ba8c18",
                1L,
                ComponentKind.CYCLIC,
                Arrays.asList(id("simple-a"), id("simple-b")),
                Arrays.asList(
                        "AUy8JhB5oRViCnC7CYpbL1hJdRY2sKDJUNaogzJ13xgR#0",
                        "AUy8JhB5oRViCnC7CYpbL1hJdRY2sKDJUNaogzJ13xgR#1"),
                "AUy8JhB5oRViCnC7CYpbL1hJdRY2sKDJUNaogzJ13xgR",
                CyclicSetProof.fromDeclaredPlaceholderSet(Arrays.asList(
                        simpleAPlaceholder(), simpleBPlaceholder())),
                "sha256:8853187f6535f16f427ab7ba5e40e59ef799b5b28c6ee8843ffd88685369835e");
        assertEquals(authoritativeCyclic.componentIdentity(),
                IDENTITIES.componentIdentity(authoritativeCyclic));
        assertEquals(authoritativeCyclic.cyclicProofIdentity(),
                IDENTITIES.cyclicProofIdentity(authoritativeCyclic));
        assertEquals(authoritativeCyclic.componentStateIdentity(),
                IDENTITIES.componentStateIdentity(authoritativeCyclic));

        AdmissionCandidate badProof = AdmissionCandidate.badCyclicProof(
                new AdmissionCandidate.CandidateCyclicProof(
                        "sha256:b27694341619d5d0835f99889e08eed563c00a8124aaf5945b115826c8b7e4b9",
                        "6Rnjv8oquG4RqPwo55MZgF7jcUZGd7HdYZMPiFmBQUQ7",
                        Arrays.asList(
                                memberState("simple-a",
                                        "AUy8JhB5oRViCnC7CYpbL1hJdRY2sKDJUNaogzJ13xgR#0"),
                                memberState("simple-b",
                                        "AUy8JhB5oRViCnC7CYpbL1hJdRY2sKDJUNaogzJ13xgR#1")),
                        Arrays.asList(
                                simpleAPlaceholder(),
                                simpleBPlaceholder())));
        assertEquals(
                "sha256:fbb8e227a353e5c688dcbb467161979c72297ff1ca337eaf44fcf0e67728fe07",
                IDENTITIES.admissionCandidateIdentity(badProof));

        AdmissionCandidate ambiguous =
                AdmissionCandidate.ambiguousPreliminaryMembers(Arrays.asList(
                        new AdmissionCandidate.CandidateCyclicMember(
                                id("amb-a"), ambiguousMember("this#1")),
                        new AdmissionCandidate.CandidateCyclicMember(
                                id("amb-b"), ambiguousMember("this#0"))));
        assertEquals(
                "sha256:ab49acd584f0890f1c882fc693aeff7c4a26048b135dc243d0565dd69d723e68",
                IDENTITIES.admissionCandidateIdentity(ambiguous));

        AdmissionCandidate invalidBinding =
                AdmissionCandidate.invalidOccurrenceBinding(
                        Collections.singletonList(
                                new AdmissionCandidate.CandidateOccurrenceBinding(
                                        "sha256:207cc838840347462bb026a57371a0a4c0ade4d1aeb34e0eff0b85048a6bfa86",
                                        "sha256:bc8de9cc44900c0b9c637b880ee55eee51d947a08592b454723d2bf06b9d17e7",
                                        BINDING_POLICY,
                                        id("simple-a"),
                                        "/missing",
                                        1L,
                                        id("simple-b"),
                                        "AUy8JhB5oRViCnC7CYpbL1hJdRY2sKDJUNaogzJ13xgR#1",
                                        true)));
        assertEquals(
                "sha256:8e75fb2a179bf07d157a1dc402e399f5cfe6f62731dab93d8e35acc694560af7",
                IDENTITIES.admissionCandidateIdentity(invalidBinding));
    }

    @Test
    void shouldMatchReleasedCclo34FullInvocationIdentity() {
        ClosureInvocationInput input = cclo34Input();
        Map<String, Object> frozenEnvelope = frozenCclo34Envelope();
        Map<String, Object> actualEnvelope = new LinkedHashMap<String, Object>();
        actualEnvelope.put("domain", "blue-contracts-invocation/1.0");
        actualEnvelope.put("value",
                IDENTITIES.invocationIdentityConstructorValue(input));
        byte[] frozenBytes = C_CLO_34_CANONICAL_INVOCATION_ENVELOPE
                .getBytes(StandardCharsets.UTF_8);

        assertEquals(
                "4za3n8bAtn5YGRqAEeW6iRswtRnLgGkyZg9EN64gwLy2",
                ((ExternalEventCause) input.cause()).eventBlueId());
        assertEquals(2131, frozenBytes.length);
        assertEquals(frozenEnvelope, actualEnvelope);
        assertArrayEquals(frozenBytes,
                independentlyCanonicalize(actualEnvelope));
        assertEquals(C_CLO_34_INVOCATION_IDENTITY,
                independentSha256Identity(frozenBytes));
        assertEquals(C_CLO_34_INVOCATION_IDENTITY,
                input.invocationIdentity());
        assertEquals(C_CLO_34_INVOCATION_IDENTITY,
                IDENTITIES.invocationIdentity(input));
    }

    @Test
    void shouldVerifyEveryPhaseAIdentityBeforeExecution() {
        ClosureInvocationInput input = exactSimpleProcessInput(hash('0'));
        String invocationIdentity = IDENTITIES.invocationIdentity(input);
        input = exactSimpleProcessInput(invocationIdentity);

        ClosureInvocationVerifier.Verification verification =
                ClosureInvocationVerifier.verify(input);
        assertEquals(invocationIdentity, verification.invocationIdentity());
        assertEquals(
                ClosureInvocationVerifier.CandidateDisposition.NOT_SUBMITTED,
                verification.candidateDisposition());

        ClosureInvocationInput wrong = exactSimpleProcessInput(hash('f'));
        assertThrows(IllegalArgumentException.class,
                () -> ClosureInvocationVerifier.verify(wrong));
    }

    @Test
    void shouldIndependentlyRecomputeEveryPortableEnvironmentIdentity() {
        ClosureEnvironment exact = environment(BINDING_POLICY);
        ClosureEnvironment mismatched = new ClosureEnvironment(
                exact.blueLanguageSpecificationIdentity(),
                exact.contractsSpecificationIdentity(),
                exact.runtimeRegistryIdentity(),
                exact.gasManifestIdentity(),
                labeled(exact.managedDocumentIdentityPolicyIdentity(),
                        "different-document-policy"),
                exact.managedBindingPolicy(),
                exact.exactNodeProviderDomain(),
                exact.externalOrderPolicy(),
                exact.portableLimitPolicy(),
                exact.cyclicFinalizerIdentity(),
                exact.cyclicProofVerifierIdentity());
        ClosureInvocationInput provisional = exactSimpleProcessInput(
                hash('0'), mismatched);
        ClosureInvocationInput input = exactSimpleProcessInput(
                IDENTITIES.invocationIdentity(provisional), mismatched);

        assertThrows(IllegalArgumentException.class,
                () -> ClosureInvocationVerifier.verify(input));
    }

    @Test
    void shouldReturnSemanticCandidateRejectionInsteadOfShapeFailure() {
        AdmissionCandidate candidate =
                AdmissionCandidate.ambiguousPreliminaryMembers(Arrays.asList(
                        new AdmissionCandidate.CandidateCyclicMember(
                                id("amb-a"), ambiguousMember("this#1")),
                        new AdmissionCandidate.CandidateCyclicMember(
                                id("amb-b"), ambiguousMember("this#0"))));
        ClosureInvocationInput provisional = exactSimpleAdmissionInput(
                hash('0'), candidate,
                IDENTITIES.admissionCandidateIdentity(candidate));
        ClosureInvocationInput input = exactSimpleAdmissionInput(
                IDENTITIES.invocationIdentity(provisional),
                candidate,
                IDENTITIES.admissionCandidateIdentity(candidate));

        assertEquals(
                ClosureInvocationVerifier.CandidateDisposition
                        .SEMANTICALLY_INVALID,
                ClosureInvocationVerifier.verify(input)
                        .candidateDisposition());
        assertEquals(
                AdmissionCandidate.Kind.AMBIGUOUS_PRELIMINARY_MEMBERS,
                ClosureInvocationVerifier.verify(input).candidateKind());

        assertThrows(IllegalArgumentException.class, () ->
                AdmissionCandidate.ambiguousPreliminaryMembers(Arrays.asList(
                        new AdmissionCandidate.CandidateCyclicMember(
                                id("duplicate"), ambiguousMember("this#1")),
                        new AdmissionCandidate.CandidateCyclicMember(
                                id("duplicate"), ambiguousMember("this#0")))));

        ClosureInvocationInput wrongCandidateIdentity =
                exactSimpleAdmissionInput(hash('1'), candidate, hash('2'));
        assertThrows(IllegalArgumentException.class, () ->
                ClosureInvocationVerifier.verify(wrongCandidateIdentity));
    }

    private static ClosureInvocationInput cclo34Input() {
        ManagedOccurrenceBinding bToA = binding(
                "sha256:f1e82f9a16ec41c5b9d4c5d37d0e412f4c0cdf05a639cc507aedfee2e8d3f232",
                "sha256:fcaa81273fa0de871bacd9150a4110c2d680d5498a1595940a72dd79d40e2fb7",
                "b", "/a", "a", BLUE_A, true);
        ManagedOccurrenceBinding aToB = binding(
                "sha256:f5d1cd1ca17ac4fa6547d53f85dadb18f4b37e1bca42588f5cb4fb9090023eca",
                "sha256:161a924802ff3740fe56c60641fdd72368ca6d1000f6a9721a518e440bf2d464",
                "a", "/b", "b", BLUE_B, false);
        ComponentSnapshot componentA = acyclicComponent(
                "sha256:7f65ed079664f9e8c2c38b1294f739ad105bc718dd96d98ec67e0a91cd4cd9d7",
                "sha256:f201416a678dd218ee3fb60b25eab0ce4e51f8c4ee25ad966c214ed6ed8a6858",
                "a", BLUE_A, 1L);
        ComponentSnapshot componentB = acyclicComponent(
                "sha256:1cabaeee5d923ae59beee6f049ee2a69fefdd44ae4752428f184d3fb4496f058",
                "sha256:23c1237df89ee70ad0257df724270fc614a74964a3254935de319f553ec77e29",
                "b", BLUE_B, 1L);
        AffectedClosureSnapshot snapshot = new AffectedClosureSnapshot(
                "sha256:ed4ac428e31d3960b96bb4d26d816e77247942b7438404a79fb27cba3d67726d",
                1L,
                Arrays.asList(
                        new ManagedDocumentSnapshot(
                                id("a"), BLUE_A, new Node().name("a"),
                                true, false, true, 0L, 1L),
                        new ManagedDocumentSnapshot(
                                id("b"), BLUE_B, new Node().name("b"),
                                true, false, false, 0L, 1L)),
                Arrays.asList(bToA, aToB),
                "sha256:bf684fafcc3901aedb2c6f7154b15a1cfa36958dbc8d479113317fd5cd0588ee",
                Arrays.asList(componentA, componentB),
                Collections.singletonList(id("a")));
        Node event = new Node().properties(
                "type", new Node().blueId(
                        "5KUZWsqRuW7SyRj1oCK7hRTmJKVCHTiVJboxy4nas8KX"),
                "subscriptionKey", new Node().value("fixture"),
                "id", new Node().value("START-FINITE"));
        String eventBlueId = DirectBlueIdCalculator.calculateBlueId(event);
        assertEquals(
                "4za3n8bAtn5YGRqAEeW6iRswtRnLgGkyZg9EN64gwLy2",
                eventBlueId);
        ExternalEventCause cause = new ExternalEventCause(
                "sha256:08502d369b6bfee172fd72c48637ce4a241fa88348024904e6113f333d3cd57b",
                event,
                eventBlueId,
                ExternalOrderKey.of(Arrays.asList(
                        BigInteger.valueOf(100L), "timeline-a",
                        BigInteger.ONE)),
                EXTERNAL_ORDER_POLICY);
        DirectLogicalDelivery delivery = new DirectLogicalDelivery(
                ManagedScopeKey.root(id("a")), "source", "start", 0L);
        ExecutionPolicy policy = new ExecutionPolicy(
                "sha256:06be3c4e41fbbc52cf8289ff95cc52264b2f5093d75c590501173942490c1685",
                100000L,
                Collections.<DocumentId, Long>emptyMap(),
                "release-default");
        ClosureEnvironment environment = releasedEnvironment();
        ClosureInvocationInput provisional =
                ClosureInvocationInput.processClosure(
                        hash('0'),
                        snapshot,
                        cause,
                        Collections.singletonList(delivery),
                        "sha256:c8047b98a89fd94bc32a8f546c7d623d59254615962e60bf562f6d79c8eee439",
                        policy,
                        environment);
        return ClosureInvocationInput.processClosure(
                IDENTITIES.invocationIdentity(provisional),
                snapshot,
                cause,
                Collections.singletonList(delivery),
                "sha256:c8047b98a89fd94bc32a8f546c7d623d59254615962e60bf562f6d79c8eee439",
                policy,
                environment);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> frozenCclo34Envelope() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(DeserializationFeature.USE_LONG_FOR_INTS);
            return mapper.readValue(C_CLO_34_CANONICAL_INVOCATION_ENVELOPE,
                    LinkedHashMap.class);
        } catch (Exception exception) {
            throw new AssertionError(
                    "Unable to parse frozen C-CLO-34 invocation envelope",
                    exception);
        }
    }

    private static byte[] independentlyCanonicalize(Object value) {
        try {
            return new JsonCanonicalizer(
                    new ObjectMapper().writeValueAsString(value))
                    .getEncodedUTF8();
        } catch (Exception exception) {
            throw new AssertionError(
                    "Unable to independently canonicalize C-CLO-34", exception);
        }
    }

    private static String independentSha256Identity(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                int unsigned = item & 0xff;
                hex.append(Character.forDigit(unsigned >>> 4, 16));
                hex.append(Character.forDigit(unsigned & 0x0f, 16));
            }
            return "sha256:" + hex;
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is unavailable", exception);
        }
    }

    private static ClosureInvocationInput exactSimpleProcessInput(
            String invocationIdentity) {
        return exactSimpleProcessInput(
                invocationIdentity, environment(BINDING_POLICY));
    }

    private static ClosureInvocationInput exactSimpleProcessInput(
            String invocationIdentity,
            ClosureEnvironment environment) {
        SimpleState state = simpleState();
        Node event = new Node().properties(
                "kind", new Node().value("external"));
        String eventBlueId = DirectBlueIdCalculator.calculateBlueId(event);
        String causeIdentity = IDENTITIES.externalCauseIdentity(
                eventBlueId,
                ExternalOrderKey.of(Collections.singletonList(
                        BigInteger.ONE)),
                EXTERNAL_ORDER_POLICY);
        ExternalEventCause cause = new ExternalEventCause(
                causeIdentity,
                event,
                eventBlueId,
                ExternalOrderKey.of(Collections.singletonList(
                        BigInteger.ONE)),
                EXTERNAL_ORDER_POLICY);
        return ClosureInvocationInput.processClosure(
                invocationIdentity,
                state.snapshot,
                cause,
                Collections.<DirectLogicalDelivery>emptyList(),
                IDENTITIES.directDeliverySnapshotIdentity(
                        Collections.<DirectLogicalDelivery>emptyList()),
                state.policy,
                environment);
    }

    private static ClosureInvocationInput exactSimpleAdmissionInput(
            String invocationIdentity,
            AdmissionCandidate candidate,
            String candidateIdentity) {
        SimpleState state = simpleState();
        String admissionPolicy = hash('a');
        String causeIdentity = IDENTITIES.admissionCauseIdentity(
                AdmissionKind.TOP_LEVEL_ADMISSION,
                "test-admission",
                null,
                null,
                admissionPolicy);
        AdmissionCause cause = new AdmissionCause(
                causeIdentity,
                AdmissionKind.TOP_LEVEL_ADMISSION,
                "test-admission",
                null,
                null,
                admissionPolicy);
        return ClosureInvocationInput.admitClosure(
                invocationIdentity,
                state.snapshot,
                cause,
                candidate,
                candidateIdentity,
                IDENTITIES.directDeliverySnapshotIdentity(
                        Collections.<DirectLogicalDelivery>emptyList()),
                state.policy,
                environment(BINDING_POLICY));
    }

    private static SimpleState simpleState() {
        DocumentId documentId = id("root");
        Node document = new Node().properties(
                "value", new Node().value("exact"));
        String blueId = DirectBlueIdCalculator.calculateBlueId(document);
        String componentIdentity = IDENTITIES.componentIdentity(
                ComponentKind.ACYCLIC,
                0L,
                Collections.singletonList(documentId));
        ComponentSnapshot provisionalComponent = new ComponentSnapshot(
                componentIdentity,
                hash('0'),
                0L,
                ComponentKind.ACYCLIC,
                Collections.singletonList(documentId),
                Collections.singletonList(blueId),
                null,
                null,
                null);
        String componentStateIdentity =
                IDENTITIES.componentStateIdentity(provisionalComponent);
        ComponentSnapshot component = new ComponentSnapshot(
                componentIdentity,
                componentStateIdentity,
                0L,
                ComponentKind.ACYCLIC,
                Collections.singletonList(documentId),
                Collections.singletonList(blueId),
                null,
                null,
                null);
        ManagedDocumentSnapshot managed = new ManagedDocumentSnapshot(
                documentId,
                blueId,
                document,
                false,
                false,
                true,
                0L,
                0L);
        String bindingSetIdentity = IDENTITIES.occurrenceBindingSetIdentity(
                Collections.<ManagedOccurrenceBinding>emptyList());
        AffectedClosureSnapshot provisionalSnapshot =
                new AffectedClosureSnapshot(
                        hash('1'),
                        0L,
                        Collections.singletonList(managed),
                        Collections.<ManagedOccurrenceBinding>emptyList(),
                        bindingSetIdentity,
                        Collections.singletonList(component),
                        Collections.singletonList(documentId));
        AffectedClosureSnapshot snapshot = new AffectedClosureSnapshot(
                IDENTITIES.affectedClosureIdentity(provisionalSnapshot),
                0L,
                Collections.singletonList(managed),
                Collections.<ManagedOccurrenceBinding>emptyList(),
                bindingSetIdentity,
                Collections.singletonList(component),
                Collections.singletonList(documentId));
        ExecutionPolicy provisionalPolicy = new ExecutionPolicy(
                hash('2'),
                1000L,
                Collections.<DocumentId, Long>emptyMap(),
                "test-policy");
        ExecutionPolicy policy = new ExecutionPolicy(
                IDENTITIES.executionPolicyIdentity(provisionalPolicy),
                1000L,
                Collections.<DocumentId, Long>emptyMap(),
                "test-policy");
        return new SimpleState(snapshot, policy);
    }

    private static ClosureEnvironment releasedEnvironment() {
        return new ClosureEnvironment(
                "sha256:01b038b64e3f0a9a11f3f70d544a63ff78a01d5169f1a03f8b8629cf73645a7d",
                "sha256:dfb444962a5a17b3a6519e8d148c2bf4a975a921b1fcb1277710052caaecd930",
                "sha256:46a7744c1cbfa4b00e1d8a99f6ca3f0089ef697de968fee08547894ab02b0ca1",
                "sha256:54310113bbfc0c6529802fa134a40d7131a4c72e52ccd11d16b20733db60bad8",
                labeled(
                        "sha256:44ed2def49e63a24f3b818cf3d2854a3ceb8b40f679bf5b77a0be52b538258ac",
                        "nfc-document-lineage-v1"),
                labeled(BINDING_POLICY, "exact-document-lineage"),
                labeled(
                        "sha256:3cf3044ce503a6a8d6924c4739188b2780c74eaa2f74b3f0fd3247b3d3c0d3ec",
                        "fixture-exact-node-provider-v1"),
                labeled(EXTERNAL_ORDER_POLICY,
                        "canonical-source-order-v1"),
                portable(
                        "sha256:fcd17a9a327082d9a563c59be1089a7f4ee09275d74915b2c84563be42c575a5",
                        "blue-contracts-1.0-portable-limits",
                        Collections.<String, Long>emptyMap()),
                ClosureRuntimeDescriptor.CYCLIC_FINALIZER_IDENTITY,
                ClosureRuntimeDescriptor.CYCLIC_PROOF_VERIFIER_IDENTITY);
    }

    private static ClosureEnvironment environment(String bindingPolicy) {
        String managedDocumentLabel = "test-document-policy";
        String bindingLabel = "exact-document-lineage";
        String providerLabel = "test-provider-domain";
        String orderLabel = "canonical-source-order-v1";
        ClosureEnvironment.PortableLimitPolicyEvidence provisionalLimits =
                portable(hash('0'), "test-portable-limits",
                        Collections.<String, Long>emptyMap());
        return new ClosureEnvironment(
                hash('1'), hash('2'), hash('3'), hash('4'),
                labeled(IDENTITIES.labeledIdentity(
                                ClosureIdentityService.Constructor
                                        .MANAGED_DOCUMENT_IDENTITY_POLICY,
                                managedDocumentLabel),
                        managedDocumentLabel),
                labeled(bindingPolicy, bindingLabel),
                labeled(IDENTITIES.labeledIdentity(
                                ClosureIdentityService.Constructor
                                        .EXACT_NODE_PROVIDER_DOMAIN,
                                providerLabel),
                        providerLabel),
                labeled(EXTERNAL_ORDER_POLICY, orderLabel),
                portable(IDENTITIES.portableLimitPolicyIdentity(
                                provisionalLimits),
                        provisionalLimits.label(),
                        provisionalLimits.limits()),
                hash('8'), hash('9'));
    }

    private static ClosureEnvironment.LabeledIdentityEvidence labeled(
            String identity, String label) {
        return new ClosureEnvironment.LabeledIdentityEvidence(identity, label);
    }

    private static ClosureEnvironment.PortableLimitPolicyEvidence portable(
            String identity,
            String label,
            java.util.Map<String, Long> limits) {
        return new ClosureEnvironment.PortableLimitPolicyEvidence(
                identity, label, limits);
    }

    private static AdmissionCandidate.CandidateMemberState memberState(
            String documentId, String blueId) {
        return new AdmissionCandidate.CandidateMemberState(
                id(documentId), blueId);
    }

    private static Node simpleAPlaceholder() {
        return new Node().properties(
                "documentId", new Node().blueId(
                        "3fbe7KHmQAtqGDkqzPrPkfhJCD1nMFXJa9ckCUZxxxNR"),
                "memberIdentity", new Node().blueId(
                        "3fbe7KHmQAtqGDkqzPrPkfhJCD1nMFXJa9ckCUZxxxNR"),
                "b", new Node().blueId("this#1"),
                "contracts", new Node().blueId(
                        "AKdg7JuRiCbPdRARLfWhCoSFQz4htgjc2pcDPWkNPfQJ"));
    }

    private static Node simpleBPlaceholder() {
        return new Node().properties(
                "documentId", new Node().blueId(
                        "8i8RsDeMbU4U3nudF7pWdTH1imR2aRenUqnXb6xAWu3a"),
                "memberIdentity", new Node().blueId(
                        "8i8RsDeMbU4U3nudF7pWdTH1imR2aRenUqnXb6xAWu3a"),
                "a", new Node().blueId("this#0"),
                "contracts", new Node().blueId(
                        "F4GdSvomgpBDpomh3VuFEg3L6yu2gLsBQeCEyGmYpeuz"));
    }

    private static Node ambiguousMember(String reference) {
        return new Node().properties(
                "same", new Node().value(Boolean.TRUE),
                "other", new Node().blueId(reference));
    }

    private static ManagedOccurrenceBinding binding(
            String occurrenceIdentity,
            String bindingIdentity,
            String source,
            String path,
            String target,
            String expectedBlueId,
            boolean active) {
        return new ManagedOccurrenceBinding(
                occurrenceIdentity,
                bindingIdentity,
                BINDING_POLICY,
                id(source),
                ScopeAddress.embedded(path, 1L),
                id(target),
                expectedBlueId,
                active,
                null);
    }

    private static ComponentSnapshot acyclicComponent(
            String componentIdentity,
            String componentStateIdentity,
            String documentId,
            String blueId,
            long generation) {
        return new ComponentSnapshot(
                componentIdentity,
                componentStateIdentity,
                generation,
                ComponentKind.ACYCLIC,
                Collections.singletonList(id(documentId)),
                Collections.singletonList(blueId),
                null,
                null,
                null);
    }

    private static DocumentId id(String value) {
        return new DocumentId(value);
    }

    private static String hash(char value) {
        char[] digits = new char[64];
        Arrays.fill(digits, value);
        return "sha256:" + new String(digits);
    }

    private static final class SimpleState {
        private final AffectedClosureSnapshot snapshot;
        private final ExecutionPolicy policy;

        private SimpleState(
                AffectedClosureSnapshot snapshot,
                ExecutionPolicy policy) {
            this.snapshot = snapshot;
            this.policy = policy;
        }
    }
}
