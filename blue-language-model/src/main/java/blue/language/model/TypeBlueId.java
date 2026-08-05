package blue.language.model;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the Blue type identities and default-value lookup configuration for
 * a Java-mapped class.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TypeBlueId {

    /**
     * Returns explicit candidate BlueIds for this Java class.
     *
     * @return explicit candidate BlueIds
     */
    String[] value() default {};

    /**
     * Returns the optional named default identity resolved from the configured repository.
     *
     * @return named default identity, or an empty string
     */
    String defaultValue() default "";

    /**
     * Returns the repository location containing generated defaults.
     *
     * @return default-value repository location
     */
    String defaultValueRepositoryLocation() default "blue-preprocessed";

    /**
     * Returns the property resource used to resolve named defaults.
     *
     * @return default-value property resource
     */
    String defaultValuePropertyFile() default "blue-ids.yaml";

    /**
     * Returns the optional repository subdirectory override.
     *
     * @return repository subdirectory, or an empty string
     */
    String defaultValueRepositoryDir() default "";

    /**
     * Returns the optional repository key override.
     *
     * @return repository key, or an empty string
     */
    String defaultValueRepositoryKey() default "";
}
