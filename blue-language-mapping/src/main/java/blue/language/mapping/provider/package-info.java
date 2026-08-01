/**
 * Provides opt-in classpath discovery for Blue mapping resources.
 *
 * <p><strong>Contents.</strong> This package contains providers that index
 * explicitly selected classpath directories. General mapping, canonical
 * Language bootstrap data, network retrieval, and runtime-wide implicit
 * scanning do not belong here.</p>
 *
 * <p><strong>Entry points.</strong>
 * {@link blue.language.mapping.provider.ClasspathBasedNodeProvider} loads
 * {@code .blue} documents and addressable text resources from directories or
 * JAR entries named by the caller.</p>
 *
 * <p><strong>Lifecycle.</strong> Construction eagerly builds an insertion-
 * ordered index. Configure and construct a provider before sharing it; lookup
 * is read-only afterward and the provider owns no closeable resource.</p>
 *
 * <p><strong>Extension.</strong> Discovery must remain explicit and preserve
 * deterministic resource ordering. Add general provider behavior to
 * {@link blue.language.provider.NodeProvider}, Java-object conversion to
 * {@link blue.language.mapping}, and remote transports to a dedicated provider
 * package such as {@code blue.language.provider.ipfs}.</p>
 */
package blue.language.mapping.provider;
