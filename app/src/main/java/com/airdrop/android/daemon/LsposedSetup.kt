package com.airdrop.android.daemon

import android.database.sqlite.SQLiteDatabase
import java.io.File

object LsposedSetup {
    const val DB = "/data/adb/lspd/config/modules_config.db"

    @JvmStatic
    fun main(args: Array<String>) {
        val module = args.getOrNull(1) ?: return
        val scope = args.getOrNull(2) ?: return
        if (!File(DB).exists()) {
            println("nodb")
            return
        }
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
}
