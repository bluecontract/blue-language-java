package blue.language.processor;

/** Receives detached before/after update-view materialization events. */
interface UpdateMaterializationMetrics {

    void recordBeforeNodeMaterialization();

    void recordAfterNodeMaterialization();
}
