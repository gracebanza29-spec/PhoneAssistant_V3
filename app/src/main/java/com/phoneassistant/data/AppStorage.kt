package com.phoneassistant.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Stockage local simple via SharedPreferences + JSON.
 * Remplace Room/SQLite — aucun annotation processor requis.
 */
class AppStorage(context: Context) {

    private val prefs = context.getSharedPreferences("phone_assistant", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ── Numéros bloqués ───────────────────────────────────────────────────────
    fun getBlockedNumbers(): List<BlockedEntry> {
        val json = prefs.getString("blocked", "[]") ?: "[]"
        val type = object : TypeToken<List<BlockedEntry>>() {}.type
        return gson.fromJson(json, type)
    }

    fun blockNumber(number: String, reason: String = "") {
        val list = getBlockedNumbers().toMutableList()
        val normalized = normalize(number)
        if (list.none { it.normalized == normalized }) {
            list.add(BlockedEntry(number, normalized, reason, System.currentTimeMillis()))
            save("blocked", list)
        }
    }

    fun unblockNumber(number: String) {
        val normalized = normalize(number)
        val list = getBlockedNumbers().filter { it.normalized != normalized }
        save("blocked", list)
    }

    fun isBlocked(number: String): Boolean {
        val normalized = normalize(number)
        return getBlockedNumbers().any { it.normalized == normalized }
    }

    // ── Notes contacts ────────────────────────────────────────────────────────
    fun getNote(contactId: Long): String {
        return prefs.getString("note_$contactId", "") ?: ""
    }

    fun saveNote(contactId: Long, note: String) {
        prefs.edit().putString("note_$contactId", note).apply()
    }

    // ── Favoris ───────────────────────────────────────────────────────────────
    fun getFavorites(): List<Long> {
        val json = prefs.getString("favorites", "[]") ?: "[]"
        val type = object : TypeToken<List<Long>>() {}.type
        return gson.fromJson(json, type)
    }

    fun addFavorite(contactId: Long) {
        val list = getFavorites().toMutableList()
        if (!list.contains(contactId)) { list.add(contactId); save("favorites", list) }
    }

    fun removeFavorite(contactId: Long) {
        save("favorites", getFavorites().filter { it != contactId })
    }

    fun isFavorite(contactId: Long) = getFavorites().contains(contactId)

    // ── Cache Caller ID ───────────────────────────────────────────────────────
    fun getCachedCallerId(number: String): CallerIdResult? {
        val normalized = normalize(number)
        val json = prefs.getString("cid_$normalized", null) ?: return null
        val cached = gson.fromJson(json, CachedCallerId::class.java) ?: return null
        // Expirer après 30 jours
        if (System.currentTimeMillis() - cached.timestamp > 30L * 86_400_000) return null
        return cached.result
    }

    fun cacheCallerId(number: String, result: CallerIdResult) {
        val normalized = normalize(number)
        prefs.edit().putString("cid_$normalized", gson.toJson(CachedCallerId(result, System.currentTimeMillis()))).apply()
    }

    // ── Utilitaires ───────────────────────────────────────────────────────────
    private fun normalize(number: String) = number.replace(Regex("[^0-9+]"), "")
    private fun <T> save(key: String, data: T) = prefs.edit().putString(key, gson.toJson(data)).apply()
}

data class BlockedEntry(
    val number: String,
    val normalized: String,
    val reason: String,
    val blockedAt: Long
)

data class CallerIdResult(
    val number: String,
    val carrier: String?,
    val lineType: String?,
    val location: String?,
    val isSpam: Boolean
)

data class CachedCallerId(val result: CallerIdResult, val timestamp: Long)
