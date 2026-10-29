package com.example.couponvault.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.overlayDataStore: DataStore<Preferences> by preferencesDataStore(name = "coupon_vault_overlay_prefs")

open class OverlayPreferences(context: Context? = null) {
    private val appContext: Context? = context?.applicationContext

    companion object {
        val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        val KEY_CURRENT_COUPON_ID = longPreferencesKey("current_coupon_id")
        val KEY_PREVIOUS_COUPON_ID = longPreferencesKey("previous_coupon_id")
        val KEY_AUTO_MARK_REDEEMED = booleanPreferencesKey("auto_mark_redeemed")
        val KEY_OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
        val KEY_OVERLAY_X = intPreferencesKey("overlay_x")
        val KEY_OVERLAY_Y = intPreferencesKey("overlay_y")
    }

    @Volatile
    private var cachedDeviceId: String? = null

    open val deviceIdFlow: Flow<String> = appContext?.overlayDataStore?.data?.map { prefs ->
        prefs[KEY_DEVICE_ID] ?: ""
    } ?: kotlinx.coroutines.flow.emptyFlow()

    open suspend fun getOrCreateDeviceId(): String {
        cachedDeviceId?.let { return it }
        val stored = appContext?.overlayDataStore?.data?.first()?.get(KEY_DEVICE_ID)
        if (!stored.isNullOrBlank()) {
            cachedDeviceId = stored
            return stored
        }
        val newId = java.util.UUID.randomUUID().toString()
        appContext?.overlayDataStore?.edit { prefs ->
            prefs[KEY_DEVICE_ID] = newId
        }
        cachedDeviceId = newId
        return newId
    }

    open val currentCouponIdFlow: Flow<Long?> = appContext?.overlayDataStore?.data?.map { prefs ->
        val id = prefs[KEY_CURRENT_COUPON_ID] ?: -1L
        if (id > 0) id else null
    } ?: kotlinx.coroutines.flow.emptyFlow()

    open val previousCouponIdFlow: Flow<Long?> = appContext?.overlayDataStore?.data?.map { prefs ->
        val id = prefs[KEY_PREVIOUS_COUPON_ID] ?: -1L
        if (id > 0) id else null
    } ?: kotlinx.coroutines.flow.emptyFlow()

    open val autoMarkRedeemedFlow: Flow<Boolean> = appContext?.overlayDataStore?.data?.map { prefs ->
        prefs[KEY_AUTO_MARK_REDEEMED] ?: true
    } ?: kotlinx.coroutines.flow.emptyFlow()

    open val overlayEnabledFlow: Flow<Boolean> = appContext?.overlayDataStore?.data?.map { prefs ->
        prefs[KEY_OVERLAY_ENABLED] ?: false
    } ?: kotlinx.coroutines.flow.emptyFlow()

    open val overlayPositionFlow: Flow<Pair<Int, Int>> = appContext?.overlayDataStore?.data?.map { prefs ->
        Pair(prefs[KEY_OVERLAY_X] ?: 80, prefs[KEY_OVERLAY_Y] ?: 320)
    } ?: kotlinx.coroutines.flow.emptyFlow()

    open suspend fun setCurrentCouponId(id: Long?) {
        appContext?.overlayDataStore?.edit { prefs ->
            if (id != null && id > 0) {
                prefs[KEY_CURRENT_COUPON_ID] = id
            } else {
                prefs.remove(KEY_CURRENT_COUPON_ID)
            }
        }
    }

    open suspend fun setPreviousCouponId(id: Long?) {
        appContext?.overlayDataStore?.edit { prefs ->
            if (id != null && id > 0) {
                prefs[KEY_PREVIOUS_COUPON_ID] = id
            } else {
                prefs.remove(KEY_PREVIOUS_COUPON_ID)
            }
        }
    }

    open suspend fun setAutoMarkRedeemed(enabled: Boolean) {
        appContext?.overlayDataStore?.edit { prefs ->
            prefs[KEY_AUTO_MARK_REDEEMED] = enabled
        }
    }

    open suspend fun setOverlayEnabled(enabled: Boolean) {
        appContext?.overlayDataStore?.edit { prefs ->
            prefs[KEY_OVERLAY_ENABLED] = enabled
        }
    }

    open suspend fun saveOverlayPosition(x: Int, y: Int) {
        appContext?.overlayDataStore?.edit { prefs ->
            prefs[KEY_OVERLAY_X] = x
            prefs[KEY_OVERLAY_Y] = y
        }
    }
}
