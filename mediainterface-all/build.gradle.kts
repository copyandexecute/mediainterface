import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.0.1"
}

dependencies {
    // `api` so consumers within this Gradle build (e.g. :examples) see the
    // submodule classes transitively. POM deps are stripped in afterEvaluate
    // below so external Maven consumers get only the self-contained shadow jar.
    api(project(":mediainterface-core"))
    api(project(":mediainterface-linux"))
    api(project(":mediainterface-windows"))
    api(project(":mediainterface-macos"))
    implementation("org.slf4j:slf4j-api:2.0.9")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

// The default `jar` task would produce an empty artifact (no src/main/java).
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<ShadowJar>("shadowJar") {
    // Replace the regular jar as the primary artifact (no classifier).
    archiveClassifier.set("")
    configurations = listOf(project.configurations.runtimeClasspath.get())
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    // Merge platform SPI service files from all 3 providers so ServiceLoader
    // sees linux + windows + macos at runtime.
    mergeServiceFiles {
        include("META-INF/services/org.endlesssource.mediainterface.spi.PlatformMediaProvider")
    }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("module-info.class")
    // slf4j-api is typically provided by the consumer (Minecraft ships it).
    dependencies {
        exclude(dependency("org.slf4j:slf4j-api:.*"))
    }
}

tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}

// The parent build.gradle.kts auto-creates a `mavenJava` publication from
// components["java"]. Replace its artifacts with the shadow jar and strip
// transitive deps from the POM (they're all shaded).
afterEvaluate {
    publishing {
        publications.withType<MavenPublication>().configureEach {
            if (name == "mavenJava") {
                val artifactList = artifacts.toList()
                artifacts.clear()
                artifact(tasks.named("shadowJar"))
                // Keep sources/javadoc jars if the parent added them
                artifactList.filter { it.classifier == "sources" || it.classifier == "javadoc" }
                    .forEach { artifact(it) }
                pom.withXml {
                    val root = asNode()
                    val children = root.children().filterIsInstance<groovy.util.Node>()
                    children.filter { it.name().toString().substringAfterLast(":") == "dependencies" }
                        .forEach { root.remove(it) }
                    // slf4j-api as optional runtime dep (not shaded)
                    val deps = root.appendNode("dependencies")
                    val dep = deps.appendNode("dependency")
                    dep.appendNode("groupId", "org.slf4j")
                    dep.appendNode("artifactId", "slf4j-api")
                    dep.appendNode("version", "2.0.9")
                    dep.appendNode("scope", "runtime")
                    dep.appendNode("optional", "true")
                }
            }
        }
    }
}
