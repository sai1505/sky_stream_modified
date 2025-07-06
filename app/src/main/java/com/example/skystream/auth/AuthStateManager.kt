package com.example.skystream.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import net.openid.appauth.AuthState
import org.json.JSONException
import java.lang.ref.WeakReference

class AuthStateManager private constructor(context: Context) {

    private val mPrefs: SharedPreferences = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
    private val mPrefsLock = Any()

    companion object {
        private const val TAG = "AuthStateManager"
        private const val STORE_NAME = "AuthState"
        private const val KEY_STATE = "state"
        private const val KEY_CURRENT_ACCOUNT = "current_account"
        private const val KEY_ACCOUNT_PREFIX = "account_"

        @Volatile
        private var INSTANCE: AuthStateManager? = null

        fun getInstance(context: Context): AuthStateManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AuthStateManager(context).also { INSTANCE = it }
            }
        }
    }

    // Get current AuthState
    fun getCurrent(): AuthState {
        synchronized(mPrefsLock) {
            val currentAccount = mPrefs.getString(KEY_CURRENT_ACCOUNT, null)
            if (currentAccount != null) {
                val stateJson = mPrefs.getString("$KEY_ACCOUNT_PREFIX$currentAccount", null)
                if (stateJson != null) {
                    try {
                        return AuthState.jsonDeserialize(stateJson)
                    } catch (ex: JSONException) {
                        Log.w(TAG, "Failed to deserialize stored auth state - discarding")
                    }
                }
            }
            return AuthState()
        }
    }

    // Replace current AuthState
    fun replace(state: AuthState, accountId: String) {
        synchronized(mPrefsLock) {
            val stateJson = state.jsonSerializeString()
            mPrefs.edit()
                .putString("$KEY_ACCOUNT_PREFIX$accountId", stateJson)
                .putString(KEY_CURRENT_ACCOUNT, accountId)
                .apply()
        }
    }

    // Get all stored accounts
    fun getAllAccounts(): Map<String, AuthState> {
        synchronized(mPrefsLock) {
            val accounts = mutableMapOf<String, AuthState>()
            val allPrefs = mPrefs.all

            for ((key, value) in allPrefs) {
                if (key.startsWith(KEY_ACCOUNT_PREFIX) && value is String) {
                    val accountId = key.removePrefix(KEY_ACCOUNT_PREFIX)
                    try {
                        val authState = AuthState.jsonDeserialize(value)
                        accounts[accountId] = authState
                    } catch (ex: JSONException) {
                        Log.w(TAG, "Failed to deserialize account $accountId - skipping")
                    }
                }
            }
            return accounts
        }
    }

    // Switch to different account
    fun switchToAccount(accountId: String): Boolean {
        synchronized(mPrefsLock) {
            val accountKey = "$KEY_ACCOUNT_PREFIX$accountId"
            if (mPrefs.contains(accountKey)) {
                mPrefs.edit()
                    .putString(KEY_CURRENT_ACCOUNT, accountId)
                    .apply()
                return true
            }
            return false
        }
    }

    // Remove specific account
    fun removeAccount(accountId: String) {
        synchronized(mPrefsLock) {
            val currentAccount = mPrefs.getString(KEY_CURRENT_ACCOUNT, null)
            val editor = mPrefs.edit()
                .remove("$KEY_ACCOUNT_PREFIX$accountId")

            // If removing current account, clear current selection
            if (currentAccount == accountId) {
                editor.remove(KEY_CURRENT_ACCOUNT)
            }

            editor.apply()
        }
    }

    // Clear all authentication data
    fun clearAll() {
        synchronized(mPrefsLock) {
            mPrefs.edit().clear().apply()
        }
    }

    // Get current account ID
    fun getCurrentAccountId(): String? {
        return mPrefs.getString(KEY_CURRENT_ACCOUNT, null)
    }

    // Check if any account is stored
    fun hasStoredAccounts(): Boolean {
        val allPrefs = mPrefs.all
        return allPrefs.keys.any { it.startsWith(KEY_ACCOUNT_PREFIX) }
    }
}
