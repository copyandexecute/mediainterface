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
    // dbus-java 3.3.2 is a Multi-Release JAR with Java 11 overlays under
    // META-INF/versions/11/. Forge 1.8.9/1.12.2 ASM 5 scans every entry and
    // chokes on >Java 8 bytecode ("probably a corrupt zip"). Drop the overlays —
    // Java 8 runtime ignores them anyway, and Java 11+ consumers fall back to
    // the root classes (same code path, no functional loss for our MPRIS use).
    exclude("META-INF/versions/**")
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
                    // Remove all existing <dependencies> nodes (there may be more
                    // than one if multiple publish hooks have contributed).
                    // Matching must cope with namespaced QName via localPart.
                    val toRemove = root.children().filterIsInstance<groovy.util.Node>()
                        .filter {
                            // QName.toString() is "{namespace}localname"; plain
                            // names lack the "}" so substringAfterLast is a no-op.
                            it.name().toString().substringAfterLast("}") == "dependencies"
                        }
                    toRemove.forEach { root.remove(it) }
                    // Declare slf4j-api as optional runtime dep; everything else
                    // is shaded into the uber-JAR.
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
