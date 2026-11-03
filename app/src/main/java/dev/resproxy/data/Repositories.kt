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

class ProfileRepository(private val store: DataStore<Preferences>, private val secrets: SecretStore) {

    private val listSerializer = ListSerializer(Profile.serializer())

    val profiles: Flow<List<Profile>> = store.data.map { prefs ->
        prefs[KEY]?.let { raw ->
            runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
                .map { it.copy(password = secrets.decrypt(it.password)) }
        } ?: emptyList()
    }

    suspend fun all(): List<Profile> = profiles.first()
    suspend fun get(id: String?): Profile? = id?.let { all().firstOrNull { p -> p.id == id } }

    suspend fun save(profile: Profile) = mutate { list ->
        val i = list.indexOfFirst { it.id == profile.id }
        if (i >= 0) list.toMutableList().apply { set(i, profile) } else list + profile
    }

    suspend fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    private suspend fun mutate(block: (List<Profile>) -> List<Profile>) {
        store.edit { prefs ->
            val current = prefs[KEY]?.let {
                runCatching { json.decodeFromString(listSerializer, it) }.getOrDefault(emptyList())
            } ?: emptyList()
            // `current` holds ciphertext; decrypt so callers see plain values, re-encrypt on write.
            val updated = block(current.map { it.copy(password = secrets.decrypt(it.password)) })
            prefs[KEY] = json.encodeToString(
                listSerializer, updated.map { it.copy(password = secrets.encrypt(it.password)) }
            )
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("profiles")
    }
}

class SettingsRepository(private val store: DataStore<Preferences>, private val secrets: SecretStore) {

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        prefs[SETTINGS]?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() }
            ?: AppSettings()
    }

    val apiKeys: Flow<ApiKeys> = store.data.map { prefs ->
        prefs[KEYS]?.let {
            runCatching { json.decodeFromString(ApiKeys.serializer(), secrets.decrypt(it)) }.getOrNull()
        } ?: ApiKeys()
    }

    suspend fun current(): AppSettings = settings.first()
    suspend fun currentKeys(): ApiKeys = apiKeys.first()

    suspend fun update(block: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val cur = prefs[SETTINGS]?.let {
                runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull()
            } ?: AppSettings()
            prefs[SETTINGS] = json.encodeToString(AppSettings.serializer(), block(cur))
        }
    }

    suspend fun setKeys(keys: ApiKeys) {
        store.edit { it[KEYS] = secrets.encrypt(json.encodeToString(ApiKeys.serializer(), keys)) }
    }

    private companion object {
        val SETTINGS = stringPreferencesKey("settings")
        val KEYS = stringPreferencesKey("api_keys")
    }
}

class StatsRepository(private val store: DataStore<Preferences>) {

    private val mapSerializer = MapSerializer(String.serializer(), UsageTotals.serializer())

    val totals: Flow<Map<String, UsageTotals>> = store.data.map { decode(it) }

    suspend fun add(profileId: String, rx: Long, tx: Long) {
        if (rx == 0L && tx == 0L) return
        store.edit { prefs ->
            val m = decode(prefs).toMutableMap()
            val cur = m[profileId] ?: UsageTotals()
            m[profileId] = UsageTotals(cur.rxBytes + rx, cur.txBytes + tx)
            prefs[KEY] = json.encodeToString(mapSerializer, m)
        }
    }

    suspend fun reset(profileId: String) {
        store.edit { prefs -> prefs[KEY] = json.encodeToString(mapSerializer, decode(prefs) - profileId) }
    }

    private fun decode(prefs: Preferences): Map<String, UsageTotals> =
        prefs[KEY]?.let { runCatching { json.decodeFromString(mapSerializer, it) }.getOrNull() } ?: emptyMap()

    private companion object {
        val KEY = stringPreferencesKey("usage")
    }
}
