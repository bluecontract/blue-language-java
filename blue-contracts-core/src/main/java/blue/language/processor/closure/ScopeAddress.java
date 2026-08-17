package blue.language.processor.closure;

import java.util.Objects;

/**
 * Representation-neutral address of one managed scope occurrence.
 *
 * <p>The closure 1.0 execution profile selects {@link #root()} for every
 * managed document. Embedded addresses remain expressible as relationship
 * evidence without making a document aware of a containing document.</p>
 */
public final class ScopeAddress implements Comparable<ScopeAddress> {

    private static final ScopeAddress ROOT = new ScopeAddress("/", 0L);

    private final String path;
    private final long activationGeneration;

    private ScopeAddress(String path, long activationGeneration) {
        this.path = ClosureValueSupport.requireAbsolutePointer(path, "path");
        this.activationGeneration = ClosureValueSupport.requireSafeInteger(
                activationGeneration, "activationGeneration");
        if (isRootPath(this.path) != (this.activationGeneration == 0L)) {
            throw new IllegalArgumentException(
                    "Root generation is zero and embedded generations are positive");
        }
    }

    /**
     * Returns the address used to process one managed document independently.
     *
     * @return canonical Root address
     */
    public static ScopeAddress root() {
        return ROOT;
    }

    /**
     * Creates non-Root relationship evidence at a positive generation.
     *
     * @param absolutePath absolute runtime pointer
     * @param activationGeneration positive occurrence generation
     * @return immutable embedded address
     */
    public static ScopeAddress embedded(
            String absolutePath,
            long activationGeneration) {
        ClosureValueSupport.requirePositiveSafeInteger(
                activationGeneration, "activationGeneration");
        if (isRootPath(absolutePath)) {
            throw new IllegalArgumentException(
                    "Use ScopeAddress.root() for the managed Root");
        }
        return new ScopeAddress(absolutePath, activationGeneration);
    }

    /**
     * Returns the absolute runtime pointer.
     *
     * @return admitted path
     */
    public String path() {
        return path;
    }

    /**
     * Returns the scope-occurrence generation.
     *
     * @return zero for Root or a positive embedded generation
     */
    public long activationGeneration() {
        return activationGeneration;
    }

    /**
     * Reports whether this is a document Root address.
     *
     * @return whether the address is Root
     */
    public boolean isRoot() {
        return isRootPath(path);
    }

    /**
     * Compares path then activation generation.
     *
     * @param other address to compare
     * @return portable ordering result
     */
    @Override
    public int compareTo(ScopeAddress other) {
        int order = ClosureValueSupport.comparePortableText(
                path, other.path);
        return order != 0
                ? order
                : Long.compare(activationGeneration,
                        other.activationGeneration);
    }

    /**
     * Compares path and generation values.
     *
     * @param other candidate address
     * @return whether both values address the same occurrence
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ScopeAddress)) {
            return false;
        }
        ScopeAddress address = (ScopeAddress) other;
        return activationGeneration == address.activationGeneration
                && path.equals(address.path);
    }

    /**
     * Returns a value hash.
     *
     * @return address hash
     */
    @Override
    public int hashCode() {
        return Objects.hash(path, activationGeneration);
    }

    /**
     * Returns a diagnostic path/generation form.
     *
     * @return diagnostic text
     */
    @Override
    public String toString() {
        return path + "@" + activationGeneration;
    }

    private static boolean isRootPath(String path) {
        return "/".equals(path);
    }
}
