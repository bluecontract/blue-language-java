package blue.language.processor;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.api.BlueCachePolicy;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.runtime.BlueLanguageRuntime;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class LanguageRuntimeAccessContractIntegrationTest {

    @Test
    void shouldUseFocusedRuntimeForCheckpointSourceIdentity() {
        // given
        Node source = YAML_MAPPER.readValue(
                "blue:\n"
                        + "  imports:\n"
                        + "    TextAlias:\n"
                        + "      blueId: " + TEXT_TYPE_BLUE_ID + "\n"
                        + "type: TextAlias\n"
                        + "value: hello",
                Node.class);
        BlueLanguageRuntime runtime = BlueLanguageRuntime.create(
                blueId -> null,
                BlueCachePolicy.boundedDefaults(),
                Collections.emptyMap());

        // when
        String expected = runtime.identity()
                .sourceDocumentBlueId(source);
        String actual = CheckpointIdentityCalculator.identity(
                source, runtime);

        // then
        try {
            assertEquals(expected, actual);
        } finally {
            runtime.close();
        }
    }

    @Test
    void shouldUseFocusedSnapshotsWithoutOwningInheritedRuntime() {
        // given
        Node externalType = new Node().name("External type");
        String externalTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(externalType);
        NodeProvider provider = blueId -> externalTypeBlueId.equals(blueId)
                ? Collections.singletonList(externalType)
                : null;
        BlueLanguageRuntime inheritedRuntime = BlueLanguageRuntime.create(
                provider,
                BlueCachePolicy.boundedDefaults(),
                Collections.emptyMap());
        RegisteredContractScopeIdentitySnapshotManager manager =
                new RegisteredContractScopeIdentitySnapshotManager(
                        ContractProcessorRegistryBuilder.create()
                                .registerDefaults()
                                .build(),
                        inheritedRuntime);
        Node document = new Node()
                .type(new Node().blueId(externalTypeBlueId));

        // when
        ResolvedSnapshot snapshot = manager.fromDocument(document);
        FrozenNode exact = manager.materializeVerifiedExactReference(
                FrozenNode.fromNode(
                        new Node().blueId(externalTypeBlueId)));
        manager.releaseTransientState();

        // then
        try {
            assertEquals(externalTypeBlueId, exact.blueId());
            assertEquals(externalTypeBlueId,
                    snapshot.frozenResolvedRoot()
                            .getType()
                            .getReferenceBlueId());
            assertFalse(inheritedRuntime.isClosed());
        } finally {
            inheritedRuntime.close();
        }
    }

    @Test
    void shouldPreserveInheritedEnvironmentImportsForTypeIdentity() {
        // given
        Node parent = new Node().name("Host-provided parent type");
        String parentBlueId = DirectBlueIdCalculator.calculateBlueId(parent);
        NodeProvider provider = blueId -> parentBlueId.equals(blueId)
                ? Collections.singletonList(parent.clone())
                : null;
        BlueLanguageRuntime inheritedRuntime = BlueLanguageRuntime.create(
                provider,
                BlueCachePolicy.boundedDefaults(),
                Collections.emptyMap(),
                Collections.singletonMap("HostParent", parentBlueId));
        RegisteredContractScopeIdentitySnapshotManager manager =
                new RegisteredContractScopeIdentitySnapshotManager(
                        ContractProcessorRegistryBuilder.create()
                                .registerDefaults()
                                .build(),
                        inheritedRuntime);
        Node child = YAML_MAPPER.readValue(
                "name: Host child\n"
                        + "type: HostParent\n",
                Node.class);

        try {
            // when
            CanonicalTypeIdentityEvidence expected = inheritedRuntime
                    .resolveTypeDeclarationIdentity(child);
            CanonicalTypeIdentityEvidence actual = manager
                    .resolveTypeDeclarationIdentity(child);

            // then
            assertEquals(expected.blueId(), actual.blueId());
            assertEquals(
                    parentBlueId,
                    actual.canonicalTypeIdentityInput()
                            .getType()
                            .getBlueId());
        } finally {
            manager.releaseTransientState();
            inheritedRuntime.close();
        }
    }
}
