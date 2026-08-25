package blue.language.processor;

import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;

import java.util.LinkedHashSet;
import java.util.Set;

/** Finds exact processor-state witnesses that must remain references. */
final class ProcessorStateReferencePathCatalog {

    private ProcessorStateReferencePathCatalog() {
    }

    static Set<String> find(
            Node document,
            Iterable<String> openedScopePaths) {
        Set<String> result = new LinkedHashSet<String>();
        for (String scopePath
                : ExecutableBodyPathCatalog.openedScopes(openedScopePaths)) {
            Node scope = JsonPointer.ROOT.equals(scopePath)
                    ? document
                    : NodePathEditor.getOrNull(document, scopePath);
            Node contracts = scope != null ? scope.getContracts() : null;
            Node initialized = contracts != null
                    && contracts.getProperties() != null
                    ? contracts.getProperties().get(
                            ProcessorContractConstants.KEY_INITIALIZED)
                    : null;
            Node exactDocument = initialized != null
                    && initialized.getProperties() != null
                    ? initialized.getProperties().get(
                            ProcessorContractConstants.KEY_DOCUMENT)
                    : null;
            if (exactDocument != null && exactDocument.isReferenceOnly()) {
                result.add(JsonPointer.append(
                        ProcessorEngine.resolvePointer(
                                scopePath,
                                ProcessorPointerConstants
                                        .RELATIVE_INITIALIZED),
                        ProcessorContractConstants.KEY_DOCUMENT));
            }
        }
        return result;
    }
}
