package com.dograh.voiceagent.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class CallMemory(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    init {
        try {
            System.loadLibrary("sqlite_vec")
            Log.d(TAG, "sqlite-vec native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load sqlite-vec native library: ${e.message}")
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Load the sqlite-vec extension into the SQLite connection
        try {
            db.execSQL("SELECT load_extension('libsqlite_vec.so')")
            Log.d(TAG, "sqlite-vec extension loaded into database")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load sqlite-vec extension in DB onCreate: ${e.message}")
        }

        // Table for structured call history
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS calls (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
                phone_number TEXT,
                direction TEXT,
                transcript TEXT,
                summary TEXT
            )
        """)

        // Virtual table for vector embeddings using sqlite-vec
        // We'll store 384-dimensional embeddings (e.g. from an all-MiniLM-L6-v2 model)
        try {
            db.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS vec_calls USING vec0(
                    call_id INTEGER PRIMARY KEY,
                    embedding FLOAT[384]
                )
            """)
            Log.d(TAG, "sqlite-vec virtual table created")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create sqlite-vec virtual table: ${e.message}")
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS calls")
        db.execSQL("DROP TABLE IF EXISTS vec_calls")
        onCreate(db)
    }

    /**
     * Store a call record with its text summary and embedding.
     */
    fun saveCallRecord(phoneNumber: String, direction: String, transcript: String, summary: String, embedding: FloatArray) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("phone_number", phoneNumber)
                put("direction", direction)
                put("transcript", transcript)
                put("summary", summary)
            }
            val callId = db.insert("calls", null, values)

            if (callId != -1L) {
                // Serializing float array to byte array for sqlite-vec BLOB insertion
                val byteBuffer = java.nio.ByteBuffer.allocate(embedding.size * 4)
                byteBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (value in embedding) {
                    byteBuffer.putFloat(value)
                }
                val blob = byteBuffer.array()

                val stmt = db.compileStatement("INSERT INTO vec_calls(call_id, embedding) VALUES(?, ?)")
                stmt.bindLong(1, callId)
                stmt.bindBlob(2, blob)
                stmt.executeInsert()
                db.setTransactionSuccessful()
                Log.d(TAG, "Call record $callId and embedding saved successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving call record: ${e.message}")
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Query local memory using vector search (k-NN search via sqlite-vec).
     */
    fun queryCallMemory(queryEmbedding: FloatArray, limit: Int = 3): List<CallRecord> {
        val results = mutableListOf<CallRecord>()
        val db = readableDatabase

        try {
            // Load extension for connection if needed (some SQLite wrappers don't persist extensions)
            try {
                db.execSQL("SELECT load_extension('libsqlite_vec.so')")
            } catch (e: Exception) {
                // Silent catch: it might already be loaded
            }

            // Serialize query embedding to byte array
            val byteBuffer = java.nio.ByteBuffer.allocate(queryEmbedding.size * 4)
            byteBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (value in queryEmbedding) {
                byteBuffer.putFloat(value)
            }
            val blob = byteBuffer.array()

            // Perform k-NN query using vec_distance_cosine or standard vec_distance_l2
            val query = """
                SELECT c.id, c.timestamp, c.phone_number, c.direction, c.transcript, c.summary, v.distance
                FROM vec_calls v
                JOIN calls c ON v.call_id = c.id
                WHERE v.embedding MATCH ?1 AND k = ?2
                ORDER BY v.distance ASC
            """

            val cursor = db.rawQuery(query, arrayOf(blob.toString(), limit.toString())) // Raw SQL query
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                val timestamp = cursor.getString(cursor.getColumnIndexOrThrow("timestamp"))
                val phoneNumber = cursor.getString(cursor.getColumnIndexOrThrow("phone_number"))
                val direction = cursor.getString(cursor.getColumnIndexOrThrow("direction"))
                val transcript = cursor.getString(cursor.getColumnIndexOrThrow("transcript"))
                val summary = cursor.getString(cursor.getColumnIndexOrThrow("summary"))
                val distance = cursor.getFloat(cursor.getColumnIndexOrThrow("distance"))

                results.add(CallRecord(id, timestamp, phoneNumber, direction, transcript, summary, distance))
            }
            cursor.close()
        } catch (e: Exception) {
            Log.e(TAG, "Vector search query failed: ${e.message}")
        }

        return results
    }

    companion object {
        private const val TAG = "CallMemory"
        private const val DATABASE_NAME = "dograh_memory.db"
        private const val DATABASE_VERSION = 1
    }
}

data class CallRecord(
    val id: Long,
    val timestamp: String,
    val phoneNumber: String,
    val direction: String,
    val transcript: String,
    val summary: String,
    val distance: Float
)
