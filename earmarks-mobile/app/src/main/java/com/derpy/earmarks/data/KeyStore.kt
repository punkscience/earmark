package com.derpy.earmarks.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

/**
 * The app's single preferences DataStore. Internal rather than private because
 * [SettingsStore] shares it — two `preferencesDataStore` delegates with the
 * same name in one process throws at runtime.
 */
internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "earmarks_prefs")

private val PRIV_KEY_HEX = stringPreferencesKey("priv_key_hex")

class KeyStore(private val context: Context) {
    suspend fun getKey(): String? =
        context.dataStore.data.map { it[PRIV_KEY_HEX] }.firstOrNull()

    suspend fun saveKey(hexKey: String) {
        context.dataStore.edit { it[PRIV_KEY_HEX] = hexKey }
    }

    suspend fun clearKey() {
        context.dataStore.edit { it.remove(PRIV_KEY_HEX) }
    }
}
