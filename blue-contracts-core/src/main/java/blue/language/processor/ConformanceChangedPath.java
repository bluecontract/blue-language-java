package blue.language.processor;

import blue.language.processor.util.PointerUtils;

/**
 * A patch target and the scope that originated it, used by injected conformance planners.
 */
public final class ConformanceChangedPath {

    private final String path;
    private final String originScope;

    /**
     * Creates a normalized changed-path descriptor.
     *
     * @param path JSON Pointer identifying the changed document location
     * @param originScope scope whose effect produced the change
     */
    public ConformanceChangedPath(String path, String originScope) {
        this.path = PointerUtils.normalizePointer(path);
        this.originScope = PointerUtils.normalizeScope(originScope);
    }

    /**
     * Returns the normalized changed path.
     *
     * @return a normalized JSON Pointer
     */
    public String path() {
        return path;
    }

    /**
     * Returns the normalized originating scope.
     *
     * @return a normalized scope pointer
     */
    public String originScope() {
        return originScope;
    }
}
