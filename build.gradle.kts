import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    id("com.gradleup.shadow") version "9.0.0"
}

group = "com.huidu"
version = "1.0.0"

// 支持范围：Paper 1.21.4 ~ 最新（含 26.x）。
// 刻意编译到范围内**最低**的版本：只用 1.21.4 就有的 API，产出的 jar 才能同时
// 跑在 1.21.4 和 26.x 上。若改成编译到最新版，会不知不觉用上新 API，
// 在 1.21.4 上运行时才以 NoSuchMethodError 暴露。
// 升这个值 = 抬高支持下限，务必同步 plugin.yml 的 api-version 与 README。
val paperApiVersion = "1.21.4-R0.1-SNAPSHOT"
// 必须与 paper-api 所 import 的 adventure-bom 一致，否则 Paper 自己的
// Component API 都解析不了。1.21.4 -> 4.20.0，26.2 -> 5.2.0；
// 同样取下限，并由 scripts/verify-api-compat 校验产物在 26.x 上仍可解析。
val adventureVersion = "4.20.0"

repositories {
    mavenCentral()
    maven("https://repo.codemc.org/repository/maven-public")
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
    maven("https://repo.clojars.org/")
    maven("https://libraries.minecraft.net/")
    maven("https://jitpack.io")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    // musicbox-api is built first by Gradle and shaded into the plugin jar below.
    implementation(project(":musicbox-api"))

    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    compileOnly("net.milkbowl.vault:VaultUnlockedAPI:2.7")
    compileOnly("org.yaml:snakeyaml:2.0")
    compileOnly("net.kyori:adventure-api:$adventureVersion")
    compileOnly("net.kyori:adventure-text-minimessage:$adventureVersion")
    compileOnly("net.kyori:adventure-text-serializer-legacy:$adventureVersion")
    compileOnly("me.clip:placeholderapi:2.11.6")

    implementation("com.zaxxer:HikariCP:5.1.0") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Layered-architecture rules: enforces that api/common/core/module only depend downward.
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    // Some tests exercise Bukkit/snakeyaml types directly; compileOnly deps do not reach the
    // test classpath in Gradle, so re-declare them for the test configurations.
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation("org.yaml:snakeyaml:2.0")
}

java {
    // Declared, not inherited from JAVA_HOME: paper-api 26.x class files are version 69, which
    // only javac 25 reads. Bytecode stays at 21 (see options.release below).
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.test {
    useJUnitPlatform()
}

// plugin.yml is the file the server reads, while Gradle owns the version. Substituting it here
// keeps the two from drifting: bumping `version` above is the only edit a release needs.
//
// Plain replacement in the task action rather than filter/expand: those run as script closures,
// which the configuration cache cannot serialise (Gradle 9 rejects the whole build). The version is
// also declared as an input, so changing it re-runs the task instead of reusing a stale copy.
val pluginVersion = version.toString()
tasks.processResources {
    inputs.property("version", pluginVersion)
    val version = pluginVersion
    doLast {
        val pluginFile = destinationDir.resolve("plugin.yml")
        if (pluginFile.isFile) {
            val text = pluginFile.readText()
            val updated = text.replace("\${version}", version)
            if (updated != text) {
                pluginFile.writeText(updated)
            }
        }
    }
}

// Offline replacement for the formatter/linter plugin this project cannot add: spotless,
// checkstyle and errorprone are not in the local dependency cache, and adding them would break
// `--offline` builds. These rules are the small set that has actually caused bugs here, checked
// with no dependency at all.
//
// Deliberately checks src/main only: tests legitimately print and assert on odd strings.
val verifySources by tasks.registering {
    group = "verification"
    description = "Fails on debug leftovers, merge markers and whitespace damage in main sources."
    val sourceDir = layout.projectDirectory.dir("src/main/java")
    inputs.dir(sourceDir)
    doLast {
        val forbidden = mapOf(
            "System.out." to "use the plugin logger or MessageUtils",
            "System.err." to "use the plugin logger",
            "printStackTrace(" to "log the exception instead of printing it",
            "<<<<<<<" to "unresolved merge marker",
            ">>>>>>>" to "unresolved merge marker",
        )
        val problems = mutableListOf<String>()
        sourceDir.asFile.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    forbidden.forEach { (needle, hint) ->
                        if (line.contains(needle)) {
                            problems += "${file.relativeTo(sourceDir.asFile)}:${index + 1}: $needle ($hint)"
                        }
                    }
                    if (line.contains('\t')) {
                        problems += "${file.relativeTo(sourceDir.asFile)}:${index + 1}: tab character"
                    }
                    if (line.endsWith(" ") || line.endsWith("\t")) {
                        problems += "${file.relativeTo(sourceDir.asFile)}:${index + 1}: trailing whitespace"
                    }
                }
            }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Source verification failed with ${problems.size} problem(s):\n" +
                    problems.joinToString("\n") { "  $it" }
            )
        }
    }
}

tasks.named("check") {
    dependsOn(verifySources)
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    // Relocate HikariCP like the former Maven shade config did, so it cannot collide with
    // another plugin's copy on the same server.
    relocate("com.zaxxer.hikari", "com.huidu.musicboxplus.shadow.hikari")
    exclude("module-info.class")
    manifest {
        attributes(
            "Implementation-Title" to "MusicBox",
            "Implementation-Version" to project.version,
        )
    }
}

// The plugin ships as a single shaded jar; the plain jar task would produce the same name.
tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
