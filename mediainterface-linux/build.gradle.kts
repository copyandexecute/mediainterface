plugins {
    id("java-library")
}

dependencies {
    api(project(":mediainterface-core"))
    // dbus-java 3.x is the last Java 8 line. 4.x ships Java 11/16 bytecode which
    // breaks Forge 1.8.9/1.12.2 ASM 5 mod scanning ("probably a corrupt zip").
    // 3.3.2 covers our MPRIS use-case and uses jnr-unixsocket transport (Java 8).
    api("com.github.hypfvieh:dbus-java:3.3.2")
    implementation("org.slf4j:slf4j-api:2.0.9")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
