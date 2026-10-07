plugins {
    id("net.neoforged.moddev") version "2.0.147"
}

group = providers.gradleProperty("mod_group_id").get()
version = providers.gradleProperty("mod_version").get()
base.archivesName.set(providers.gradleProperty("mod_id").get())

val minecraftVersion = providers.gradleProperty("minecraft_version").get()
val neoForgeVersion = providers.gradleProperty("neoforge_version").get()
val jeiVersion = providers.gradleProperty("jei_version").get()
val modId = providers.gradleProperty("mod_id").get()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://maven.blamejared.com") {
        name = "Jared's Maven (JEI)"
    }
}

dependencies {
    compileOnly("org.spongepowered:mixin:0.8.7")
    compileOnly("mezz.jei:jei-1.21.1-neoforge:$jeiVersion")
    runtimeOnly("mezz.jei:jei-1.21.1-neoforge:$jeiVersion")
    testImplementation("mezz.jei:jei-1.21.1-neoforge:$jeiVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

neoForge {
    version = neoForgeVersion

    runs {
        configureEach {
            systemProperty("neoforge.enabledGameTestNamespaces", modId)
        }

        create("client") {
            client()
        }

        create("server") {
            server()
            programArgument("--nogui")
        }
    }

    mods {
        create(modId) {
            sourceSet(sourceSets.main.get())
        }
    }

    unitTest {
        enable()
        testedMod.set(mods.getByName(modId))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    val metadata = mapOf(
        "minecraft_version" to minecraftVersion,
        "neoforge_version" to neoForgeVersion,
        "mod_license" to providers.gradleProperty("mod_license").get(),
        "mod_version" to project.version.toString()
    )
    inputs.properties(metadata)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(metadata)
    }
}

tasks.test {
    useJUnitPlatform()
}
