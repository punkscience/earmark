package com.derpy.earmarks.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val DOWNLOAD_ON_MOBILE_DATA = booleanPreferencesKey("download_on_mobile_data")

/**
 * User-adjustable behaviour. Currently just the one switch.
 */
class SettingsStore(private val context: Context) {

    /**
     * Whether background downloads may run on a metered connection.
     *
     * Off by default. A stash is whole albums at 16 MiB a chunk, so an
     * unattended sync that ignored metering would quietly eat a data plan —
     * that is a decision to opt into, not out of.
     */
    val downloadOnMobileData: Flow<Boolean> =
        context.dataStore.data.map { it[DOWNLOAD_ON_MOBILE_DATA] ?: false }

    suspend fun downloadOnMobileDataNow(): Boolean = downloadOnMobileData.first()

    suspend fun setDownloadOnMobileData(enabled: Boolean) {
        context.dataStore.edit { it[DOWNLOAD_ON_MOBILE_DATA] = enabled }
    }
}
