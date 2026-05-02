package com.myenglish.dictionary.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class DictionaryRepository(context: Context) {
    private val dbHelper = DictionaryDbHelper(context.applicationContext)

    suspend fun search(query: String): List<WordSummary> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) return@withContext recentHistory()
        val db = dbHelper.readableDatabase
        val ids = linkedSetOf<Long>()
        val normalized = clean.lowercase(Locale.ROOT)
        val isAsciiQuery = normalized.all { it in 'a'..'z' || it in '0'..'9' || it == ' ' || it == '-' }
        val isShortAsciiQuery = isAsciiQuery && normalized.length < 3

        db.rawQuery(
            "SELECT id FROM words WHERE normalized_word = ? LIMIT 1",
            arrayOf(normalized)
        ).use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getLong(0)
        }

        db.rawQuery(
            "SELECT id FROM words WHERE normalized_word LIKE ? ORDER BY frequency DESC, normalized_word LIMIT 30",
            arrayOf("$normalized%")
        ).use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getLong(0)
        }

        val shouldSearchDefinitions = !isShortAsciiQuery && (!isAsciiQuery || ids.isEmpty() || clean.contains(' '))
        if (shouldSearchDefinitions) {
            val likeArg = "%$clean%"
            val sql: String
            val args: Array<String>
            if (isAsciiQuery) {
                sql = """
                    SELECT DISTINCT w.id
                    FROM words w
                    JOIN definitions d ON d.word_id = w.id
                    LEFT JOIN examples e ON e.word_id = w.id
                    WHERE d.english_definition LIKE ?
                       OR e.sentence_en LIKE ?
                    ORDER BY w.frequency DESC, w.normalized_word
                    LIMIT 40
                """.trimIndent()
                args = arrayOf(likeArg, likeArg)
            } else {
                sql = """
                    SELECT DISTINCT w.id
                    FROM words w
                    JOIN definitions d ON d.word_id = w.id
                    LEFT JOIN examples e ON e.word_id = w.id
                    WHERE d.chinese_definition LIKE ?
                       OR e.sentence_zh LIKE ?
                    ORDER BY w.frequency DESC, w.normalized_word
                    LIMIT 40
                """.trimIndent()
                args = arrayOf(likeArg, likeArg)
            }
            db.rawQuery(sql, args).use { cursor ->
                while (cursor.moveToNext()) ids += cursor.getLong(0)
            }
        }

        val ftsQuery = clean
            .replace(Regex("[^\\p{L}\\p{N}\\u4e00-\\u9fa5 ]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" OR ")
        if (ftsQuery.isNotBlank() && shouldSearchDefinitions) {
            runCatching {
                db.rawQuery(
                    "SELECT word_id FROM word_fts WHERE word_fts MATCH ? LIMIT 40",
                    arrayOf(ftsQuery)
                ).use { cursor ->
                    while (cursor.moveToNext()) ids += cursor.getLong(0)
                }
            }
        }

        ids.take(50).mapNotNull { summaryById(db, it) }
    }

    suspend fun detail(wordId: Long): WordDetail? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            """
            SELECT w.*, EXISTS(SELECT 1 FROM favorites f WHERE f.word_id = w.id) AS is_favorite,
                   COALESCE(n.content, '') AS note
            FROM words w
            LEFT JOIN notes n ON n.word_id = w.id
            WHERE w.id = ?
            """.trimIndent(),
            arrayOf(wordId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            WordDetail(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                word = cursor.getString(cursor.getColumnIndexOrThrow("word")),
                phoneticUs = cursor.stringOrNull("phonetic_us"),
                phoneticUk = cursor.stringOrNull("phonetic_uk"),
                frequency = cursor.getInt(cursor.getColumnIndexOrThrow("frequency")),
                difficulty = cursor.stringOrNull("difficulty"),
                definitions = definitions(db, wordId),
                examples = examples(db, wordId),
                forms = forms(db, wordId),
                relations = relations(db, wordId),
                isFavorite = cursor.getInt(cursor.getColumnIndexOrThrow("is_favorite")) == 1,
                note = cursor.getString(cursor.getColumnIndexOrThrow("note")),
            )
        }
    }

    suspend fun favorites(): List<WordSummary> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            """
            SELECT w.id
            FROM favorites f
            JOIN words w ON w.id = f.word_id
            ORDER BY f.created_at DESC
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    summaryById(db, cursor.getLong(0))?.let(::add)
                }
            }
        }
    }

    suspend fun recentHistory(): List<WordSummary> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            """
            SELECT word_id, MAX(searched_at) AS last_seen
            FROM search_history
            WHERE word_id IS NOT NULL
            GROUP BY word_id
            ORDER BY last_seen DESC
            LIMIT 20
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    summaryById(db, cursor.getLong(0))?.let(::add)
                }
            }
        }
    }

    suspend fun addHistory(query: String, wordId: Long?) = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.insert(
            "search_history",
            null,
            ContentValues().apply {
                put("query", query.trim())
                if (wordId != null) put("word_id", wordId)
                put("searched_at", System.currentTimeMillis())
            }
        )
    }

    suspend fun setFavorite(wordId: Long, favorite: Boolean) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        if (favorite) {
            db.insertWithOnConflict(
                "favorites",
                null,
                ContentValues().apply {
                    put("word_id", wordId)
                    put("created_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
            ensureStudyRecord(db, wordId)
        } else {
            db.delete("favorites", "word_id = ?", arrayOf(wordId.toString()))
        }
    }

    suspend fun saveNote(wordId: Long, note: String) = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.insertWithOnConflict(
            "notes",
            null,
            ContentValues().apply {
                put("word_id", wordId)
                put("content", note)
                put("updated_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    suspend fun dueReviews(): List<ReviewCard> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        db.rawQuery(
            """
            SELECT s.word_id, w.word, w.phonetic_us, s.next_review_at,
                   COALESCE((SELECT chinese_definition FROM definitions d WHERE d.word_id = w.id ORDER BY order_index LIMIT 1), '') AS definition
            FROM study_records s
            JOIN words w ON w.id = s.word_id
            WHERE s.next_review_at <= ?
            ORDER BY s.next_review_at ASC, w.frequency DESC
            LIMIT 30
            """.trimIndent(),
            arrayOf(System.currentTimeMillis().toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ReviewCard(
                            wordId = cursor.getLong(0),
                            word = cursor.getString(1),
                            phonetic = cursor.stringOrNull("phonetic_us"),
                            dueAt = cursor.getLong(3),
                            definition = cursor.getString(4),
                        )
                    )
                }
            }
        }
    }

    suspend fun gradeReview(wordId: Long, grade: ReviewGrade) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        ensureStudyRecord(db, wordId)
        db.rawQuery(
            "SELECT review_count, ease_factor, interval_days FROM study_records WHERE word_id = ?",
            arrayOf(wordId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@withContext
            val count = cursor.getInt(0) + 1
            val oldEase = cursor.getDouble(1)
            val oldInterval = cursor.getInt(2)
            val ease = when (grade) {
                ReviewGrade.Again -> max(1.3, oldEase - 0.25)
                ReviewGrade.Good -> oldEase
                ReviewGrade.Easy -> oldEase + 0.15
            }
            val interval = when (grade) {
                ReviewGrade.Again -> 0
                ReviewGrade.Good -> if (oldInterval == 0) 1 else max(1, (oldInterval * ease).roundToInt())
                ReviewGrade.Easy -> if (oldInterval == 0) 3 else max(2, (oldInterval * (ease + 0.5)).roundToInt())
            }
            val next = System.currentTimeMillis() + interval * DAY_MS
            db.update(
                "study_records",
                ContentValues().apply {
                    put("status", if (grade == ReviewGrade.Again) "learning" else "reviewing")
                    put("review_count", count)
                    put("ease_factor", ease)
                    put("interval_days", interval)
                    put("next_review_at", next)
                    put("last_review_at", System.currentTimeMillis())
                },
                "word_id = ?",
                arrayOf(wordId.toString())
            )
        }
    }

    private fun ensureStudyRecord(db: SQLiteDatabase, wordId: Long) {
        db.insertWithOnConflict(
            "study_records",
            null,
            ContentValues().apply {
                put("word_id", wordId)
                put("status", "new")
                put("review_count", 0)
                put("ease_factor", 2.5)
                put("interval_days", 0)
                put("next_review_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    private fun summaryById(db: SQLiteDatabase, wordId: Long): WordSummary? {
        db.rawQuery(
            """
            SELECT w.id, w.word, w.phonetic_us, w.phonetic_uk, w.difficulty,
                   EXISTS(SELECT 1 FROM favorites f WHERE f.word_id = w.id) AS is_favorite,
                   COALESCE((SELECT chinese_definition FROM definitions d WHERE d.word_id = w.id ORDER BY order_index LIMIT 1), '') AS primary_definition
            FROM words w
            WHERE w.id = ?
            """.trimIndent(),
            arrayOf(wordId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return WordSummary(
                id = cursor.getLong(0),
                word = cursor.getString(1),
                phoneticUs = cursor.stringOrNull("phonetic_us"),
                phoneticUk = cursor.stringOrNull("phonetic_uk"),
                difficulty = cursor.stringOrNull("difficulty"),
                isFavorite = cursor.getInt(5) == 1,
                primaryDefinition = cursor.getString(6),
            )
        }
    }

    private fun definitions(db: SQLiteDatabase, wordId: Long): List<Definition> =
        db.rawQuery(
            "SELECT part_of_speech, chinese_definition, english_definition FROM definitions WHERE word_id = ? ORDER BY order_index",
            arrayOf(wordId.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Definition(cursor.getString(0), cursor.getString(1), cursor.stringOrNull("english_definition")))
                }
            }
        }

    private fun examples(db: SQLiteDatabase, wordId: Long): List<Example> =
        db.rawQuery(
            "SELECT sentence_en, sentence_zh FROM examples WHERE word_id = ? ORDER BY order_index",
            arrayOf(wordId.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(Example(cursor.getString(0), cursor.getString(1)))
            }
        }

    private fun forms(db: SQLiteDatabase, wordId: Long): List<WordForm> =
        db.rawQuery(
            "SELECT form_type, form_value FROM word_forms WHERE word_id = ? ORDER BY form_type",
            arrayOf(wordId.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(WordForm(cursor.getString(0), cursor.getString(1)))
            }
        }

    private fun relations(db: SQLiteDatabase, wordId: Long): List<RelatedWord> =
        db.rawQuery(
            "SELECT relation_type, related_word FROM relations WHERE word_id = ? ORDER BY relation_type, related_word",
            arrayOf(wordId.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(RelatedWord(cursor.getString(0), cursor.getString(1)))
            }
        }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
