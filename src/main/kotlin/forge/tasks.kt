package me.eater.emo.forge

import com.github.kittinunf.fuel.coroutines.awaitString
import com.github.kittinunf.fuel.httpDownload
import com.github.kittinunf.fuel.httpGet
import me.eater.emo.EmoContext
import me.eater.emo.Target
import me.eater.emo.VersionSelector
import me.eater.emo.forge.dto.manifest.v1.Manifest
import me.eater.emo.forge.dto.promotions.Promotions
import me.eater.emo.utils.Process
import me.eater.emo.utils.io
import me.eater.emo.utils.parallel
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile

class FetchForgeVersions : Process<EmoContext> {
    override fun getName() = "forge.fetch_versions"
    override suspend fun execute(context: EmoContext) {
        if (context.forgeVersion!!.isStatic()) {
            context.selectedForgeVersion = context.forgeVersion.selector
            return
        }

        val manifest = "https://files.minecraftforge.net/maven/net/minecraftforge/forge/promotions.json"
            .httpGet()
            .awaitString()

        val promotions = Promotions.fromJson(manifest)!!

        val key = when (context.selectedMinecraftVersion) {
            null -> context.forgeVersion.selector
            else -> "${context.selectedMinecraftVersion!!.id}-${context.forgeVersion.selector}"
        }

        if (!promotions.promos.containsKey(key)) {
            throw Error("No forge distribution found for $key")
        }

        promotions.promos.getValue(key).run {
            context.selectedForgeVersion = version
            context.minecraftVersion = VersionSelector(minecraftVersion)
        }
    }
}

class FetchUniversal : Process<EmoContext> {
    override fun getName() = "forge.v1.fetch_universal"

    override suspend fun execute(context: EmoContext) {
        val versionTuple = "${context.selectedMinecraftVersion!!.id}-${context.selectedForgeVersion!!}"
        val artifactUrl =
            "https://files.minecraftforge.net/maven/net/minecraftforge/forge/$versionTuple/forge-$versionTuple-universal.jar"

        io {
            artifactUrl
                .httpDownload()
                .fileDestination { _, _ -> Paths.get(context.installLocation.toString(), "forge.jar").toFile() }
                .response { _ ->}
                .join()
        }
    }
}

class LoadForgeManifest : Process<EmoContext> {
    override fun getName() = "forge.v1.load_manifest"

    override suspend fun execute(context: EmoContext) {
        io {
            val jar = JarFile(Paths.get(context.installLocation.toString(), "forge.jar").toFile())
            val json = String(jar.getInputStream(jar.getJarEntry("version.json")).readBytes())
            context.forgeManifest = Manifest.fromJson(json)!!
            jar.close()
        }
    }
}

class FetchForgeLibraries: Process<EmoContext> {
    override fun getName() = "forge.v1.fetch_libraries"

    override suspend fun execute(context: EmoContext) {
        parallel((context.forgeManifest!! as Manifest).libraries) {
            if (it.clientreq === null && it.serverreq === null) {
                return@parallel
            }

            val mirror = it.url ?: "https://libraries.minecraft.net"

            val file = Paths.get(context.installLocation.toString(), "libraries", it.getPath())
            Files.createDirectories(file.parent)

            if (Files.exists(file)) return@parallel

            (mirror + '/' + it.getPath())
                .httpDownload()
                .fileDestination { _, _ -> file.toFile() }
                .response { _ -> }
                .join()
        }

        if (context.target == Target.Client) {
            val newPath = Paths.get(context.installLocation.toString(), "libraries/net/minecraftforge/forge", "${context.selectedMinecraftVersion!!.id}-${context.selectedForgeVersion!!}", "forge-${context.selectedMinecraftVersion!!.id}-${context.selectedForgeVersion!!}.jar")
            Files.createDirectories(newPath.parent)
            Files.move(Paths.get(context.installLocation.toString(), "forge.jar"), newPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}