package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.BlueContracts;
import blue.language.processor.ClosureRuntimeDescriptor;
import blue.language.processor.ExternalOrderKey;
import blue.language.provider.CyclicSetProof;
import blue.language.runtime.BlueLanguage;
import blue.language.snapshot.FrozenNode;
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
            "8BEDGRScD4UFo3By5zVDqhhHHurAPbpraVY6C2PRSZgZ";
    private static final String BLUE_B =
            "BTEtRFRthnZRSw1chg6iwhxXeu32rcwe6te8aJB4cbE6";
    private static final String C_CLO_34_INVOCATION_IDENTITY =
            "sha256:d47c4c728d36948b3a02e00f37a6f8ab3f7ff844913f7e35b6b0a5618407787f";
    private static final String C_CLO_34_CANONICAL_INVOCATION_ENVELOPE =
            "{\"domain\":\"blue-contracts-invocation/1.0\",\"value\":{\"admissionCandidateIdentity\":null,\"blue"
                    + "LanguageSpecificationIdentity\":\"sha256:77b48506ff7b5ddbab26b98ce9e060e943cb3babf1e6f551108"
                    + "5bbb3c31c4144\",\"causeIdentity\":\"sha256:08502d369b6bfee172fd72c48637ce4a241fa88348024904e61"
                    + "13f333d3cd57b\",\"contractsSpecificationIdentity\":\"sha256:0d7496790fb87d4589628c81fa8ca5e72b"
                    + "7d955458e20bc393f7837115ecb3b7\",\"cyclicFinalizerIdentity\":\"sha256:0ea9ccf1f8da23be8f703685"
                    + "65c3322c1d25aa88ba711fa06456aed2a842942c\",\"cyclicProofVerifierIdentity\":\"sha256:581619ff2a"
                    + "6909590c8740887d80d0d24659673e5af2de713a181c6664709c84\",\"directDeliverySnapshotIdentity\":\""
                    + "sha256:c8047b98a89fd94bc32a8f546c7d623d59254615962e60bf562f6d79c8eee439\",\"documents\":[{\"bl"
                    + "ueId\":\"8BEDGRScD4UFo3By5zVDqhhHHurAPbpraVY6C2PRSZgZ\",\"componentGeneration\":1,\"documentId\":"
                    + "\"a\",\"epoch\":0,\"initialized\":true,\"publicRoot\":true,\"terminated\":false},{\"blueId\":\"BTEtRFRt"
                    + "hnZRSw1chg6iwhxXeu32rcwe6te8aJB4cbE6\",\"componentGeneration\":1,\"documentId\":\"b\",\"epoch\":0,\""
                    + "initialized\":true,\"publicRoot\":false,\"terminated\":false}],\"exactNodeProviderDomainIdentity"
                    + "\":\"sha256:3cf3044ce503a6a8d6924c4739188b2780c74eaa2f74b3f0fd3247b3d3c0d3ec\",\"externalOrder"
                    + "PolicyIdentity\":\"sha256:2d8d984a7c93db9aa076dbfdeb6617161b91c224db6a1366d3636462b762a0f9\","
                    + "\"gasManifestIdentity\":\"sha256:54310113bbfc0c6529802fa134a40d7131a4c72e52ccd11d16b20733db60"
                    + "bad8\",\"gasPolicyIdentity\":\"sha256:06be3c4e41fbbc52cf8289ff95cc52264b2f5093d75c590501173942"
                    + "490c1685\",\"inputClosureIdentity\":\"sha256:c65ef11c664373426353af741b2ccd5f2297bcc522d91a98e"
                    + "6abcfe0e5883db2\",\"inputGraphGeneration\":1,\"managedBindingPolicyIdentity\":\"sha256:c1e8d8804"
                    + "99cbafc595e1fb213ee73acc6ddb8d1d9850c7ddff2224c88a03d35\",\"managedDocumentIdentityPolicyIde"
                    + "ntity\":\"sha256:44ed2def49e63a24f3b818cf3d2854a3ceb8b40f679bf5b77a0be52b538258ac\",\"occurren"
                    + "ceBindingSetIdentity\":\"sha256:c8f3bd1bbefdc74425c9648e1db070982cd02021f7c8f5239142e0c3d882"
                    + "4f72\",\"operation\":\"process-closure\",\"portableLimitPolicyIdentity\":\"sha256:fcd17a9a327082d9"
                    + "a563c59be1089a7f4ee09275d74915b2c84563be42c575a5\",\"runtimeRegistryIdentity\":\"sha256:1442c9"
                    + "0ed0b2601b7293cd3c21938a86907d217336b69e4674adabbf3253e9a4\"}}";

    @Test
    void shouldMatchReleasedCclo14Cclo15AndCclo29CandidateIdentities() {
        ComponentSnapshot authoritativeCyclic = new ComponentSnapshot(
                "sha256:b27694341619d5d0835f99889e08eed563c00a8124aaf5945b115826c8b7e4b9",
                "sha256:cdfd54d28552cfc08c9bcf5633a2a2e1f06709b2564b8f87e2163c6d79ee8c19",
                1L,
                ComponentKind.CYCLIC,
                Arrays.asList(id("simple-a"), id("simple-b")),
                Arrays.asList(
                        "9J59XHipxtMwoMTY7r7HaicepUCtDPkMPFMAzLfdp4iW#0",
                        "9J59XHipxtMwoMTY7r7HaicepUCtDPkMPFMAzLfdp4iW#1"),
                "9J59XHipxtMwoMTY7r7HaicepUCtDPkMPFMAzLfdp4iW",
                CyclicSetProof.fromDeclaredPlaceholderSet(Arrays.asList(
                        simpleAPlaceholder(), simpleBPlaceholder())),
                "sha256:22090c938702e4d83792a7534ab991825463a5fadb19ef4fe6bcb7d000e0824c");
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
                                        "9J59XHipxtMwoMTY7r7HaicepUCtDPkMPFMAzLfdp4iW#0"),
                                memberState("simple-b",
                                        "9J59XHipxtMwoMTY7r7HaicepUCtDPkMPFMAzLfdp4iW#1")),
                        Arrays.asList(
                                simpleAPlaceholder(),
                                simpleBPlaceholder())));
        assertEquals(
                "sha256:b5f4f42d492de95b8e6a3d026bd240278240ae15aa18a472390390e759295669",
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
                                        "sha256:e1a055c184d22ac4acdde2716af5fbd42ec3ca6a524168577617e0d7c80bb361",
                                        BINDING_POLICY,
                                        id("simple-a"),
                                        "/missing",
                                        1L,
                                        id("simple-b"),
                                        "9J59XHipxtMwoMTY7r7HaicepUCtDPkMPFMAzLfdp4iW#1",
                                        true)));
        assertEquals(
                "sha256:d1d5cc1cd4d2874a41f0a9e166c00019bac93505ae103c3badcedadc4e78ef09",
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
                verifyInvocation(input);
        assertEquals(invocationIdentity, verification.invocationIdentity());
        assertEquals(
                ClosureInvocationVerifier.CandidateDisposition.NOT_SUBMITTED,
                verification.candidateDisposition());

        ClosureInvocationInput wrong = exactSimpleProcessInput(hash('f'));
        assertThrows(IllegalArgumentException.class,
                () -> verifyInvocation(wrong));
    }

    @Test
    void shouldRejectCyclicMemberAtTopLevelExternalBoundary() {
        String eventBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("cyclic external set")) + "#0";
        ClosureInvocationInput referenceInput = exactExternalProcessInput(
                new Node().blueId(eventBlueId), eventBlueId);
        ClosureInvocationInput resolvedInput = exactExternalProcessInput(
                new Node().properties(
                        "kind", new Node().value("resolved cyclic member")),
                eventBlueId);

        assertThrows(IllegalArgumentException.class,
                () -> verifyInvocation(referenceInput));
        assertThrows(IllegalArgumentException.class,
                () -> verifyInvocation(resolvedInput));
    }

    @Test
    void shouldPreservePlainExactReferenceExternalIdentity() {
        String eventBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().properties(
                        "kind", new Node().value("referenced external")));
        Node reference = new Node().blueId(eventBlueId);
        ClosureInvocationInput input = exactExternalProcessInput(
                reference, eventBlueId);

        ClosureInvocationVerifier.Verification verification =
                verifyInvocation(input);

        assertEquals(eventBlueId,
                verification.externalEventIdentityEvidence().eventBlueId());
        assertEquals(
                FrozenNode.fromResolvedNode(reference)
                        .resolvedStructuralKey(),
                verification.externalEventIdentityEvidence()
                        .frozenEvent().resolvedStructuralKey());
    }

    @Test
    void shouldUseSourceCanonicalIdentityForInlineExternalEvent() {
        Node event = new Node()
                .type(new Node().name("Inline External Event Type"))
                .properties("value", new Node().value("exact"));
        try (BlueLanguage language = BlueLanguage.builder().build();
             BlueContracts contracts = BlueContracts.builder(
                     language.processing()).build()) {
            String eventBlueId = language.identity()
                    .sourceDocumentBlueId(event.clone());
            ClosureInvocationInput input = exactExternalProcessInput(
                    event, eventBlueId);

            ClosureInvocationVerifier.Verification verification =
                    ClosureInvocationVerifier.verify(
                            input,
                            contracts::runtimeAccess);
            assertEquals(input.invocationIdentity(),
                    verification.invocationIdentity());
            assertEquals(eventBlueId,
                    verification.externalEventIdentityEvidence()
                            .eventBlueId());
            assertEquals(
                    FrozenNode.fromResolvedNode(event)
                            .resolvedStructuralKey(),
                    verification.externalEventIdentityEvidence()
                            .frozenEvent().resolvedStructuralKey());

            ClosureInvocationInput mismatchedInput = exactExternalProcessInput(
                    event,
                    DirectBlueIdCalculator.calculateBlueId(
                            new Node().value("different event")));
            assertThrows(IllegalArgumentException.class,
                    () -> ClosureInvocationVerifier.verify(
                            mismatchedInput,
                            contracts::runtimeAccess));
        }
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
                () -> verifyInvocation(input));
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
                verifyInvocation(input)
                        .candidateDisposition());
        assertEquals(
                AdmissionCandidate.Kind.AMBIGUOUS_PRELIMINARY_MEMBERS,
                verifyInvocation(input).candidateKind());

        assertThrows(IllegalArgumentException.class, () ->
                AdmissionCandidate.ambiguousPreliminaryMembers(Arrays.asList(
                        new AdmissionCandidate.CandidateCyclicMember(
                                id("duplicate"), ambiguousMember("this#1")),
                        new AdmissionCandidate.CandidateCyclicMember(
                                id("duplicate"), ambiguousMember("this#0")))));

        ClosureInvocationInput wrongCandidateIdentity =
                exactSimpleAdmissionInput(hash('1'), candidate, hash('2'));
        assertThrows(IllegalArgumentException.class, () ->
                verifyInvocation(wrongCandidateIdentity));
    }

    private static ClosureInvocationInput cclo34Input() {
        ManagedOccurrenceBinding bToA = binding(
                "sha256:f1e82f9a16ec41c5b9d4c5d37d0e412f4c0cdf05a639cc507aedfee2e8d3f232",
                "sha256:5eacd13e1e98a75f80713bce3c4168ec69abe257bf27f938b8b582232018a697",
                "b", "/a", "a", BLUE_A, true);
        ManagedOccurrenceBinding aToB = binding(
                "sha256:f5d1cd1ca17ac4fa6547d53f85dadb18f4b37e1bca42588f5cb4fb9090023eca",
                "sha256:738a6433193982338d641f8759dae92bbb5b4b9093bc8b30d42e296baa2e9be6",
                "a", "/b", "b", BLUE_B, false);
        ComponentSnapshot componentA = acyclicComponent(
                "sha256:7f65ed079664f9e8c2c38b1294f739ad105bc718dd96d98ec67e0a91cd4cd9d7",
                "sha256:e166f34649e7ff062265e74822aa892763dfbaa5a9e30915fb3f2d1491234a5e",
                "a", BLUE_A, 1L);
        ComponentSnapshot componentB = acyclicComponent(
                "sha256:1cabaeee5d923ae59beee6f049ee2a69fefdd44ae4752428f184d3fb4496f058",
                "sha256:871712178cfc249c05203b57c92b8d696a54a2c4e9a8591621ba324a76ca1f85",
                "b", BLUE_B, 1L);
        AffectedClosureSnapshot snapshot = new AffectedClosureSnapshot(
                "sha256:c65ef11c664373426353af741b2ccd5f2297bcc522d91a98e6abcfe0e5883db2",
                1L,
                Arrays.asList(
                        new ManagedDocumentSnapshot(
                                id("a"), BLUE_A, new Node().name("a"),
                                true, false, true, 0L, 1L),
                        new ManagedDocumentSnapshot(
                                id("b"), BLUE_B, new Node().name("b"),
                                true, false, false, 0L, 1L)),
                Arrays.asList(bToA, aToB),
                "sha256:c8f3bd1bbefdc74425c9648e1db070982cd02021f7c8f5239142e0c3d8824f72",
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

    private static ClosureInvocationInput exactExternalProcessInput(
            Node event,
            String eventBlueId) {
        SimpleState state = simpleState();
        ClosureEnvironment environment = environment(BINDING_POLICY);
        ExternalEventCause cause = ClosureEvidenceFactory.externalCause(
                event,
                eventBlueId,
                ExternalOrderKey.of(Collections.singletonList(
                        BigInteger.ONE)),
                EXTERNAL_ORDER_POLICY);
        return ClosureEvidenceFactory.processClosure(
                state.snapshot,
                cause,
                Collections.<DirectLogicalDelivery>emptyList(),
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
                "sha256:77b48506ff7b5ddbab26b98ce9e060e943cb3babf1e6f5511085bbb3c31c4144",
                "sha256:0d7496790fb87d4589628c81fa8ca5e72b7d955458e20bc393f7837115ecb3b7",
                "sha256:1442c90ed0b2601b7293cd3c21938a86907d217336b69e4674adabbf3253e9a4",
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
                        "Fiod9ArSxfZhdRe3rC5dqM78x2CBfW3zdzSbaY9a6ujg"));
    }

    private static Node simpleBPlaceholder() {
        return new Node().properties(
                "documentId", new Node().blueId(
                        "8i8RsDeMbU4U3nudF7pWdTH1imR2aRenUqnXb6xAWu3a"),
                "memberIdentity", new Node().blueId(
                        "8i8RsDeMbU4U3nudF7pWdTH1imR2aRenUqnXb6xAWu3a"),
                "a", new Node().blueId("this#0"),
                "contracts", new Node().blueId(
                        "fyDiUQNFeL6UYVyezYDUYSbHfPX6gZCTQwsJudgNGq9"));
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

    private static ClosureInvocationVerifier.Verification verifyInvocation(
            ClosureInvocationInput input) {
        return ClosureInvocationVerifier.verify(input, null);
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
