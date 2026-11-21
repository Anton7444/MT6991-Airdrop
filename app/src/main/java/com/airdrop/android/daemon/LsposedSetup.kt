package com.airdrop.android.daemon

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Runs as root through app_process: `query <module> <scope>` reports the module's LSPosed state, and
 * `grant <module> <scope> <apk>` enables the module and scopes it to the share app.
 */
object LsposedSetup {
    const val DB = "/data/adb/lspd/config/modules_config.db"

    @JvmStatic
    fun main(args: Array<String>) {
        val cmd = args.getOrNull(0) ?: return
        val module = args.getOrNull(1) ?: return
        val scope = args.getOrNull(2) ?: return
        if (!File(DB).exists()) {
            println("nodb")
            return
        }
        when (cmd) {
            "grant" -> grant(module, scope, args.getOrNull(3) ?: return)
            else -> query(module, scope)
        }
    }

    private fun query(module: String, scope: String) {
        val db = SQLiteDatabase.openDatabase(DB, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val known = db.rawQuery("SELECT COUNT(*) FROM modules WHERE module_pkg_name = ?", arrayOf(module)).use { it.moveToFirst() && it.getInt(0) > 0 }
            val enabled = db.rawQuery(
                "SELECT enabled FROM modules_state WHERE module_pkg_name = ? AND user_id = 0",
                arrayOf(module),
            ).use { it.moveToFirst() && it.getInt(0) == 1 }
            val scoped = db.rawQuery(
                "SELECT COUNT(*) FROM scope WHERE module_pkg_name = ? AND app_pkg_name = ? AND user_id = 0",
                arrayOf(module, scope),
            ).use { it.moveToFirst() && it.getInt(0) > 0 }
            println("known=$known enabled=$enabled scoped=$scoped")
        } finally {
            db.close()
        }
    }

    private fun grant(module: String, scope: String, apk: String) {
        val db = SQLiteDatabase.openDatabase(DB, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.beginTransaction()
            try {
                db.execSQL("INSERT OR IGNORE INTO modules (module_pkg_name, apk_path) VALUES (?, ?)", arrayOf(module, apk))
                db.execSQL("UPDATE modules SET apk_path = ? WHERE module_pkg_name = ?", arrayOf(apk, module))
                db.execSQL("INSERT OR IGNORE INTO modules_state (module_pkg_name, user_id, enabled) VALUES (?, 0, 1)", arrayOf(module))
                db.execSQL("UPDATE modules_state SET enabled = 1 WHERE module_pkg_name = ? AND user_id = 0", arrayOf(module))
                db.execSQL("INSERT OR IGNORE INTO scope (module_pkg_name, app_pkg_name, user_id) VALUES (?, ?, 0)", arrayOf(module, scope))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            println("granted")
        } finally {
            db.close()
        }
    }
}
