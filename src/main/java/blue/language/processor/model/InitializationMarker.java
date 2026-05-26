package blue.language.processor.model;

import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

@TypeBlueId(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER)
public class InitializationMarker extends MarkerContract {

    private String documentId;

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }
}
