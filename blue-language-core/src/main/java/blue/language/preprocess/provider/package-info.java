/**
 * Local provider implementations used for preprocessing and development.
 *
 * <p><strong>Contents.</strong> In-memory and directory-backed canonical node
 * providers belong here. Remote transport protocols, semantic preprocessing
 * stages, and global registries do not.</p>
 *
 * <p><strong>Entry points.</strong>
 * {@link blue.language.preprocess.provider.BasicNodeProvider} supports explicit
 * local ingestion; {@link blue.language.preprocess.provider.DirectoryBasedNodeProvider}
 * loads canonical content from a selected directory.</p>
 *
 * <p><strong>Lifecycle.</strong> These providers own mutable indexes or file
 * access configuration and are not implicitly safe for concurrent mutation.
 * They expose no closeable resource; callers control their construction scope.</p>
 *
 * <p><strong>Extension.</strong> General provider implementations should target
 * {@link blue.language.provider.NodeProvider}; transport-specific providers
 * belong in their transport module. Preprocessing orchestration lives in the
 * parent {@code blue.language.preprocess} package.</p>
 */
package blue.language.preprocess.provider;
