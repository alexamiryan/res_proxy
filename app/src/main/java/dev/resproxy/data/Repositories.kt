package dev.resproxy.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Provider accounts, stored as one Keystore-encrypted JSON blob. */
class AccountRepository(private val store: DataStore<Preferences>, private val secrets: SecretStore) {

    private val mapSerializer = MapSerializer(Provider.serializer(), Account.serializer())

    val accounts: Flow<Map<Provider, Account>> = store.data.map { decode(it) }

    fun account(provider: Provider): Flow<Account?> = accounts.map { it[provider] }

    suspend fun get(provider: Provider): Account? = accounts.first()[provider]

    suspend fun save(account: Account) {
        store.edit { prefs ->
            val m = decode(prefs) + (account.provider to account)
            prefs[KEY] = secrets.encrypt(json.encodeToString(mapSerializer, m))
        }
    }

    /** Replaces all accounts (backup import). */
    suspend fun replaceAll(accounts: List<Account>) {
        store.edit { prefs ->
            prefs[KEY] = secrets.encrypt(json.encodeToString(mapSerializer, accounts.associateBy { it.provider }))
        }
    }

    /** One-time import of credentials saved by the old profile editor; returns that profile's location. */
    suspend fun migrateLegacyProfiles(): Location? {
        var location: Location? = null
        store.edit { prefs ->
            val raw = prefs[LEGACY_PROFILES] ?: return@edit
            val legacy = runCatching { json.decodeFromString(ListSerializer(Profile.serializer()), raw) }.getOrDefault(emptyList())
            val current = decode(prefs)
            val p = legacy.firstOrNull { it.provider == Provider.IPROYAL && it.username.isNotBlank() }
            if (p != null && Provider.IPROYAL !in current) {
                val acc = Account(Provider.IPROYAL, p.username.trim(), secrets.decrypt(p.password).trim())
                prefs[KEY] = secrets.encrypt(json.encodeToString(mapSerializer, current + (Provider.IPROYAL to acc)))
                val cc = p.country.trim().lowercase()
                location = Location(
                    country = cc, countryName = if (cc.isEmpty()) "" else java.util.Locale("", cc).displayCountry,
                    state = p.state.trim().lowercase().replace(" ", ""), stateName = p.state.trim(),
                    city = p.city.trim().lowercase().replace(" ", ""), cityName = p.city.trim(),
                )
            }
            prefs.remove(LEGACY_PROFILES)
        }
        return location
    }

    private fun decode(prefs: Preferences): Map<Provider, Account> =
        prefs[KEY]?.let { runCatching { json.decodeFromString(mapSerializer, secrets.decrypt(it)) }.getOrNull() } ?: emptyMap()

    private companion object {
        val KEY = stringPreferencesKey("accounts")
        val LEGACY_PROFILES = stringPreferencesKey("profiles")
    }
}

class SettingsRepository(private val store: DataStore<Preferences>, private val secrets: SecretStore) {

    val settings: Flow<AppSettings> = store.data.map { prefs -> upgrade(decode(prefs)) }

    val apiKeys: Flow<ApiKeys> = store.data.map { prefs ->
        prefs[KEYS]?.let {
            runCatching { json.decodeFromString(ApiKeys.serializer(), secrets.decrypt(it)) }.getOrNull()
        } ?: ApiKeys()
    }

    suspend fun current(): AppSettings = settings.first()
    suspend fun currentKeys(): ApiKeys = apiKeys.first()

    suspend fun update(block: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            prefs[SETTINGS] = json.encodeToString(AppSettings.serializer(), block(upgrade(decode(prefs))))
        }
    }

    /** Switches provider, remembering each provider's last location. */
    suspend fun switchProvider(to: Provider) = update { s ->
        if (s.provider == to) s
        else s.copy(
            provider = to,
            location = s.savedLocations[to] ?: Location(),
            savedLocations = s.savedLocations + (s.provider to s.location),
            sessionId = "",
        )
    }

    /** Persists the schema upgrade, adopting [legacyLocation] if no location is set yet. */
    suspend fun migrate(legacyLocation: Location?) = update { s ->
        if (legacyLocation != null && s.location == Location()) s.copy(location = legacyLocation) else s
    }

    suspend fun setKeys(keys: ApiKeys) {
        store.edit { it[KEYS] = secrets.encrypt(json.encodeToString(ApiKeys.serializer(), keys)) }
    }

    private fun decode(prefs: Preferences): AppSettings =
        prefs[SETTINGS]?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()

    /** Schema 0/1 → 2: new defaults for kill switch, auto-connect and auto-rotate. */
    private fun upgrade(s: AppSettings): AppSettings =
        if (s.schema >= AppSettings.SCHEMA) s
        else s.copy(
            schema = AppSettings.SCHEMA,
            killSwitch = true,
            autoConnect = true,
            autoRotate = true,
            rotateThreshold = Verdict.SUSPICIOUS,
            maxRotations = 10,
        )

    private companion object {
        val SETTINGS = stringPreferencesKey("settings")
        val KEYS = stringPreferencesKey("api_keys")
    }
}

/** Imported WireGuard profiles and the Mullvad account, both Keystore-encrypted. */
class VpnRepository(private val store: DataStore<Preferences>, private val secrets: SecretStore) {

    private val listSerializer = ListSerializer(VpnProfile.serializer())

    val profiles: Flow<List<VpnProfile>> = store.data.map { prefs ->
        prefs[PROFILES]?.let { runCatching { json.decodeFromString(listSerializer, secrets.decrypt(it)) }.getOrNull() } ?: emptyList()
    }

    val mullvad: Flow<MullvadAccount?> = store.data.map { prefs ->
        prefs[MULLVAD]?.let { runCatching { json.decodeFromString(MullvadAccount.serializer(), secrets.decrypt(it)) }.getOrNull() }
    }

    suspend fun profiles(): List<VpnProfile> = profiles.first()
    suspend fun mullvad(): MullvadAccount? = mullvad.first()

    suspend fun saveProfiles(list: List<VpnProfile>) {
        store.edit { it[PROFILES] = secrets.encrypt(json.encodeToString(listSerializer, list)) }
    }

    suspend fun upsert(p: VpnProfile) {
        val cur = profiles()
        saveProfiles(if (cur.any { it.id == p.id }) cur.map { if (it.id == p.id) p else it } else cur + p)
    }

    suspend fun remove(id: String) = saveProfiles(profiles().filterNot { it.id == id })

    suspend fun saveMullvad(a: MullvadAccount?) {
        store.edit { prefs ->
            if (a == null) prefs.remove(MULLVAD) else prefs[MULLVAD] = secrets.encrypt(json.encodeToString(MullvadAccount.serializer(), a))
        }
    }

    /** Moves the pre-profiles single chain config into the profile list; returns its new id. */
    suspend fun migrateLegacyChain(): String? {
        var id: String? = null
        store.edit { prefs ->
            val raw = prefs[LEGACY_CHAIN] ?: return@edit
            val chain = runCatching { json.decodeFromString(VpnChain.serializer(), secrets.decrypt(raw)) }.getOrNull()
            if (chain != null && chain.config.isNotBlank()) {
                val cur = prefs[PROFILES]?.let { runCatching { json.decodeFromString(listSerializer, secrets.decrypt(it)) }.getOrNull() }.orEmpty()
                val p = VpnProfile(name = chain.name, config = chain.config)
                prefs[PROFILES] = secrets.encrypt(json.encodeToString(listSerializer, cur + p))
                id = p.id
            }
            prefs.remove(LEGACY_CHAIN)
        }
        return id
    }

    private companion object {
        val PROFILES = stringPreferencesKey("vpn_profiles")
        val MULLVAD = stringPreferencesKey("mullvad")
        val LEGACY_CHAIN = stringPreferencesKey("vpn_chain")
    }
}

class StatsRepository(private val store: DataStore<Preferences>) {

    private val mapSerializer = MapSerializer(String.serializer(), UsageTotals.serializer())

    val totals: Flow<Map<String, UsageTotals>> = store.data.map { decode(it) }

    suspend fun add(key: String, rx: Long, tx: Long) {
        if (rx == 0L && tx == 0L) return
        store.edit { prefs ->
            val m = decode(prefs).toMutableMap()
            val cur = m[key] ?: UsageTotals()
            m[key] = UsageTotals(cur.rxBytes + rx, cur.txBytes + tx)
            prefs[KEY] = json.encodeToString(mapSerializer, m)
        }
    }

    suspend fun reset(key: String) {
        store.edit { prefs -> prefs[KEY] = json.encodeToString(mapSerializer, decode(prefs) - key) }
    }

    private fun decode(prefs: Preferences): Map<String, UsageTotals> =
        prefs[KEY]?.let { runCatching { json.decodeFromString(mapSerializer, it) }.getOrNull() } ?: emptyMap()

    private companion object {
        val KEY = stringPreferencesKey("usage")
    }
}
