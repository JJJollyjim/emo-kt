package me.eater.emo.emo

import me.eater.emo.Account
import me.eater.emo.EmoEnvironment
import me.eater.emo.Target
import me.eater.emo.emo.dto.ClientLock
import me.eater.emo.emo.dto.LaunchOptions
import me.eater.emo.emo.dto.Profile
import me.eater.emo.minecraft.dto.manifest.Argument
import me.eater.emo.minecraft.dto.manifest.emoKlaxon
import me.eater.emo.minecraft.dto.manifest.parseManifest
import java.io.File
import java.nio.file.Paths

/**
 * Class used to start Minecraft
 *
 * @param profileLocation Location of minecraft install
 * @param account Account to start minecraft with, may be null for a server
 */
class MinecraftExecutor(val profileLocation: String, val account: Account? = null, val java: String = "java") {
    var process: Process? = null


    fun createArgs(): List<String> {
        val profile = Profile.fromJson(File("$profileLocation/emo.json").readText())!!

        val launchOptionsFile = Paths.get(profileLocation, ".emo/launch.json").toFile()
        val launchOptions: LaunchOptions = if (launchOptionsFile.exists()) {
            emoKlaxon().parse(launchOptionsFile.readText())!!
        } else {
            LaunchOptions()
        }

        val args = when (profile.target) {
            Target.Client -> {
                if (account === null) {
                    throw Error("Account is needed to start Minecraft")
                }

                val clientLock: ClientLock =
                    emoKlaxon()
                        .parse(Paths.get(profileLocation, ".emo/client.json").toFile())!!

                val manifest = parseManifest(
                    Paths.get(
                        profileLocation,
                        ".emo/minecraft.json"
                    ).toFile().readText()
                )
                val environment = EmoEnvironment()

                val classpath
                        : MutableList<String> = mutableListOf()
                classpath.addAll(clientLock.start?.extraLibraries?.map { "libraries/$it" } ?: listOf())
                    classpath.addAll(manifest.getLibraries()
                        .filter { it.downloads.artifact !== null && environment.passesRules(it.rules) }
                        .map { "libraries/" + it.downloads.artifact!!.path }
                        .toMutableList())

                classpath.add("minecraft.jar")

                val nonUniqueClasspath = mutableListOf<String>();
                for (cp in classpath) {
                    if (nonUniqueClasspath.contains(cp)) {
                        continue
                    }

                    nonUniqueClasspath.add(cp)
                }

                // Set default template variables and add variables from clientLock
                val vars = mutableMapOf(
                    Pair("classpath", nonUniqueClasspath.map {
                        Paths.get(profileLocation, it).toRealPath().toString()
                    }.joinToString(File.pathSeparator)),
                    Pair("user_type", "mojang"),
                    Pair(
                        "auth_uuid",
                        account.uuid ?: throw RuntimeException("User has no uuid (are you using a demo account?)")
                    ),
                    Pair(
                        "auth_player_name",
                        account.displayName
                            ?: throw RuntimeException("User has no uuid (are you using a demo account?)")
                    ),
                    Pair("auth_access_token", account.accessToken),
                    Pair("game_directory", Paths.get(profileLocation).toRealPath().toString())
                )

                vars.putAll(clientLock.vars)

                if (!Paths.get(vars["natives_directory"]).isAbsolute) {
                    vars.set(
                        "natives_directory",
                        Paths.get("$profileLocation/${vars["natives_directory"]}").toRealPath().toString()
                    )
                }

                val gameArgs = processArguments(
                    clientLock.start!!.gameArguments,
                    environment,
                    vars
                )

                val jvmArgs = processArguments(
                    clientLock.start!!.jvmArguments,
                    environment,
                    vars
                )

                listOf(
                    listOf(launchOptions.java ?: java),
                    jvmArgs,
                    launchOptions.getJVMArgs().toList(),
                    listOf(clientLock.start!!.mainClass),
                    gameArgs
                ).flatten()
            }
            Target.Server -> {
                // -should- work
                listOf(
                    launchOptions.java ?: java,
                    *launchOptions.getJVMArgs(),
                    "-jar",
                    when (profile.forge) {
                        null -> "minecraft_server.${profile.minecraft}.jar"
                        else -> "forge.jar"
                    },
                    "-nogui"
                )
            }
        }

        return args
    }

    /**
     * Start minecraft for given [profileLocation] and [account], returns running [Process]
     */
    fun execute(): Process {
        val args = createArgs();

        process = ProcessBuilder().apply {
            command(args)
            directory(Paths.get(profileLocation).toFile())
            redirectOutput(ProcessBuilder.Redirect.INHERIT)
            redirectError(ProcessBuilder.Redirect.INHERIT)
        }.start()

        return process!!
    }

    private fun processArguments(
        arguments: List<Argument>,
        environment: EmoEnvironment,
        vars: Map<String, String>
    ): List<String> {
        return arguments
            .filter { environment.passesRules(it.rules) }
            .flatMap { it.value }
            .map { s -> s.replace(Regex("\\$\\{([^}]+)}")) { vars[it.groupValues[1]] ?: "" } }
    }
}
