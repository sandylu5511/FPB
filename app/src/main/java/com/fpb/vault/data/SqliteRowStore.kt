package com.fpb.vault.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.fpb.vault.vault.StorageNames

/**
 * SQLite 实现的密文行存储。
 *
 * ## 为什么不用 Room
 *
 * 表只有 3 列，全部查询都是"按主键取一行"。引入 Room 会带来 KSP 注解处理器、
 * 生成代码、以及一套需要跟着 AGP/Kotlin 版本走的兼容性风险 —— 换来的编译期
 * SQL 校验在这里价值有限。**对加密应用来说，能一眼读完的 SQL 比生成的 SQL 更值得信任。**
 *
 * 全部查询走 `?` 占位符。id 虽是本机生成且经过形状校验，但把"拼接 SQL"这个习惯
 * 留在代码库里迟早会被复制到别处。
 */
class SqliteRowStore(
    context: Context,
    databaseName: String = DATABASE_NAME,
) : CipherRowStore {

    private val helper = Helper(context.applicationContext, databaseName)

    override fun load(id: String): CipherRow? =
        helper.readableDatabase.rawQuery(SELECT_ONE, arrayOf(id)).use { cursor ->
            if (cursor.moveToFirst()) cursor.toRow() else null
        }

    override fun loadAll(): List<CipherRow> =
        helper.readableDatabase.rawQuery(SELECT_ALL, null).use { cursor ->
            val out = ArrayList<CipherRow>(cursor.count.coerceAtLeast(0))
            while (cursor.moveToNext()) out.add(cursor.toRow())
            out
        }

    override fun upsert(row: CipherRow) {
        val values = ContentValues(3).apply {
            put(COLUMN_ID, row.id)
            put(COLUMN_NONCE, row.nonce)
            put(COLUMN_CIPHERTEXT, row.ciphertext)
        }
        helper.writableDatabase.insertWithOnConflict(
            TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun delete(id: String) {
        helper.writableDatabase.delete(TABLE, "$COLUMN_ID = ?", arrayOf(id))
    }

    override fun totalBytes(): Long =
        helper.readableDatabase.rawQuery(SELECT_TOTAL_BYTES, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    /**
     * 用 SQLite 事务把一组写入合成一次原子提交。
     *
     * `beginTransaction()` 在整个进程里是"排他事务"，嵌套调用由 SQLiteDatabase
     * 自己记账（只有最外层真正提交），因此会话层不必关心自己是否已在事务里。
     * 块内抛异常时不调用 `setTransactionSuccessful()`，`endTransaction()` 会回滚。
     */
    override fun <T> inTransaction(block: () -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        return try {
            val result = block()
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    fun close() = helper.close()

    /**
     * 数据库里**只有 id 与密文两样东西**。
     *
     * 不放"标题""类型""创建时间"这些看起来很有用的列，是因为它们会让数据库文件
     * 变成一份可读的清单：谁拿到文件就能看到"这人记了 3 条密码、5 张身份证照片"。
     * 索引清单是加密的（见 `NoteIndexCodec`），列表摘要也是在内存里现算的。
     */
    private class Helper(
        context: Context,
        databaseName: String,
    ) : SQLiteOpenHelper(context, databaseName, null, SCHEMA_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(CREATE_TABLE)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 尚无迁移路径。将来加列时：ALTER TABLE 只能新增，
            // 绝不可 DROP —— 那会连带把密文一起清掉，而密文是不可再生的。
            throw IllegalStateException("不支持的数据库版本迁移: $oldVersion -> $newVersion")
        }
    }

    private fun android.database.Cursor.toRow(): CipherRow = CipherRow(
        id = getString(0),
        nonce = getBlob(1),
        ciphertext = getBlob(2),
    )

    companion object {
        internal const val DATABASE_NAME = StorageNames.DATABASE_FILE

        private const val SCHEMA_VERSION = 1
        private const val TABLE = "cipher_rows"
        private const val COLUMN_ID = "id"
        private const val COLUMN_NONCE = "nonce"
        private const val COLUMN_CIPHERTEXT = "ciphertext"

        private const val CREATE_TABLE =
            "CREATE TABLE $TABLE (" +
                "$COLUMN_ID TEXT PRIMARY KEY NOT NULL, " +
                "$COLUMN_NONCE BLOB NOT NULL, " +
                "$COLUMN_CIPHERTEXT BLOB NOT NULL)"

        private const val SELECT_ONE =
            "SELECT $COLUMN_ID, $COLUMN_NONCE, $COLUMN_CIPHERTEXT FROM $TABLE WHERE $COLUMN_ID = ?"

        private const val SELECT_ALL =
            "SELECT $COLUMN_ID, $COLUMN_NONCE, $COLUMN_CIPHERTEXT FROM $TABLE"

        private const val SELECT_TOTAL_BYTES =
            "SELECT SUM(LENGTH($COLUMN_NONCE) + LENGTH($COLUMN_CIPHERTEXT)) FROM $TABLE"
    }
}
