package com.linvpn.app.data

import android.content.Context
import android.content.SharedPreferences
import com.linvpn.app.core.LogBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

object Store {
    private lateinit var prefs: SharedPreferences

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    @Synchronized
    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("lin_store", Context.MODE_PRIVATE)
        _profiles.value = runCatching {
            json.decodeFromString<List<Profile>>(prefs.getString("profiles", "[]") ?: "[]")
        }.getOrDefault(emptyList())
        _settings.value = runCatching {
            json.decodeFromString<AppSettings>(prefs.getString("settings", "{}") ?: "{}")
        }.getOrDefault(AppSettings())
        LogBus.maxLines = _settings.value.logMax
    }

    @Synchronized
    fun saveProfile(p: Profile) {
        val l = _profiles.value.toMutableList()
        val i = l.indexOfFirst { it.id == p.id }
        if (i >= 0) l[i] = p else l.add(p)
        persistProfiles(l)
        if (_settings.value.lastProfileId.isBlank()) updateSettings { it.copy(lastProfileId = p.id) }
    }

    @Synchronized
    fun deleteProfile(id: String) {
        persistProfiles(_profiles.value.filterNot { it.id == id })
        if (_settings.value.lastProfileId == id) {
            updateSettings { it.copy(lastProfileId = _profiles.value.firstOrNull()?.id ?: "") }
        }
    }

    @Synchronized
    fun duplicateProfile(id: String) {
        val p = _profiles.value.firstOrNull { it.id == id } ?: return
        saveProfile(p.copy(id = UUID.randomUUID().toString(), name = p.name + " (salinan)"))
    }

    @Synchronized
    fun updateSettings(block: (AppSettings) -> AppSettings) {
        val s = block(_settings.value)
        _settings.value = s
        LogBus.maxLines = s.logMax
        prefs.edit().putString("settings", json.encodeToString(s)).apply()
    }

    fun exportProfiles(): String = json.encodeToString(_profiles.value)

    /** Mengembalikan jumlah profil yang berhasil diimpor, atau -1 bila format salah. */
    fun importProfiles(text: String): Int {
        return try {
            val list = json.decodeFromString<List<Profile>>(text)
            list.forEach { saveProfile(it.copy(id = UUID.randomUUID().toString())) }
            list.size
        } catch (e: Exception) {
            -1
        }
    }

    private fun persistProfiles(l: List<Profile>) {
        _profiles.value = l
        prefs.edit().putString("profiles", json.encodeToString(l)).apply()
    }
}
