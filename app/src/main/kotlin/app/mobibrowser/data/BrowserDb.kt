package app.mobibrowser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

data class HistoryEntry(
    val url: String,
    val title: String,
    val lastVisit: Long,
    val visits: Int,
)

data class BookmarkEntry(
    val id: String,
    val url: String,
    val title: String,
    val addedAt: Long,
)

enum class ScriptKind { JS, CSS }

/** User script / estilo do usuário, ou content script de extensão em modo compatibilidade. */
data class UserScript(
    val id: String,
    val name: String,
    val code: String,
    /** Curinga de host/caminho no estilo match pattern do Chrome, ou o atalho all_urls.
     *  Não citar o padrão literal aqui: a barra com estrela que ele contém abriria um
     *  comentário aninhado neste bloco e engoliria o arquivo inteiro. */
    val pattern: String,
    val kind: ScriptKind,
    val enabled: Boolean,
    /** Quando não-nulo, vem de uma extensão instalada (não editável pelo usuário). */
    val sourceExtension: String?,
    /** Precisa rodar no document_start (padrão) ou depois do DOM pronto? */
    val runAtIdle: Boolean,
)

/**
 * Camada de dados do navegador: histórico, favoritos e scripts do usuário.
 *
 * SQLite cru (sem ORM/codegen) de propósito: menos uma toolchain no CI do APK instável
 * e nada para desalinhar entre AGP/KSP/Kotlin. As queries são pequenas e diretas.
 */
class BrowserDb(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE history(
              url TEXT PRIMARY KEY,
              title TEXT NOT NULL DEFAULT '',
              last_visit INTEGER NOT NULL,
              visits INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_history_visit ON history(last_visit DESC)")
        db.execSQL(
            """
            CREATE TABLE bookmarks(
              id TEXT PRIMARY KEY,
              url TEXT NOT NULL UNIQUE,
              title TEXT NOT NULL DEFAULT '',
              added INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE scripts(
              id TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              code TEXT NOT NULL,
              pattern TEXT NOT NULL DEFAULT '<all_urls>',
              kind TEXT NOT NULL DEFAULT 'JS',
              enabled INTEGER NOT NULL DEFAULT 1,
              source_extension TEXT,
              run_at_idle INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 ainda não tem migração: se um build futuro mudar o schema, migrar aqui.
        if (oldVersion < 1) onCreate(db)
    }

    /* ------------------------------ histórico ------------------------------ */

    fun addHistory(url: String, title: String) {
        writableDatabase.execSQL(
            """
            INSERT INTO history(url, title, last_visit, visits) VALUES(?,?,?,1)
            ON CONFLICT(url) DO UPDATE SET
              title = COALESCE(NULLIF(excluded.title, ''), history.title),
              last_visit = excluded.last_visit,
              visits = visits + 1
            """.trimIndent(),
            arrayOf(url, title, System.currentTimeMillis()),
        )
    }

    fun recentHistory(limit: Int = 100): List<HistoryEntry> =
        query(
            """
            SELECT url, title, last_visit, visits FROM history
            ORDER BY last_visit DESC LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString()),
            ::toHistoryEntry,
        )

    fun searchHistory(term: String, limit: Int = 100): List<HistoryEntry> {
        val like = "%${term.replace("%", "\\%")}%"
        return readableDatabase.rawQuery(
            """
            SELECT url, title, last_visit, visits FROM history
            WHERE url LIKE ? OR title LIKE ?
            ORDER BY last_visit DESC LIMIT ?
            """.trimIndent(),
            arrayOf(like, like, limit.toString()),
        ).use { c -> generateSequence { if (c.moveToNext()) c else null }.map { toHistoryEntry(c) }.toList() }
    }

    fun deleteHistory(url: String) {
        writableDatabase.delete("history", "url = ?", arrayOf(url))
    }

    fun clearHistory() {
        writableDatabase.delete("history", null, null)
    }

    private fun toHistoryEntry(c: android.database.Cursor) = HistoryEntry(
        url = c.getString(0),
        title = c.getString(1),
        lastVisit = c.getLong(2),
        visits = c.getInt(3),
    )

    /* ------------------------------ favoritos ------------------------------ */

    fun isBookmarked(url: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM bookmarks WHERE url = ? LIMIT 1",
        arrayOf(url),
    ).use { it.moveToFirst() }

    fun addBookmark(url: String, title: String): String {
        val id = UUID.randomUUID().toString()
        writableDatabase.insertWithOnConflict(
            "bookmarks",
            null,
            ContentValues().apply {
                put("id", id)
                put("url", url)
                put("title", title)
                put("added", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return id
    }

    fun removeBookmark(url: String) {
        writableDatabase.delete("bookmarks", "url = ?", arrayOf(url))
    }

    fun listBookmarks(limit: Int = 500): List<BookmarkEntry> = query(
        "SELECT id, url, title, added FROM bookmarks ORDER BY added DESC LIMIT ?",
        arrayOf(limit.toString()),
    ) { c ->
        BookmarkEntry(c.getString(0), c.getString(1), c.getString(2), c.getLong(3))
    }

    /* -------------------------- scripts do usuário ------------------------- */

    fun listScripts(extensionId: String? = null): List<UserScript> {
        val selection = if (extensionId == null) null else "source_extension = ?"
        val args = if (extensionId == null) null else arrayOf(extensionId)
        return readableDatabase.query(
            "scripts",
            arrayOf("id", "name", "code", "pattern", "kind", "enabled", "source_extension", "run_at_idle"),
            selection,
            args,
            null,
            null,
            "name COLLATE NOCASE ASC",
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        UserScript(
                            id = c.getString(0),
                            name = c.getString(1),
                            code = c.getString(2),
                            pattern = c.getString(3),
                            kind = runCatching { ScriptKind.valueOf(c.getString(4)) }.getOrDefault(ScriptKind.JS),
                            enabled = c.getInt(5) == 1,
                            sourceExtension = c.getString(6),
                            runAtIdle = c.getInt(7) == 1,
                        ),
                    )
                }
            }
        }
    }

    fun saveScript(script: UserScript) {
        writableDatabase.insertWithOnConflict(
            "scripts",
            null,
            ContentValues().apply {
                put("id", script.id)
                put("name", script.name)
                put("code", script.code)
                put("pattern", script.pattern)
                put("kind", script.kind.name)
                put("enabled", if (script.enabled) 1 else 0)
                put("source_extension", script.sourceExtension)
                put("run_at_idle", if (script.runAtIdle) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun setScriptEnabled(id: String, enabled: Boolean) {
        writableDatabase.execSQL("UPDATE scripts SET enabled = ? WHERE id = ?", arrayOf(if (enabled) 1 else 0, id))
    }

    fun deleteScript(id: String) {
        writableDatabase.delete("scripts", "id = ?", arrayOf(id))
    }

    /** Troca todos os scripts de uma extensão (usado ao reconvertir um pacote). */
    fun replaceExtensionScripts(extensionId: String, scripts: List<UserScript>) {
        writableDatabase.apply {
            beginTransaction()
            try {
                delete("scripts", "source_extension = ?", arrayOf(extensionId))
                scripts.forEach { saveScript(it.copy(sourceExtension = extensionId)) }
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
    }

    /* ---------------------------- meta / limpeza --------------------------- */

    fun meta(key: String): String? = readableDatabase
        .rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key))
        .use { if (it.moveToFirst()) it.getString(0) else null }

    fun setMeta(key: String, value: String) {
        writableDatabase.execSQL(
            "INSERT INTO meta(key, value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            arrayOf(key, value),
        )
    }

    fun countHistory(): Int = readableDatabase
        .rawQuery("SELECT COUNT(*) FROM history", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun clearEverything() {
        clearHistory()
        writableDatabase.execSQL("DELETE FROM scripts WHERE source_extension IS NOT NULL")
    }

    /**
     * Sem mágica de `LIMIT`: quem escreve o SQL decide se ele tem `LIMIT ?`, e os argumentos
     * precisam bater com os `?`. Ligar um argumento a mais é `SQLiteException` em tempo de
     * uso — exatamente o tipo de coisa que só apareceria no aparelho do usuário, na tela de
     * favoritos.
     */
    private fun <T> query(
        sql: String,
        args: Array<String>? = null,
        mapper: (android.database.Cursor) -> T,
    ): List<T> = readableDatabase.rawQuery(sql, args).use { c ->
        buildList { while (c.moveToNext()) add(mapper(c)) }
    }

    private companion object {
        const val DB_NAME = "mobibrowser.db"
        const val DB_VERSION = 1
    }
}
