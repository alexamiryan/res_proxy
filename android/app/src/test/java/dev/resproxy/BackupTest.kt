package dev.resproxy

import dev.resproxy.data.Account
import dev.resproxy.data.ApiKeys
import dev.resproxy.data.AppSettings
import dev.resproxy.data.BackupCrypto
import dev.resproxy.data.BackupPayload
import dev.resproxy.data.Location
import dev.resproxy.data.Provider
import dev.resproxy.data.VpnChain
import dev.resproxy.data.WrongPassphraseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {

    private val payload = BackupPayload(
        createdAt = 1_700_000_000_000,
        settings = AppSettings(provider = Provider.GEONODE, location = Location(country = "fr", countryName = "France"), chainEnabled = true),
        accounts = listOf(
            Account(Provider.IPROYAL, "ipuser", "ippass", apiToken = "tok"),
            Account(Provider.GEONODE, "geonode_x", "gpass", apiLogin = "me@example.com", apiPassword = "dash"),
        ),
        chain = VpnChain("ch-zrh", "[Interface]\nPrivateKey = secret\n"),
        apiKeys = ApiKeys(abuseIpDb = "abc"),
    )

    @Test fun roundTrips() {
        val text = BackupCrypto.seal(payload, "correct horse".toCharArray())
        assertTrue(BackupCrypto.looksLikeBackup(text))
        // Nothing sensitive in the clear.
        listOf("ippass", "gpass", "secret", "geonode_x", "tok").forEach { assertFalse(it, text.contains(it)) }
        assertEquals(payload, BackupCrypto.open(text, "correct horse".toCharArray()))
        // Tolerates whitespace/line wrapping added by messengers.
        assertEquals(payload, BackupCrypto.open(text.chunked(60).joinToString("\n"), "correct horse".toCharArray()))
    }

    @Test(expected = WrongPassphraseException::class) fun wrongPassphraseFails() {
        BackupCrypto.open(BackupCrypto.seal(payload, "correct horse".toCharArray()), "wrong".toCharArray())
    }

    @Test fun saltMakesEachExportDifferent() {
        val p = "pw123456".toCharArray()
        assertTrue(BackupCrypto.seal(payload, p) != BackupCrypto.seal(payload, p))
    }
}
