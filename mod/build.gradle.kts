plugins {
    `java-library`
    kotlin("jvm") version "2.3.0"
    id("net.neoforged.moddev") version "2.0.141"
}

val modId = project.property("mod_id") as String
val modVersion = project.property("mod_version") as String
val modGroupId = project.property("mod_group_id") as String
val neoVersion = project.property("neo_version") as String
val parchmentMinecraftVersion = project.property("parchment_minecraft_version") as String
val parchmentMappingsVersion = project.property("parchment_mappings_version") as String
val kffVersion = project.property("kff_version") as String
val owoVersion = project.property("owo_version") as String
val jeiVersion = project.property("jei_version") as String
val emiVersion = project.property("emi_version") as String

version = modVersion
group = modGroupId

base {
    archivesName.set(modId)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
    jvmToolchain(21)
}

neoForge {
    version = neoVersion

    parchment {
        minecraftVersion = parchmentMinecraftVersion
        mappingsVersion = parchmentMappingsVersion
    }

    runs {
        create("client") {
            client()
            systemProperty("neoforge.enabledGameTestNamespaces", modId)
        }
    }

    mods {
        create(modId) {
            sourceSet(sourceSets.main.get())
        }
    }
}

repositories {
    mavenCentral()
    maven {
        name = "NeoForged"
        url = uri("https://maven.neoforged.net/releases")
    }
    maven {
        name = "Kotlin for Forge"
        url = uri("https://thedarkcolour.github.io/KotlinForForge/")
    }
    maven {
        name = "WispForest"
        url = uri("https://maven.wispforest.io/releases/")
    }
    maven {
        name = "Su5ed (Forgified Fabric API)"
        url = uri("https://maven.su5ed.dev/releases")
    }
    maven {
        name = "Jared's Maven (JEI)"
        url = uri("https://maven.blamejared.com/")
    }
    maven {
        name = "TerraformersMC (EMI)"
        url = uri("https://maven.terraformersmc.com/releases/")
    }
}

dependencies {
    implementation("thedarkcolour:kotlinforforge-neoforge:$kffVersion")
    implementation("io.wispforest:owo-lib-neoforge:$owoVersion")

    // Soft-depend recipe viewers (compileOnly so mod works without them)
    compileOnly("mezz.jei:jei-1.21.1-neoforge-api:$jeiVersion")
    compileOnly("dev.emi:emi-neoforge:$emiVersion:api")
}

tasks.withType<ProcessResources>().configureEach {
    val replaceProperties = mapOf(
        "minecraft_version" to project.property("minecraft_version"),
        "minecraft_version_range" to project.property("minecraft_version_range"),
        "neo_version" to neoVersion,
        "neo_version_range" to project.property("neo_version_range"),
        "mod_id" to modId,
        "mod_name" to project.property("mod_name"),
        "mod_license" to project.property("mod_license"),
        "mod_version" to modVersion,
        "mod_authors" to project.property("mod_authors"),
        "mod_description" to project.property("mod_description"),
    )
    inputs.properties(replaceProperties)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(replaceProperties)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

val loaderSourceSet = sourceSets.create("loader") {
    compileClasspath += sourceSets.main.get().compileClasspath
}

val loaderJar = tasks.register<Jar>("loaderJar") {
    dependsOn(tasks.jar)
    archiveBaseName.set("${modId}-loader")
    archiveVersion.set("")
    from(loaderSourceSet.output)
    from(tasks.jar.flatMap { it.archiveFile }) {
        into("META-INF/agy-bundled")
    }
    manifest {
        attributes(
            "Automatic-Module-Name" to "${modGroupId}.loader",
            "Implementation-Title" to "${project.property("mod_name")} Auto-Updater Loader",
            "Implementation-Version" to modVersion,
        )
    }
}

tasks.named("build") {
    dependsOn(loaderJar)
}
