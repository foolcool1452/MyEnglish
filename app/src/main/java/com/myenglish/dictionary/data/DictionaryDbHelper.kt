package com.myenglish.dictionary.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.io.IOException

class DictionaryDbHelper(private val context: Context) :
    SQLiteOpenHelper(context, prepareDatabase(context), null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE words (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                word TEXT NOT NULL UNIQUE,
                normalized_word TEXT NOT NULL UNIQUE,
                phonetic_us TEXT,
                phonetic_uk TEXT,
                frequency INTEGER NOT NULL DEFAULT 0,
                difficulty TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE definitions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                word_id INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,
                part_of_speech TEXT NOT NULL,
                chinese_definition TEXT NOT NULL,
                english_definition TEXT,
                order_index INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE examples (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                word_id INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,
                sentence_en TEXT NOT NULL,
                sentence_zh TEXT NOT NULL,
                source TEXT,
                order_index INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE word_forms (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                word_id INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,
                form_type TEXT NOT NULL,
                form_value TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE relations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                word_id INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,
                relation_type TEXT NOT NULL,
                related_word TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE favorites (
                word_id INTEGER PRIMARY KEY REFERENCES words(id) ON DELETE CASCADE,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE search_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                query TEXT NOT NULL,
                word_id INTEGER,
                searched_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE notes (
                word_id INTEGER PRIMARY KEY REFERENCES words(id) ON DELETE CASCADE,
                content TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE study_records (
                word_id INTEGER PRIMARY KEY REFERENCES words(id) ON DELETE CASCADE,
                status TEXT NOT NULL DEFAULT 'new',
                review_count INTEGER NOT NULL DEFAULT 0,
                ease_factor REAL NOT NULL DEFAULT 2.5,
                interval_days INTEGER NOT NULL DEFAULT 0,
                next_review_at INTEGER NOT NULL,
                last_review_at INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE VIRTUAL TABLE word_fts USING fts4(
                word_id,
                word,
                normalized_word,
                chinese_definition,
                english_definition,
                examples
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX index_words_normalized ON words(normalized_word)")
        db.execSQL("CREATE INDEX index_definitions_word ON definitions(word_id)")
        db.execSQL("CREATE INDEX index_examples_word ON examples(word_id)")
        db.execSQL("CREATE INDEX index_history_time ON search_history(searched_at DESC)")
        seed(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS word_fts")
        db.execSQL("DROP TABLE IF EXISTS study_records")
        db.execSQL("DROP TABLE IF EXISTS notes")
        db.execSQL("DROP TABLE IF EXISTS search_history")
        db.execSQL("DROP TABLE IF EXISTS favorites")
        db.execSQL("DROP TABLE IF EXISTS relations")
        db.execSQL("DROP TABLE IF EXISTS word_forms")
        db.execSQL("DROP TABLE IF EXISTS examples")
        db.execSQL("DROP TABLE IF EXISTS definitions")
        db.execSQL("DROP TABLE IF EXISTS words")
        onCreate(db)
    }

    private fun seed(db: SQLiteDatabase) {
        val json = context.assets.open("dictionary_seed.json")
            .bufferedReader()
            .use { it.readText() }
        val words = JSONArray(json)
        db.beginTransaction()
        try {
            for (index in 0 until words.length()) {
                val item = words.getJSONObject(index)
                val wordId = db.insertOrThrow(
                    "words",
                    null,
                    ContentValues().apply {
                        put("word", item.getString("word"))
                        put("normalized_word", item.getString("word").lowercase())
                        put("phonetic_us", item.optionalString("phonetic_us"))
                        put("phonetic_uk", item.optionalString("phonetic_uk"))
                        put("frequency", item.optInt("frequency", 0))
                        put("difficulty", item.optionalString("difficulty"))
                    }
                )

                val definitions = item.getJSONArray("definitions")
                val chineseParts = mutableListOf<String>()
                val englishParts = mutableListOf<String>()
                for (definitionIndex in 0 until definitions.length()) {
                    val definition = definitions.getJSONObject(definitionIndex)
                    val chinese = definition.getString("chinese")
                    val english = definition.optString("english", "")
                    chineseParts += chinese
                    englishParts += english
                    db.insertOrThrow(
                        "definitions",
                        null,
                        ContentValues().apply {
                            put("word_id", wordId)
                            put("part_of_speech", definition.getString("pos"))
                            put("chinese_definition", chinese)
                            put("english_definition", english)
                            put("order_index", definitionIndex)
                        }
                    )
                }

                val examples = item.optJSONArray("examples") ?: JSONArray()
                val exampleParts = mutableListOf<String>()
                for (exampleIndex in 0 until examples.length()) {
                    val example = examples.getJSONObject(exampleIndex)
                    exampleParts += example.getString("en")
                    db.insertOrThrow(
                        "examples",
                        null,
                        ContentValues().apply {
                            put("word_id", wordId)
                            put("sentence_en", example.getString("en"))
                            put("sentence_zh", example.getString("zh"))
                            put("source", "seed")
                            put("order_index", exampleIndex)
                        }
                    )
                }

                val forms = item.optJSONObject("forms")
                forms?.keys()?.forEach { key ->
                    db.insertOrThrow(
                        "word_forms",
                        null,
                        ContentValues().apply {
                            put("word_id", wordId)
                            put("form_type", key)
                            put("form_value", forms.getString(key))
                        }
                    )
                }

                val relations = item.optJSONObject("relations")
                relations?.keys()?.forEach { key ->
                    val values = relations.getJSONArray(key)
                    for (relationIndex in 0 until values.length()) {
                        db.insertOrThrow(
                            "relations",
                            null,
                            ContentValues().apply {
                                put("word_id", wordId)
                                put("relation_type", key)
                                put("related_word", values.getString(relationIndex))
                            }
                        )
                    }
                }

                db.insertOrThrow(
                    "word_fts",
                    null,
                    ContentValues().apply {
                        put("word_id", wordId)
                        put("word", item.getString("word"))
                        put("normalized_word", item.getString("word").lowercase())
                        put("chinese_definition", chineseParts.joinToString(" "))
                        put("english_definition", englishParts.joinToString(" "))
                        put("examples", exampleParts.joinToString(" "))
                    }
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        private const val DATABASE_NAME = "myenglish_dictionary.db"
        private const val PREBUILT_DATABASE = "myenglish_dictionary.db"
        private const val DATABASE_VERSION = 3

        private fun prepareDatabase(context: Context): String {
            if (!assetExists(context)) return DATABASE_NAME

            val databaseFile = context.getDatabasePath(DATABASE_NAME)
            val shouldCopy = if (!databaseFile.exists()) {
                true
            } else {
                runCatching {
                    SQLiteDatabase.openDatabase(
                        databaseFile.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READONLY,
                    ).use { db ->
                        db.rawQuery("PRAGMA user_version", emptyArray()).use { cursor ->
                            cursor.moveToFirst() && cursor.getInt(0) < DATABASE_VERSION
                        }
                    }
                }.getOrDefault(true)
            }

            if (shouldCopy) {
                databaseFile.parentFile?.mkdirs()
                if (databaseFile.exists()) databaseFile.delete()
                context.assets.open(PREBUILT_DATABASE).use { input ->
                    databaseFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            return DATABASE_NAME
        }

        private fun assetExists(context: Context): Boolean =
            try {
                context.assets.open(PREBUILT_DATABASE).close()
                true
            } catch (_: IOException) {
                false
            }
    }
}

fun Cursor.stringOrNull(column: String): String? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getString(index)
}

private fun org.json.JSONObject.optionalString(name: String): String? =
    if (has(name) && !isNull(name)) getString(name) else null
