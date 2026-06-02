package com.phoneassistant.data

import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import com.phoneassistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ── Modèles ────────────────────────────────────────────────────────────────

data class Contact(
    val id: Long,
    val name: String,
    val phones: List<Phone>,
    val photoUri: String? = null,
    val isFavorite: Boolean = false,
    val note: String = ""
)

data class Phone(val number: String, val type: String)

data class CallEntry(
    val id: Long,
    val number: String,
    val name: String?,
    val callerInfo: String?,
    val callType: CallType,
    val duration: Long,
    val timestamp: Long
)

enum class CallType { INCOMING, OUTGOING, MISSED, REJECTED, BLOCKED }

// ── Contacts Repository ─────────────────────────────────────────────────────

class ContactsRepo(private val ctx: Context) {

    suspend fun getAll(): List<Contact> = withContext(Dispatchers.IO) {
        val storage = AppStorage(ctx)
        val favorites = storage.getFavorites()
        val map = mutableMapOf<Long, Contact>()

        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI
            ),
            null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY + " ASC"
        )?.use { c ->
            val idCol    = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameCol  = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val numCol   = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val typeCol  = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
            val photoCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)

            while (c.moveToNext()) {
                val id    = c.getLong(idCol)
                val name  = c.getString(nameCol) ?: continue
                val num   = c.getString(numCol) ?: continue
                val type  = ContactsContract.CommonDataKinds.Phone
                    .getTypeLabel(ctx.resources, c.getInt(typeCol), "Autre").toString()
                val photo = c.getString(photoCol)
                val isFav = favorites.contains(id)
                val note  = storage.getNote(id)
                val phone = Phone(num, type)
                val prev  = map[id]
                map[id] = prev?.copy(phones = prev.phones + phone)
                    ?: Contact(id, name, listOf(phone), photo, isFav, note)
            }
        }
        map.values.toList()
    }

    suspend fun search(query: String) = getAll().filter { c ->
        val q = query.lowercase()
        c.name.lowercase().contains(q) || c.phones.any { it.number.contains(q) }
    }

    suspend fun findByNumber(number: String): Contact? {
        val clean = number.replace(Regex("[^0-9]"), "").takeLast(9)
        return getAll().firstOrNull { c ->
            c.phones.any { p -> p.number.replace(Regex("[^0-9]"), "").takeLast(9) == clean }
        }
    }
}

// ── Call Log Repository ─────────────────────────────────────────────────────

class CallLogRepo(private val ctx: Context) {

    suspend fun getAll(limit: Int = 200): List<CallEntry> = withContext(Dispatchers.IO) {
        val list = mutableListOf<CallEntry>()
        try {
            // Utiliser le paramètre "limit" dans l'URI plutôt que LIMIT dans sortOrder
            // (la clause LIMIT dans sortOrder est rejetée par certains appareils Samsung)
            val uri = CallLog.Calls.CONTENT_URI.buildUpon()
                .appendQueryParameter("limit", limit.toString())
                .build()
            ctx.contentResolver.query(
                uri,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                        CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE),
                null, null, "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                val idC   = c.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numC  = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameC = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val typeC = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val durC  = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val dateC = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
                while (c.moveToNext()) {
                    val t = when (c.getInt(typeC)) {
                        CallLog.Calls.INCOMING_TYPE -> CallType.INCOMING
                        CallLog.Calls.OUTGOING_TYPE -> CallType.OUTGOING
                        CallLog.Calls.MISSED_TYPE   -> CallType.MISSED
                        CallLog.Calls.REJECTED_TYPE -> CallType.REJECTED
                        CallLog.Calls.BLOCKED_TYPE  -> CallType.BLOCKED
                        else -> CallType.INCOMING
                    }
                    list.add(CallEntry(c.getLong(idC), c.getString(numC) ?: "",
                        c.getString(nameC)?.takeIf { it.isNotBlank() },
                        null, t, c.getLong(durC), c.getLong(dateC)))
                }
            }
        } catch (_: SecurityException) {
            // Permission READ_CALL_LOG non accordée — retourner liste vide
        } catch (_: Exception) {
            // Autre erreur (fournisseur de contenu Samsung, etc.) — retourner liste vide
        }
        list
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        try {
            ctx.contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls._ID}=?",
                arrayOf(id.toString())
            )
        } catch (_: Exception) { /* Permission non accordée */ }
    }
}

// ── Caller ID Repository (OkHttp direct, pas de Retrofit) ──────────────────

class CallerIdRepo(private val ctx: Context) {

    private val storage = AppStorage(ctx)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun identify(number: String): CallerIdResult? = withContext(Dispatchers.IO) {
        // 1. Cache local
        storage.getCachedCallerId(number)?.let { return@withContext it }

        // 2. API
        val apiKey = BuildConfig.CALLER_ID_API_KEY
        if (apiKey == "VOTRE_CLE_API" || apiKey.isBlank()) return@withContext null

        return@withContext try {
            val normalized = number.replace(Regex("[^0-9+]"), "")
            val url = "https://api.numlookupapi.com/v1/validate?apikey=$apiKey&number=$normalized"
            val response = http.newCall(Request.Builder().url(url).build()).execute()
            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)

            val lineType = json.optString("line_type", "")
            val lineLabel = when (lineType) {
                "mobile"       -> "📱 Mobile"
                "fixed_line"   -> "☎️ Fixe"
                "voip"         -> "🌐 VoIP"
                "toll_free"    -> "🟢 Numéro vert"
                "premium_rate" -> "⚠️ Numéro surtaxé"
                else           -> lineType.ifBlank { null }
            }
            val location = listOfNotNull(
                json.optString("location").takeIf { it.isNotBlank() },
                json.optString("country_name").takeIf { it.isNotBlank() }
            ).joinToString(", ").takeIf { it.isNotEmpty() }

            val result = CallerIdResult(
                number = number,
                carrier = json.optString("carrier").takeIf { it.isNotBlank() },
                lineType = lineLabel,
                location = location,
                isSpam = lineType == "premium_rate"
            )
            storage.cacheCallerId(number, result)
            result
        } catch (_: Exception) { null }
    }
}
