package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static blue.language.model.wire.BlueLanguageConstants.BOOLEAN_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.DOUBLE_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.INTEGER_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class MutationGasChargerTest {

    @Test
    void chargesUntypedAndExplicitlyTypedScalarPatchesIdentically() {
        for (ScalarCase scalar : scalarCases()) {
            // given
            Node untyped = new Node().value(scalar.value);
            Node explicitlyTyped = new Node()
                    .type(new Node().blueId(scalar.typeBlueId))
                    .value(scalar.value);
            FrozenNode prior;
            FrozenNode resulting;
            try (Blue blue = new Blue()) {
                prior = FrozenNode.fromNode(
                        blue.canonicalize(new Node()));
                resulting = FrozenNode.fromNode(
                        blue.canonicalize(
                                new Node().properties(
                                        "scalar", untyped.clone())));
            }

            // when / then
            GasMeter expected = chargeMutable(
                    untyped, prior, resulting);
            assertSameTrace(
                    scalar.label + " explicitly typed mutable",
                    expected,
                    chargeMutable(
                            explicitlyTyped, prior, resulting));
            assertSameTrace(
                    scalar.label + " untyped frozen",
                    expected,
                    chargeFrozen(
                            FrozenNode.fromResolvedNode(untyped),
                            prior,
                            resulting));
            assertSameTrace(
                    scalar.label + " explicitly typed frozen",
                    expected,
                    chargeFrozen(
                            FrozenNode.fromResolvedNode(explicitlyTyped),
                            prior,
                            resulting));
        }
    }

    private static GasMeter chargeMutable(
            Node value,
            FrozenNode prior,
            FrozenNode resulting) {
        GasMeter meter = new GasMeter();
        MutationGasCharger charger =
                new MutationGasCharger(meter, () -> prior);
        charger.charge(
                "/scalar",
                JsonPatch.Op.ADD,
                value,
                null,
                false,
                prior,
                resulting);
        return meter;
    }

    private static GasMeter chargeFrozen(
            FrozenNode value,
            FrozenNode prior,
            FrozenNode resulting) {
        GasMeter meter = new GasMeter();
        MutationGasCharger charger =
                new MutationGasCharger(meter, () -> prior);
        charger.charge(
                "/scalar",
                JsonPatch.Op.ADD,
                null,
                value,
                false,
                prior,
                resulting);
        return meter;
    }

    private static void assertSameTrace(
            String message,
            GasMeter expected,
            GasMeter actual) {
        assertEquals(
                expected.totalGas(), actual.totalGas(), message);
        assertEquals(
                fingerprint(expected.trace()),
                fingerprint(actual.trace()),
                message);
    }

    private static List<ScalarCase> scalarCases() {
        return Arrays.asList(
                new ScalarCase(
                        "text", "same scalar", TEXT_TYPE_BLUE_ID),
                new ScalarCase(
                        "integer", BigInteger.valueOf(42L),
                        INTEGER_TYPE_BLUE_ID),
                new ScalarCase(
                        "double", new BigDecimal("12.5"),
                        DOUBLE_TYPE_BLUE_ID),
                new ScalarCase(
                        "boolean", Boolean.TRUE,
                        BOOLEAN_TYPE_BLUE_ID));
    }

    private static List<String> fingerprint(
            List<GasTraceEntry> trace) {
        List<String> result = new ArrayList<>(trace.size());
        for (GasTraceEntry entry : trace) {
            result.add(
                    entry.namespace()
                            + ":" + entry.counter()
                            + ":" + entry.quantity()
                            + ":" + entry.weight()
                            + ":" + entry.subtotal()
                            + ":" + entry.logicalPath()
                            + ":" + entry.reason());
        }
        return result;
    }

    private static final class ScalarCase {
        private final String label;
        private final Object value;
        private final String typeBlueId;

        private ScalarCase(
                String label,
                Object value,
                String typeBlueId) {
            this.label = label;
            this.value = value;
            this.typeBlueId = typeBlueId;
        }
    }
}
