package blue.language.processor.model;

/**
 * Base type for all contract representations extracted from a document tree.
 */
public abstract class Contract {

    private String key;
    private Integer order;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Integer getOrder() {
        return order;
    }

    public void setOrder(Integer order) {
        this.order = order;
    }
}
