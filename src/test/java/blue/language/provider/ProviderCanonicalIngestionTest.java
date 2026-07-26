package blue.language.provider;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

class ProviderCanonicalIngestionTest {

    @Test
    void rejectsInvalidConstraintsKey() {
        String invalidConstraintsDoc = "name: Invalid Constraints\n" +
                "constraints:\n" +
                "  minLength: 2";

        BasicNodeProvider provider = new BasicNodeProvider();
        assertThrows(RuntimeException.class, () -> YAML_MAPPER.readValue(invalidConstraintsDoc, blue.language.model.Node.class));
        assertThrows(RuntimeException.class, () -> provider.addSingleDocs(invalidConstraintsDoc));
    }

    @Test
    void providerContentWithWrongBlueIdFailsTypeResolution() {
        String requestedBlueId = BlueIdCalculator.calculateBlueId(new Node().value("expected"));
        Blue blue = new Blue(blueId -> Collections.singletonList(new Node().value("actual")));

        assertThrows(IllegalArgumentException.class,
                () -> blue.resolve(new Node().type(new Node().blueId(requestedBlueId))));
    }

    @Test
    void providerMissingContentFailsDeterministically() {
        String requestedBlueId = BlueIdCalculator.calculateBlueId(new Node().value("missing"));
        Blue blue = new Blue(blueId -> Collections.emptyList());

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> blue.resolve(new Node().type(new Node().blueId(requestedBlueId))));
        assertNotNull(error.getMessage());
    }

    @Test
    void providerRejectsInvalidBlueIdBeforeFetch() {
        AtomicBoolean fetched = new AtomicBoolean(false);
        VerifyingNodeProvider provider = new VerifyingNodeProvider(blueId -> {
            fetched.set(true);
            return Collections.singletonList(new Node().value("x"));
        });

        assertThrows(IllegalArgumentException.class, () -> provider.fetchByBlueId("not-a-real-blueid"));
        assertFalse(fetched.get());
    }

    @Test
    void providerDoesNotSkipVerificationWhenContentReferencesRequestedBlueId() {
        String requestedBlueId = BlueIdCalculator.calculateBlueId(new Node().value("expected"));
        VerifyingNodeProvider provider = new VerifyingNodeProvider(blueId -> Collections.singletonList(
                new Node().properties(
                        "self", new Node().blueId(requestedBlueId),
                        "actual", new Node().value("actual"))));

        assertThrows(IllegalArgumentException.class, () -> provider.fetchByBlueId(requestedBlueId));
    }

    @Test
    void providerPlainIdDoesNotUseCyclicRewriteFallback() {
        String requestedBlueId = BlueIdCalculator.calculateBlueIdAllowingCyclicPlaceholders(
                new Node().properties("self", new Node().blueId(NodeContentHandler.ZERO_BLUE_ID)));
        VerifyingNodeProvider provider = new VerifyingNodeProvider(blueId -> Collections.singletonList(
                new Node().properties("self", new Node().blueId(requestedBlueId))));

        assertThrows(IllegalArgumentException.class, () -> provider.fetchByBlueId(requestedBlueId));
    }

    @Test
    void providerDoesNotBypassPlainVerificationForCyclicAwareDelegate() {
        String requestedBlueId = BlueIdCalculator.calculateBlueId(new Node().value("expected"));
        VerifyingNodeProvider provider = new VerifyingNodeProvider(new CyclicAwareWrongContentProvider(requestedBlueId));

        assertThrows(IllegalArgumentException.class, () -> provider.fetchByBlueId(requestedBlueId));
    }

    @Test
    void providerCyclicMemberFetchRequiresCyclicAwareVerificationOrFailsExplicitly() {
        String baseBlueId = BlueIdCalculator.calculateBlueId(new Node().value("base"));
        VerifyingNodeProvider provider = new VerifyingNodeProvider(blueId -> {
            if ((baseBlueId + "#0").equals(blueId)) {
                return Collections.singletonList(new Node().value("member"));
            }
            return null;
        });

        assertThrows(IllegalArgumentException.class,
                () -> provider.fetchByBlueId(baseBlueId + "#0"));
    }

    @Test
    void providerCyclicMemberDoesNotUsePartialBaseSetVerification() {
        BasicNodeProvider baseProvider = new BasicNodeProvider(YAML_MAPPER.readValue(
                "- name: A\n" +
                "  next:\n" +
                "    type:\n" +
                "      blueId: this#1\n" +
                "- name: B\n" +
                "  next:\n" +
                "    type:\n" +
                "      blueId: this#0", Node.class));
        String aBlueId = baseProvider.getBlueIdByName("A");
        String baseBlueId = aBlueId.substring(0, aBlueId.indexOf('#'));
        List<Node> baseNodes = baseProvider.fetchByBlueId(baseBlueId);

        VerifyingNodeProvider provider = new VerifyingNodeProvider(blueId -> {
            if (baseBlueId.equals(blueId)) {
                return baseNodes;
            }
            if ((baseBlueId + "#0").equals(blueId)) {
                return Collections.singletonList(baseNodes.get(1));
            }
            return null;
        });

        assertThrows(IllegalArgumentException.class,
                () -> provider.fetchByBlueId(baseBlueId + "#0"));
    }

    private static final class CyclicAwareWrongContentProvider implements blue.language.NodeProvider, CyclicAwareNodeProvider {
        private final String claimedBlueId;

        private CyclicAwareWrongContentProvider(String claimedBlueId) {
            this.claimedBlueId = claimedBlueId;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            return Collections.singletonList(new Node().value("actual"));
        }

        @Override
        public boolean hasVerifiedContentForBlueId(String blueId) {
            return claimedBlueId.equals(blueId);
        }
    }
}
