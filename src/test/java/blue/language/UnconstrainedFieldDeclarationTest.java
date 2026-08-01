package blue.language;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueLanguageRuntime;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
import blue.language.api.WeightedLruCache;
import blue.language.provider.NodeProvider;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.model.wire.BlueLanguageConstants.DICTIONARY_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Characterizes declarations with no type as unconstrained Blue fields rather
 * than as an implicit Dictionary or a separate Any type.
 */
final class UnconstrainedFieldDeclarationTest {

    @Test
    void shouldAcceptScalarForUnconstrainedField() {
        // given
        Fixture fixture = new Fixture(false, false);
        Node instance = fixture.instance(new Node().value("text"));

        // when
        Node resolved = fixture.blue.resolve(instance);

        // then
        assertEquals("text", resolved.get("/payload/value"));
    }

    @Test
    void shouldAcceptListForUnconstrainedField() {
        // given
        Fixture fixture = new Fixture(false, false);
        Node instance = fixture.instance(new Node().items(
                Arrays.asList(new Node().value("first"))));

        // when
        Node resolved = fixture.blue.resolve(instance);

        // then
        assertEquals("first", resolved.get("/payload/0/value"));
    }

    @Test
    void shouldAcceptObjectForUnconstrainedField() {
        // given
        Fixture fixture = new Fixture(false, false);
        Node instance = fixture.instance(new Node().properties(
                "member", new Node().value("value")));

        // when
        Node resolved = fixture.blue.resolve(instance);

        // then
        assertEquals("value", resolved.get("/payload/member/value"));
    }

    @Test
    void shouldAcceptSpecializedValueForUnconstrainedField() {
        // given
        Fixture fixture = new Fixture(false, false);
        Node instance = fixture.instance(new Node()
                .type(reference(TEXT_TYPE_BLUE_ID))
                .value("specialized"));

        // when
        Node resolved = fixture.blue.resolve(instance);

        // then
        assertEquals("specialized", resolved.get("/payload/value"));
        assertEquals(TEXT_TYPE_BLUE_ID,
                ((Node) resolved.get("/payload/type")).getBlueId());
    }

    @Test
    void shouldAcceptPureReferenceForUnconstrainedField() {
        // given
        Fixture fixture = new Fixture(false, false);
        Node referenced = new Node().name("Referenced payload").value("value");
        fixture.provider.addSingleNodes(referenced);
        String referencedBlueId =
                fixture.provider.getBlueIdByName("Referenced payload");
        Node instance = fixture.instance(reference(referencedBlueId));

        // when
        Node resolved = fixture.blue.resolve(instance);

        // then
        assertNotNull(resolved.getProperties().get("payload"));
        assertEquals(referencedBlueId,
                resolved.getProperties().get("payload").getBlueId());
    }

    @Test
    void shouldAllowOptionalUnconstrainedFieldToBeAbsent() {
        // given
        Fixture fixture = new Fixture(false, false);
        Node instance = fixture.instance(null);

        // when
        Node resolved = fixture.blue.resolve(instance);

        // then
        assertNotNull(resolved);
    }

    @Test
    void shouldRejectAbsentRequiredUnconstrainedField() {
        // given
        Fixture fixture = new Fixture(true, false);
        Node instance = fixture.instance(null);

        // when
        Throwable failure = captureFailure(() -> fixture.blue.resolve(instance));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    void shouldAcceptObjectForDictionaryField() {
        // given
        Fixture fixture = new Fixture(false, true);
        Node instance = fixture.instance(new Node().properties(
                "member", new Node().value("value")));

        // when
        Node resolved = fixture.blue.resolve(instance);

        // then
        assertEquals("value", resolved.get("/payload/member/value"));
    }

    @Test
    void shouldRejectScalarForDictionaryField() {
        // given
        Fixture fixture = new Fixture(false, true);
        Node instance = fixture.instance(new Node().value("not a dictionary"));

        // when
        Throwable failure = captureFailure(() -> fixture.blue.resolve(instance));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static final class Fixture {
        private final BasicNodeProvider provider = new BasicNodeProvider();
        private final Blue blue;
        private final String holderBlueId;

        private Fixture(boolean required, boolean dictionary) {
            Node declaration = new Node().description(
                    "Optional application-defined Blue value.");
            if (required) {
                declaration.schema(new Schema().required(true));
            }
            if (dictionary) {
                declaration.type(reference(DICTIONARY_TYPE_BLUE_ID));
            }
            Node holder = new Node().name("Unconstrained field holder")
                    .properties("payload", declaration);
            provider.addSingleNodes(holder);
            holderBlueId = provider.getBlueIdByName(
                    "Unconstrained field holder");
            blue = new Blue(provider);
        }

        private Node instance(Node payload) {
            Node instance = new Node().type(reference(holderBlueId));
            if (payload != null) {
                instance.properties("payload", payload);
            }
            return instance;
        }
    }
}
