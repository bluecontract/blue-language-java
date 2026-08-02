package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;

import java.util.List;

/** Package-owned compatibility value for one immutable document update. */
final class DocumentUpdateData extends DocumentUpdateDataAdapter {

    DocumentUpdateData(
            String path,
            Node before,
            Node after,
            JsonPatch.Op op,
            String originScope,
            List<String> cascadeScopes) {
        super(path, before, after, op, originScope, cascadeScopes);
    }

    DocumentUpdateData(
            String path,
            FrozenNode beforeFrozen,
            FrozenNode afterFrozen,
            JsonPatch.Op op,
            String originScope,
            List<String> cascadeScopes,
            UpdateMaterializationMetrics materializationMetrics) {
        super(path, beforeFrozen, afterFrozen, op, originScope,
                cascadeScopes, materializationMetrics);
    }

    private DocumentUpdateData(
            DocumentUpdateOccurrence occurrence,
            UpdateMaterializationMetrics materializationMetrics) {
        super(occurrence, materializationMetrics);
    }

    DocumentUpdateData withMaterializationMetrics(
            UpdateMaterializationMetrics materializationMetrics) {
        return new DocumentUpdateData(occurrence(), materializationMetrics);
    }
}
