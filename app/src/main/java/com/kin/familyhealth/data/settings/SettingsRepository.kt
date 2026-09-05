package com.kin.familyhealth.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Reach-in behavior selection (see ARCHITECTURE.md "Reach-in flow").
 * SELF_ANSWER: primary path, FCM-driven foreground service + full-screen intent.
 * ACCESSIBILITY: opt-in fallback that taps the system dialer's Answer action.
 */
enum class ReachInMode {
    SELF_ANSWER,
    ACCESSIBILITY,
}

private val Context.dataStore by preferencesDataStore(name = "kin_settings")

/**
 * FOUNDATION-owned shared contract. DataStore-backed settings consumed by
 * every feature package. Feature agents read the exposed Flows / call the
 * suspend setters — do not redefine this repository elsewhere.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val PARTNER_UID = stringPreferencesKey("partner_uid")
        val MY_DISPLAY_NAME = stringPreferencesKey("my_display_name")
        val REACH_IN_MODE = stringPreferencesKey("reach_in_mode")
        val ACCESSIBILITY_OPT_IN = booleanPreferencesKey("accessibility_opt_in")
    }

    val partnerUid: Flow<String?> = context.dataStore.data.map { it[Keys.PARTNER_UID] }

    val myDisplayName: Flow<String?> = context.dataStore.data.map { it[Keys.MY_DISPLAY_NAME] }

    val reachInMode: Flow<ReachInMode> = context.dataStore.data.map { prefs ->
        prefs[Keys.REACH_IN_MODE]?.let { stored ->
            runCatching { ReachInMode.valueOf(stored) }.getOrNull()
        } ?: ReachInMode.SELF_ANSWER
    }

    val accessibilityOptIn: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.ACCESSIBILITY_OPT_IN] ?: false
    }

    suspend fun setPartnerUid(uid: String?) {
        context.dataStore.edit { prefs ->
            if (uid == null) prefs.remove(Keys.PARTNER_UID) else prefs[Keys.PARTNER_UID] = uid
        }
    }

    suspend fun setMyDisplayName(name: String?) {
        context.dataStore.edit { prefs ->
            if (name == null) prefs.remove(Keys.MY_DISPLAY_NAME) else prefs[Keys.MY_DISPLAY_NAME] = name
        }
    }

    suspend fun setReachInMode(mode: ReachInMode) {
        context.dataStore.edit { prefs -> prefs[Keys.REACH_IN_MODE] = mode.name }
    }

    suspend fun setAccessibilityOptIn(optIn: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.ACCESSIBILITY_OPT_IN] = optIn }
    }
}
