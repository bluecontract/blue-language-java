plugins {
    id("java")
}

group = "blue.language"
version = "1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    mavenCentral()
}

dependencies {
    // JUnit Jupiter (JUnit 5)
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")

    // Jackson
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")

    implementation("commons-codec:commons-codec:1.15")

    testImplementation("org.apache.httpcomponents:httpclient:4.5.14")


}

tasks.test {
    useJUnitPlatform()
}