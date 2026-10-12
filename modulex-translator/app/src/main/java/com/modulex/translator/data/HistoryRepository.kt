package com.modulex.translator.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class HistoryKind { TEXT, VOICE, CAMERA, CONVERSATION }

data class HistoryEntry(
    val id: Long = 0,
    val timestamp: Long,
    val kind: HistoryKind,
    val sourceLang: String,
    val targetLang: String,
    val sourceText: String,
    val translatedText: String,
    val sessionId: String? = null,
    val incoming: Boolean = false,
)

/** Local-only translation history (app-private SQLite, excluded from backups). */
class HistoryRepository(context: Context) {
    private val helper = object : SQLiteOpenHelper(context, "history.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    src TEXT NOT NULL,
                    tgt TEXT NOT NULL,
                    source_text TEXT NOT NULL,
                    translated_text TEXT NOT NULL,
                    session_id TEXT,
                    incoming INTEGER NOT NULL DEFAULT 0)""",
            )
            db.execSQL("CREATE INDEX history_ts ON history(ts DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    suspend fun refresh(limit: Int = 500) = withContext(Dispatchers.IO) {
        val out = ArrayList<HistoryEntry>()
        helper.readableDatabase.query("history", null, null, null, null, null, "ts DESC", limit.toString()).use { c ->
            while (c.moveToNext()) {
                out += HistoryEntry(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    timestamp = c.getLong(c.getColumnIndexOrThrow("ts")),
                    kind = runCatching { HistoryKind.valueOf(c.getString(c.getColumnIndexOrThrow("kind"))) }.getOrDefault(HistoryKind.TEXT),
                    sourceLang = c.getString(c.getColumnIndexOrThrow("src")),
                    targetLang = c.getString(c.getColumnIndexOrThrow("tgt")),
                    sourceText = c.getString(c.getColumnIndexOrThrow("source_text")),
                    translatedText = c.getString(c.getColumnIndexOrThrow("translated_text")),
                    sessionId = c.getString(c.getColumnIndexOrThrow("session_id")),
                    incoming = c.getInt(c.getColumnIndexOrThrow("incoming")) != 0,
                )
            }
        }
        _entries.value = out
    }

    suspend fun add(e: HistoryEntry) = withContext(Dispatchers.IO) {
        helper.writableDatabase.insert("history", null, ContentValues().apply {
            put("ts", e.timestamp)
            put("kind", e.kind.name)
            put("src", e.sourceLang)
            put("tgt", e.targetLang)
            put("source_text", e.sourceText)
            put("translated_text", e.translatedText)
            put("session_id", e.sessionId)
            put("incoming", if (e.incoming) 1 else 0)
        })
        refresh()
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("history", "id = ?", arrayOf(id.toString()))
        refresh()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("history", null, null)
        // Reclaim the pages so deleted text does not linger in the database file.
        helper.writableDatabase.execSQL("VACUUM")
        refresh()
    }
}
