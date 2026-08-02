package blue.language.processor.model;

import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Marker selecting immediate descendants that participate as embedded
 * processing scopes, either by exact path or by direct collection membership.
 *
 * <p>The marker independently owns both mutable declaration lists.
 * Replacement values are copied, and access is provided through
 * unmodifiable live views.</p>
 */
@TypeBlueId(RuntimeBlueIds.PROCESS_EMBEDDED)
public class ProcessEmbedded extends MarkerContract {

    private final List<String> paths = new ArrayList<>();
    private final List<String> collectionPaths = new ArrayList<>();

    /** Creates a marker with no selected embedded paths. */
    public ProcessEmbedded() {
    }

    /**
     * Returns an unmodifiable view of the selected relative paths.
     *
     * @return unmodifiable live view in insertion order
     */
    public List<String> getPaths() {
        return Collections.unmodifiableList(paths);
    }

    /**
     * Replaces the selected paths with a copy of the supplied list.
     *
     * @param newPaths replacement paths, or {@code null} to clear the
     *        selection
     */
    public void setPaths(List<String> newPaths) {
        paths.clear();
        if (newPaths != null) {
            paths.addAll(newPaths);
        }
    }

    /**
     * Adds a selected path.
     *
     * @param path path to append; {@code null} is ignored
     * @return this marker
     */
    public ProcessEmbedded addPath(String path) {
        if (path != null) {
            paths.add(path);
        }
        return this;
    }

    /**
     * Returns an unmodifiable view of collection paths whose direct members
     * become embedded scopes.
     *
     * @return unmodifiable live view in insertion order
     */
    public List<String> getCollectionPaths() {
        return Collections.unmodifiableList(collectionPaths);
    }

    /**
     * Replaces the collection paths with a copy of the supplied list.
     *
     * @param newCollectionPaths replacement paths, or {@code null} to clear
     *        the selection
     */
    public void setCollectionPaths(List<String> newCollectionPaths) {
        collectionPaths.clear();
        if (newCollectionPaths != null) {
            collectionPaths.addAll(newCollectionPaths);
        }
    }

    /**
     * Adds a collection path.
     *
     * @param collectionPath collection path to append; {@code null} is ignored
     * @return this marker
     */
    public ProcessEmbedded addCollectionPath(String collectionPath) {
        if (collectionPath != null) {
            collectionPaths.add(collectionPath);
        }
        return this;
    }
}
