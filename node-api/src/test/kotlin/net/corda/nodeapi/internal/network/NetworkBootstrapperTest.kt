package net.corda.nodeapi.internal.network

import com.typesafe.config.ConfigFactory
import net.corda.cordform.CordformNode.NODE_INFO_DIRECTORY
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.secureRandomBytes
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.*
import net.corda.core.node.NetworkParameters
import net.corda.core.node.NodeInfo
import net.corda.core.serialization.serialize
import net.corda.core.utilities.OpaqueBytes
import net.corda.node.services.config.NotaryConfig
import net.corda.nodeapi.internal.DEV_ROOT_CA
import net.corda.nodeapi.internal.config.parseAs
import net.corda.nodeapi.internal.config.toConfig
import net.corda.nodeapi.internal.network.NodeInfoFilesCopier.Companion.NODE_INFO_FILE_NAME_PREFIX
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.DUMMY_NOTARY_NAME
import net.corda.testing.core.SerializationEnvironmentRule
import net.corda.testing.internal.createNodeInfoAndSigned
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path

class NetworkBootstrapperTest {
    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule()

    private val fakeEmbeddedCordaJar = fakeFileBytes()

    private val contractsJars = HashMap<Path, TestContractsJar>()
    private val nodeInfoFiles = HashMap<CordaX500Name, NodeInfoInfo>()
    
    private val bootstrapper = NetworkBootstrapper(
            initSerEnv = false,
            embeddedCordaJar = fakeEmbeddedCordaJar::inputStream,
            nodeInfosGenerator = { nodeDirs ->
                nodeDirs.map { nodeDir ->
                    val name = parseFakeNodeConfig(nodeDir).myLegalName
                    // Return back the same node info file if the node is asked to generate again. This is what the real
                    // node does if nothing related to NodeInfo has changed.
                    val info = nodeInfoFiles.computeIfAbsent(name) {
                        val (nodeInfo, signedNodeInfo) = createNodeInfoAndSigned(name)
                        val bytes = signedNodeInfo.serialize().bytes
                        val fileName = "$NODE_INFO_FILE_NAME_PREFIX${SecureHash.randomSHA256()}"
                        (nodeDir / fileName).write(bytes)
                        NodeInfoInfo(fileName, OpaqueBytes(bytes), nodeInfo)
                    }
                    nodeDir / info.fileName
                }
            },
            contractsJarConverter = { contractsJars[it]!! }
    )

    private val aliceConfig = FakeNodeConfig(ALICE_NAME)
    private val bobConfig = FakeNodeConfig(BOB_NAME)
    private val notaryConfig = FakeNodeConfig(DUMMY_NOTARY_NAME, NotaryConfig(validating = true))

    private val rootDir get() = tempFolder.root.toPath()

    @Test
    fun `empty dir`() {
        assertThatThrownBy {
            bootstrap()
        }.hasMessage("No nodes found")
    }

    @Test
    fun `single node conf file`() {
        val (configFile) = createNodeConfFile("node1", bobConfig)
        bootstrap()
        assertThat(configFile).doesNotExist()
        val networkParameters = assertBootstrappedNetwork(fakeEmbeddedCordaJar, "node1" to bobConfig)
        networkParameters.run {
            assertThat(epoch).isEqualTo(1)
            assertThat(notaries).isEmpty()
            assertThat(whitelistedContractImplementations).isEmpty()
        }
    }

    @Test
    fun `node conf file and corda jar`() {
        createNodeConfFile("node1", bobConfig)
        val fakeCordaJar = fakeFileBytes(rootDir / "corda.jar")
        bootstrap()
        assertBootstrappedNetwork(fakeCordaJar, "node1" to bobConfig)
    }

    @Test
    fun `single node directory with just node conf file`() {
        createNodeDir("bob", bobConfig)
        bootstrap()
        assertBootstrappedNetwork(fakeEmbeddedCordaJar, "bob" to bobConfig)
    }

    @Test
    fun `single node directory with node conf file and corda jar`() {
        val nodeDir = createNodeDir("bob", bobConfig)
        val fakeCordaJar = fakeFileBytes(nodeDir / "corda.jar")
        bootstrap()
        assertBootstrappedNetwork(fakeCordaJar, "bob" to bobConfig)
    }

    @Test
    fun `single node directory with just corda jar`() {
        val nodeCordaJar = (rootDir / "alice").createDirectories() / "corda.jar"
        val fakeCordaJar = fakeFileBytes(nodeCordaJar)
        assertThatThrownBy {
            bootstrap()
        }.hasMessageStartingWith("Missing node.conf in node directory alice")
        assertThat(nodeCordaJar).hasBinaryContent(fakeCordaJar)  // Make sure the corda.jar is left untouched
    }

    @Test
    fun `two node conf files, one of which is a notary`() {
        createNodeConfFile("alice", aliceConfig)
        createNodeConfFile("notary", notaryConfig)
        bootstrap()
        val networkParameters = assertBootstrappedNetwork(fakeEmbeddedCordaJar, "alice" to aliceConfig, "notary" to notaryConfig)
        networkParameters.assertContainsNotary()
    }

    @Test
    fun `two node conf files with the same legal name`() {
        val (node1File, node1Conf) = createNodeConfFile("node1", aliceConfig)
        val (node2File, node2Conf) = createNodeConfFile("node2", aliceConfig)
        assertThatThrownBy {
            bootstrap()
        }.hasMessageContaining("Nodes must have unique legal names")
        // Make sure the directory is left untouched
        assertThat(rootDir.list()).containsOnly(node1File, node2File)
        assertThat(node1File).hasContent(node1Conf)
        assertThat(node2File).hasContent(node2Conf)
    }

    @Test
    fun `one node directory and one node conf file`() {
        createNodeConfFile("alice", aliceConfig)
        createNodeDir("bob", bobConfig)
        bootstrap()
        assertBootstrappedNetwork(fakeEmbeddedCordaJar, "alice" to aliceConfig, "bob" to bobConfig)
    }

    @Test
    fun `node conf file and CorDapp jar`() {
        createNodeConfFile("alice", aliceConfig)
        val cordappJarFile = rootDir / "sample-app.jar"
        val cordappBytes = fakeFileBytes(cordappJarFile)
        val contractsJar = TestContractsJar(contractClassNames = listOf("contract.class"))
        contractsJars[cordappJarFile] = contractsJar
        bootstrap()
        val networkParameters = assertBootstrappedNetwork(fakeEmbeddedCordaJar, "alice" to aliceConfig)
        assertThat(rootDir / "alice" / "cordapps" / "sample-app.jar").hasBinaryContent(cordappBytes)
        assertThat(networkParameters.whitelistedContractImplementations).isEqualTo(mapOf(
                "contract.class" to listOf(contractsJar.hash)
        ))
    }

    @Test
    fun `no copy CorDapps`() {
        createNodeConfFile("alice", aliceConfig)
        val cordappJarFile = rootDir / "sample-app.jar"
        fakeFileBytes(cordappJarFile)
        val contractsJar = TestContractsJar(contractClassNames = listOf("contract.class"))
        contractsJars[cordappJarFile] = contractsJar
        bootstrap(copyCordapps = false)
        val networkParameters = assertBootstrappedNetwork(fakeEmbeddedCordaJar, "alice" to aliceConfig)
        assertThat(rootDir / "alice" / "cordapps" / "sample-app.jar").doesNotExist()
        assertThat(networkParameters.whitelistedContractImplementations).isEqualTo(mapOf(
                "contract.class" to listOf(contractsJar.hash)
        ))
    }

    @Test
    fun `bootstrap same network again`() {
        createNodeConfFile("alice", aliceConfig)
        createNodeConfFile("notary", notaryConfig)
        bootstrap()
        val networkParameters1 = (rootDir / "alice").networkParameters
        bootstrap()
        val networkParameters2 = assertBootstrappedNetwork(fakeEmbeddedCordaJar, "alice" to aliceConfig, "notary" to notaryConfig)
        assertThat(networkParameters1).isEqualTo(networkParameters2)
    }

    @Test
    fun `add notary to existing network`() {
        createNodeConfFile("alice", aliceConfig)
        bootstrap()
        createNodeConfFile("notary", notaryConfig)
        val networkParameters = assertBootstrappedNetwork(fakeEmbeddedCordaJar, "alice" to aliceConfig, "notary" to notaryConfig)
        networkParameters.assertContainsNotary()
        assertThat(networkParameters.epoch).isEqualTo(2)
    }

    private fun fakeFileBytes(writeToFile: Path? = null): ByteArray {
        val bytes = secureRandomBytes(128)
        writeToFile?.write(bytes)
        return bytes
    }

    private fun bootstrap(copyCordapps: Boolean = true) {
        bootstrapper.bootstrap(rootDir, copyCordapps)
    }

    private fun createNodeConfFile(nodeDirName: String, config: FakeNodeConfig): Pair<Path, String> {
        val file = rootDir / "${nodeDirName}_node.conf"
        val configText = config.toConfig().root().render()
        file.writeText(configText)
        return Pair(file, configText)
    }

    private fun createNodeDir(nodeDirName: String, config: FakeNodeConfig): Path {
        val nodeDir = (rootDir / nodeDirName).createDirectories()
        (nodeDir / "node.conf").writeText(config.toConfig().root().render())
        return nodeDir
    }

    private val Path.networkParameters: NetworkParameters get() {
        return (this / NETWORK_PARAMS_FILE_NAME).readObject<SignedNetworkParameters>().verifiedNetworkMapCert(DEV_ROOT_CA.certificate)
    }

    private fun assertBootstrappedNetwork(cordaJar: ByteArray, vararg nodes: Pair<String, FakeNodeConfig>): NetworkParameters {
        val networkParameters = (rootDir / nodes[0].first).networkParameters
        for ((nodeDirName, config) in nodes) {
            val nodeDir = rootDir / nodeDirName
            assertThat(nodeDir / "corda.jar").hasBinaryContent(cordaJar)
            assertThat(parseFakeNodeConfig(nodeDir)).isEqualTo(config)
            assertThat(nodeDir.networkParameters).isEqualTo(networkParameters)
            val nodeInfosDir = nodeDir / NODE_INFO_DIRECTORY
            for ((fileName, bytes) in nodeInfoFiles.values) {
                assertThat(nodeInfosDir / fileName).hasBinaryContent(bytes.bytes)
            }
        }
        return networkParameters
    }

    private fun NetworkParameters.assertContainsNotary() {
        assertThat(notaries).hasSize(1)
        notaries[0].run {
            assertThat(validating).isTrue()
            assertThat(identity.name).isEqualTo(notaryConfig.myLegalName)
            assertThat(identity.owningKey).isEqualTo(nodeInfoFiles[notaryConfig.myLegalName]!!.nodeInfo.legalIdentities[0].owningKey)
        }
    }

    private fun parseFakeNodeConfig(nodeDir: Path): FakeNodeConfig {
        return ConfigFactory.parseFile((nodeDir / "node.conf").toFile()).parseAs(FakeNodeConfig::class)
    }

    data class FakeNodeConfig(val myLegalName: CordaX500Name, val notary: NotaryConfig? = null)

    private data class NodeInfoInfo(val fileName: String, val bytes: OpaqueBytes, val nodeInfo: NodeInfo)
}
