package blue.language.processor.model;

/**
 * Base type for all contract representations extracted from a rooted document
 * graph slice.
 *
 * <p>Instances are mutable loader models. The contract loader assigns the
 * declaration metadata after constructing a concrete subtype, so callers
 * should not treat a contract as fully initialized until loading completes.</p>
 */
public abstract class Contract {

    private String key;
    private String typeBlueId;
    private Integer order;

    /** Creates an uninitialized contract for a concrete loader model. */
    public Contract() {
    }

    /**
     * Returns the scope-local key under which this contract was declared.
     *
     * @return declaration key, or {@code null} before it is assigned
     */
    public String getKey() {
        return key;
    }

    /**
     * Records the scope-local declaration key assigned by the contract loader.
     *
     * @param key declaration key, or {@code null} to clear it
     */
    public void setKey(String key) {
        this.key = key;
    }

    /**
     * Returns the exact effective type BlueId used for processor dispatch.
     *
     * @return effective type BlueId, or {@code null} before it is assigned
     */
    public String getTypeBlueId() {
        return typeBlueId;
    }

    /**
     * Records the exact effective type BlueId used for processor dispatch.
     *
     * @param typeBlueId effective type BlueId, or {@code null} to clear it
     */
    public void setTypeBlueId(String typeBlueId) {
        this.typeBlueId = typeBlueId;
    }

    /**
     * Returns the optional deterministic declaration order.
     *
     * @return declaration order, or {@code null} when no order was declared
     */
    public Integer getOrder() {
        return order;
    }

    /**
     * Sets the optional deterministic declaration order.
     *
     * @param order declaration order, or {@code null} to use the default order
     */
    public void setOrder(Integer order) {
        this.order = order;
    }
}
