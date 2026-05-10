package blue.language.processor.model;

/**
 * Base type for all contract representations extracted from a document tree.
 */
public abstract class Contract {

    private String key;
    private String typeBlueId;
    private Integer order;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getTypeBlueId() {
        return typeBlueId;
    }

    public void setTypeBlueId(String typeBlueId) {
        this.typeBlueId = typeBlueId;
    }

    public Integer getOrder() {
        return order;
    }

    public void setOrder(Integer order) {
        this.order = order;
    }
}
