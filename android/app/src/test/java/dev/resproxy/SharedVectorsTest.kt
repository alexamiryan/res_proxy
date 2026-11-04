package dev.resproxy

import dev.resproxy.data.BackupCrypto
import dev.resproxy.data.BackupPayload
import dev.resproxy.data.Profile
import dev.resproxy.provider.Endpoint
import dev.resproxy.provider.Presets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Cross-platform vectors in `shared/test-vectors` (see shared/FORMAT.md): the Chrome extension and future iOS
 * app run the same checks, so a backup made on one platform imports on the others.
 * `WRITE_VECTORS=1 ./gradlew testDebugUnitTest` regenerates the Android-made backup.
 */
class SharedVectorsTest {
    private val dir = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "shared/test-vectors") }.first { it.isDirectory }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val passphrase = "correct horse battery staple"
    private val payload by lazy { json.decodeFromString(BackupPayload.serializer(), File(dir, "backup-payload.json").readText()) }

    @Test fun presetsMatchSharedCases() {
        val cases = json.parseToJsonElement(File(dir, "presets.json").readText()).jsonObject["cases"]!!.jsonArray
        for (c in cases) {
            val o = c.jsonObject
            val name = o["name"]!!.jsonPrimitive.content
            val p = json.decodeFromJsonElement(Profile.serializer(), o["profile"]!!)
            val e = o["endpoint"]!!.jsonObject
            val want = Endpoint(
                e["host"]!!.jsonPrimitive.content, e["port"]!!.jsonPrimitive.int,
                dev.resproxy.data.Protocol.valueOf(e["protocol"]!!.jsonPrimitive.content),
                e["username"]!!.jsonPrimitive.content, e["password"]!!.jsonPrimitive.content,
            )
            assertEquals(name, want, Presets.build(p))
        }
    }

    @Test fun opensBackupMadeByAndroid() {
        assertEquals(payload, BackupCrypto.open(File(dir, "backup-android.txt").readText(), passphrase.toCharArray()))
    }

    @Test fun opensBackupMadeByChrome() {
        val f = File(dir, "backup-chrome.txt")
        assumeTrue("run the Chrome tests first", f.exists())
        assertEquals(payload, BackupCrypto.open(f.readText(), passphrase.toCharArray()))
    }

    @Test fun writeAndroidVector() {
        assumeTrue(System.getenv("WRITE_VECTORS") == "1")
        File(dir, "backup-android.txt").writeText(BackupCrypto.seal(payload, passphrase.toCharArray()) + "\n")
    }
}
