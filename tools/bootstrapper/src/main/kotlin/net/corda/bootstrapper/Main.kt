package net.corda.bootstrapper

import com.jcabi.manifests.Manifests
import net.corda.core.internal.readFully
import net.corda.core.internal.rootMessage
import net.corda.nodeapi.internal.network.NetworkBootstrapper
import picocli.CommandLine
import picocli.CommandLine.*
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val main = Main()
    try {
        CommandLine.run(main, *args)
    } catch (e: ExecutionException) {
        val throwable = e.cause ?: e
        if (main.verbose) {
            throwable.printStackTrace()
        } else {
            System.err.println("*ERROR*: ${throwable.rootMessage ?: "Use --verbose for more details"}")
        }
        exitProcess(1)
    }
}

@Command(
        name = "Network Bootstrapper",
        versionProvider = CordaVersionProvider::class,
        mixinStandardHelpOptions = true,
        showDefaultValues = true,
        description = [ "Bootstrap a local test Corda network using a set of node conf files and CorDapp jars" ]
)
class Main : Runnable {
    @Option(
            names = ["--dir"],
            description = [
                "Root directory containing the node conf files and CorDapp jars that will form the test network.",
                "It may also contain existing node directories."
            ]
    )
    private var dir: Path = Paths.get(".")

    @Option(
            names = ["--corda-jar"],
            description = ["Use the embedded corda.jar if one is not provided in the root directory or if an existing node directory doesn't have one."]
    )
    private var cordajar: Boolean = false

    @Option(names = ["--no-copy"], description = ["""Don't copy the CorDapp jars into the nodes' "cordapps" directories."""])
    private var noCopy: Boolean = false

    @Option(names = ["--verbose"], description = ["Enable verbose output."])
    var verbose: Boolean = false

    override fun run() {
        if (verbose) {
            System.setProperty("logLevel", "trace")
        }
        NetworkBootstrapper().bootstrap(dir.toAbsolutePath().normalize(), copyCordapps = !noCopy)
    }
}

private class CordaVersionProvider : IVersionProvider {
    override fun getVersion(): Array<String> {
        return arrayOf(
                "Version: ${Manifests.read("Corda-Release-Version")}",
                "Revision: ${Manifests.read("Corda-Revision")}"
        )
    }
}
