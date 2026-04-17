package com.fossylabs.portaserver.sql

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import com.fossylabs.portaserver.server.LogLevel
import com.fossylabs.portaserver.server.LogRepository
import java.io.File

data class QueryResult(
    val columns: List<String>? = null,
    val rows: List<Map<String, String?>>? = null,
    val rowsAffected: Int? = null,
    val error: String? = null,
)

object SqliteManager {

    @Volatile
    var dbDir: File? = null
        private set

    private val openDbs = HashMap<String, SQLiteDatabase>()

    fun configure(dir: File) {
        dbDir = dir
        dir.mkdirs()
    }

    fun listDatabases(): List<String> =
        dbDir?.listFiles()
            ?.filter { it.isFile && it.extension == "db" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()

    fun openOrCreate(name: String): SQLiteDatabase {
        return openDbs.getOrPut(name) {
            val file = File(checkNotNull(dbDir) { "SqliteManager not configured" }, "$name.db")
            SQLiteDatabase.openOrCreateDatabase(file, null)
        }
    }

    fun listTables(dbName: String): List<String> {
        val result = executeQuery(
            dbName,
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
        )
        return result.rows?.mapNotNull { it["name"] } ?: emptyList()
    }

    fun executeQuery(dbName: String, sql: String): QueryResult {
        LogRepository.log(LogLevel.INFO, "[$dbName] ${sql.take(120)}")
        return try {
            val db = openOrCreate(dbName)
            val trimmed = sql.trim().lowercase()
            val isRead = trimmed.startsWith("select") ||
                trimmed.startsWith("pragma") ||
                trimmed.startsWith("explain")

            if (isRead) {
                val cursor: Cursor = db.rawQuery(sql, null)
                val columns = cursor.columnNames.toList()
                val rows = buildList {
                    while (cursor.moveToNext()) {
                        add(columns.associateWith { col ->
                            val idx = cursor.getColumnIndex(col)
                            if (cursor.isNull(idx)) null else cursor.getString(idx)
                        })
                    }
                }
                cursor.close()
                QueryResult(columns = columns, rows = rows)
            } else {
                val stmt: SQLiteStatement = db.compileStatement(sql)
                val affected = stmt.executeUpdateDelete()
                stmt.close()
                QueryResult(rowsAffected = affected)
            }
        } catch (e: Exception) {
            QueryResult(error = e.message)
        }
    }

    fun closeAll() {
        openDbs.values.forEach { it.close() }
        openDbs.clear()
    }
}
