pluginManagement {
    includeBuild("build-logic")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "blue-language-java-build"

include(
    ":blue-language-model",
    ":blue-language-core",
    ":blue-language-mapping",
    ":blue-language-ipfs",
    ":blue-contracts-core",
    ":blue-conformance",
    ":blue-language-java",
    ":examples",
)
