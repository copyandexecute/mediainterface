plugins {
    id("java-library")
}

dependencies {
    api(project(":mediainterface-core"))
    api(project(":mediainterface-linux"))
    api(project(":mediainterface-windows"))
    api(project(":mediainterface-macos"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
