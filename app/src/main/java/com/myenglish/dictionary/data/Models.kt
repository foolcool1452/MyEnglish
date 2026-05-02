package com.myenglish.dictionary.data

data class WordSummary(
    val id: Long,
    val word: String,
    val phoneticUs: String?,
    val phoneticUk: String?,
    val primaryDefinition: String,
    val difficulty: String?,
    val isFavorite: Boolean,
)

data class Definition(
    val partOfSpeech: String,
    val chinese: String,
    val english: String?,
)

data class Example(
    val english: String,
    val chinese: String,
)

data class WordForm(
    val type: String,
    val value: String,
)

data class RelatedWord(
    val type: String,
    val word: String,
)

data class WordDetail(
    val id: Long,
    val word: String,
    val phoneticUs: String?,
    val phoneticUk: String?,
    val frequency: Int,
    val difficulty: String?,
    val definitions: List<Definition>,
    val examples: List<Example>,
    val forms: List<WordForm>,
    val relations: List<RelatedWord>,
    val isFavorite: Boolean,
    val note: String,
)

data class ReviewCard(
    val wordId: Long,
    val word: String,
    val phonetic: String?,
    val definition: String,
    val dueAt: Long,
)

enum class ReviewGrade {
    Again,
    Good,
    Easy,
}
