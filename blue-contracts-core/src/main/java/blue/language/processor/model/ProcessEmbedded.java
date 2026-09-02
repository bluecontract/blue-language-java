package blue.language.processor.model;

import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Contract selecting immediate descendants that participate as embedded
 * processing scopes, either by exact path or by direct collection membership.
 *
 * <p>The contract independently owns both mutable declaration lists.
 * Replacement values are copied, and access is provided through
 * unmodifiable live views.</p>
 */
@TypeBlueId(RuntimeBlueIds.PROCESS_EMBEDDED)
public class ProcessEmbedded extends Contract {

    private List<String> paths = new ArrayList<>();
    private List<String> collectionPaths = new ArrayList<>();

    /** Creates a contract with no selected embedded paths. */
    public ProcessEmbedded() {
    }

    /**
     * Returns an unmodifiable view of the selected relative paths.
     *
     * @return unmodifiable live view in insertion order
     */
    public List<String> getPaths() {
        return Collections.unmodifiableList(mutablePaths());
    }

    /**
     * Replaces the selected paths with a copy of the supplied list.
     *
     * @param newPaths replacement paths, or {@code null} to clear the
     *        selection
     */
    public void setPaths(List<String> newPaths) {
        List<String> target = mutablePaths();
        target.clear();
        if (newPaths != null) {
            target.addAll(newPaths);
        }
    }

    /**
     * Adds a selected path.
     *
     * @param path path to append; {@code null} is ignored
     * @return this contract
     */
    public ProcessEmbedded addPath(String path) {
        if (path != null) {
            mutablePaths().add(path);
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
        return Collections.unmodifiableList(mutableCollectionPaths());
    }

    /**
     * Replaces the collection paths with a copy of the supplied list.
     *
     * @param newCollectionPaths replacement paths, or {@code null} to clear
     *        the selection
     */
    public void setCollectionPaths(List<String> newCollectionPaths) {
        List<String> target = mutableCollectionPaths();
        target.clear();
        if (newCollectionPaths != null) {
            target.addAll(newCollectionPaths);
        }
    }

    /**
     * Adds a collection path.
     *
     * @param collectionPath collection path to append; {@code null} is ignored
     * @return this contract
     */
    public ProcessEmbedded addCollectionPath(String collectionPath) {
        if (collectionPath != null) {
            mutableCollectionPaths().add(collectionPath);
        }
        return this;
    }

    private List<String> mutablePaths() {
        if (paths == null) {
            paths = new ArrayList<>();
        }
        return paths;
    }

    private List<String> mutableCollectionPaths() {
        if (collectionPaths == null) {
            collectionPaths = new ArrayList<>();
        }
        return collectionPaths;
    }
}
