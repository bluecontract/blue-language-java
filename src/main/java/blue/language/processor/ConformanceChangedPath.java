package blue.language.processor;

import blue.language.processor.util.PointerUtils;

/**
 * A patch target and the scope that originated it, used by injected conformance planners.
 */
public final class ConformanceChangedPath {

    private final String path;
    private final String originScope;

    public ConformanceChangedPath(String path, String originScope) {
        this.path = PointerUtils.normalizePointer(path);
        this.originScope = PointerUtils.normalizeScope(originScope);
    }

    public String path() {
        return path;
    }

    public String originScope() {
        return originScope;
    }
}
