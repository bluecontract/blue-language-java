package blue.language.provider;

import blue.language.Blue;
import blue.language.merge.processor.BasicTypesVerifier;
import blue.language.merge.processor.DictionaryProcessor;
import blue.language.merge.processor.ListProcessor;
import blue.language.merge.processor.SchemaPropagator;
import blue.language.merge.processor.SchemaVerifier;
import blue.language.merge.processor.SequentialMergingProcessor;
import blue.language.merge.processor.TypeAssigner;
import blue.language.merge.processor.ValuePropagator;
import blue.language.model.Node;

import java.util.Arrays;
import java.util.Objects;

/**
 * Reproduces the released default Language source-content strategy without
 * inheriting caller-selected merge behavior or traversal limits.
 *
 * <p>The operation provider and preprocessing aliases are captured from the
 * active runtime because they are exact evidence inputs. The released merger
 * pipeline and unlimited identity traversal are owned here, so a host's custom
 * runtime merger or global limits cannot silently change Content BlueId
 * semantics while retaining the same declared strategy identity.</p>
 */
final class ReleasedSourceContentStrategy {

    private ReleasedSourceContentStrategy() {
    }

    /**
     * Canonicalizes authored source under the released default Language
     * strategy.
     *
     * @param source exact imported source
     * @param operationBlue provider and preprocessing environment owner
     * @return canonical direct BlueId input
     */
    static Node canonicalize(
            Node source,
            Blue operationBlue) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(
                operationBlue, "operationBlue");
        try (Blue sourceBlue = new Blue(
                operationBlue.getNodeProvider(),
                new SequentialMergingProcessor(Arrays.asList(
                        new ValuePropagator(),
                        new TypeAssigner(),
                        new ListProcessor(),
                        new DictionaryProcessor(),
                        new SchemaPropagator(),
                        new SchemaVerifier(),
                        new BasicTypesVerifier())),
                null,
                operationBlue.cachePolicy())) {
            sourceBlue.preprocessingAliases(
                    operationBlue.getPreprocessingAliases());
            return sourceBlue.canonicalize(source);
        }
    }
}
