package blue.language.preprocess;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
import blue.language.provider.BootstrapProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.model.wire.BlueLanguageConstants.INTEGER_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PreprocessingExecutionOrderTest {

    @Test
    void shouldExecuteFrozenTransformationsOnceInDeclarationOrder() {
        // given
        Node source = sourceWithTransformations(
                TEXT_TYPE_BLUE_ID, INTEGER_TYPE_BLUE_ID);
        List<String> executionOrder = new ArrayList<>();
        TransformationProcessorProvider registry = registry(
                executionOrder, false);

        // when
        new Preprocessor(registry, BootstrapProvider.INSTANCE)
                .preprocess(source);

        // then
        assertEquals(Arrays.asList("first", "second"), executionOrder);
    }

    @Test
    void shouldPreflightAllTransformationsBeforeExecutingFirst() {
        // given
        Node source = sourceWithTransformations(
                TEXT_TYPE_BLUE_ID, INTEGER_TYPE_BLUE_ID);
        AtomicInteger executions = new AtomicInteger();
        TransformationProcessorProvider registry =
                new TransformationProcessorProvider() {
                    @Override
                    public Optional<TransformationProcessor> getProcessor(
                            Node transformation) {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<TransformationProcessor> processorFor(
                            String typeBlueId, Node transformation) {
                        if (TEXT_TYPE_BLUE_ID.equals(typeBlueId)) {
                            return Optional.of(document -> {
                                executions.incrementAndGet();
                                return document;
                            });
                        }
                        return Optional.empty();
                    }
                };

        // when
        Executable preprocessing = () -> new Preprocessor(
                registry, BootstrapProvider.INSTANCE)
                .preprocess(source);

        // then
        assertThrows(IllegalArgumentException.class, preprocessing);
        assertEquals(0, executions.get());
    }

    private TransformationProcessorProvider registry(
            List<String> executionOrder, boolean rejectSecond) {
        return new TransformationProcessorProvider() {
            @Override
            public Optional<TransformationProcessor> getProcessor(
                    Node transformation) {
                return Optional.empty();
            }

            @Override
            public Optional<TransformationProcessor> processorFor(
                    String typeBlueId, Node transformation) {
                if (TEXT_TYPE_BLUE_ID.equals(typeBlueId)) {
                    return Optional.of(document -> {
                        executionOrder.add("first");
                        return document;
                    });
                }
                if (!rejectSecond
                        && INTEGER_TYPE_BLUE_ID.equals(typeBlueId)) {
                    return Optional.of(document -> {
                        executionOrder.add("second");
                        return document;
                    });
                }
                return Optional.empty();
            }
        };
    }

    private Node sourceWithTransformations(
            String firstTypeBlueId, String secondTypeBlueId) {
        return YAML_MAPPER.readValue(
                "blue:\n"
                        + "  transformations:\n"
                        + "    - type:\n"
                        + "        blueId: " + firstTypeBlueId + "\n"
                        + "    - type:\n"
                        + "        blueId: " + secondTypeBlueId + "\n"
                        + "value: source",
                Node.class);
    }
}
