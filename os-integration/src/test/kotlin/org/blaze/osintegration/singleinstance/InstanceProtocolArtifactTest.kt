package org.blaze.osintegration.singleinstance

import org.blaze.osintegration.ExternalOpenParser
import org.blaze.osintegration.ExternalOpenRequest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Wire round-trips for the artifact kinds only the window-drop surface produces (link lists and
 * partial-download candidates). The forwarder and the owner share this machine, so re-validation
 * on arrival probes real paths - the tests therefore use genuinely existing files/folders.
 */
class InstanceProtocolArtifactTest {

    private val parser = ExternalOpenParser()

    @Test
    fun `link list and partial artifacts survive an encode-decode round trip`() {
        val dir = Files.createTempDirectory("blaze-protocol-artifacts")
        try {
            val list = Files.createTempFile(dir, "links", ".txt")
            val part = Files.createTempFile(dir, "big.iso", ".part")

            val requests = listOf(
                ExternalOpenRequest.LinkListFile(list),
                ExternalOpenRequest.PartialDownload(part),
                ExternalOpenRequest.PartialDownload(dir),
            )
            val frame = InstanceProtocol.decode(InstanceProtocol.encode("t", requests))
            assertEquals(requests, frame?.let { InstanceProtocol.toRequests(it, parser) })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a forwarded artifact whose path vanished drops the frame, never fabricates a request`() {
        val gone = "/definitely/not/here/links.txt"
        val frame = InstanceProtocol.decode(
            InstanceProtocol.encode("t", listOf(ExternalOpenRequest.LinkListFile(java.nio.file.Path.of(gone))))
        )
        // Encode is name-based, but the receiving side re-probes: the entry cannot be re-validated.
        assertEquals(null, frame?.let { InstanceProtocol.toRequests(it, parser) })
    }

    @Test
    fun `unknown future kinds are dropped rather than executed`() {
        val frame = InstanceProtocol.decode("""{"token":"t","requests":[{"kind":"exe","value":"/tmp/x"}]}""")
        assertEquals(null, frame?.let { InstanceProtocol.toRequests(it, parser) })
    }
}
