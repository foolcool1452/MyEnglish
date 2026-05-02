package com.myenglish.dictionary

import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myenglish.dictionary.data.DictionaryRepository
import com.myenglish.dictionary.data.WordDetail
import com.myenglish.dictionary.data.WordSummary
import com.myenglish.dictionary.ui.theme.MyEnglishTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private var speechStatus by mutableStateOf("发音准备中")
    private var speechReady by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = DictionaryRepository(this)
        initTextToSpeech()

        setContent {
            MyEnglishTheme {
                DictionaryApp(
                    repository = repository,
                    speechReady = speechReady,
                    onSpeak = ::speak,
                )
            }
        }
    }

    private fun initTextToSpeech() {
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                speechReady = false
                speechStatus = "系统发音不可用"
                return@TextToSpeech
            }

            val result = tts?.setLanguage(Locale.US)
            val hasEnglishVoice = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            speechReady = true
            speechStatus = if (hasEnglishVoice) "美式发音" else "系统默认发音"

            tts?.setSpeechRate(0.86f)
            tts?.setPitch(1.0f)
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            tts?.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = Unit
                    @Deprecated("Use onError(String?, Int) on newer Android versions.")
                    override fun onError(utteranceId: String?) {
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "发音失败，请检查系统 TTS 语音包",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            )
        }
    }

    private fun speak(text: String) {
        if (!speechReady) {
            Toast.makeText(this, speechStatus, Toast.LENGTH_SHORT).show()
            return
        }
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "word-$text")
        if (result == TextToSpeech.ERROR) {
            Toast.makeText(this, "发音启动失败", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun DictionaryApp(
    repository: DictionaryRepository,
    speechReady: Boolean,
    onSpeak: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<WordSummary>>(emptyList()) }
    var selectedWord by remember { mutableStateOf<WordDetail?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        delay(140)
        loading = query.isNotBlank()
        results = if (query.isBlank()) emptyList() else repository.search(query)
        loading = false
    }

    BackHandler(enabled = selectedWord != null) {
        selectedWord = null
    }

    BackHandler(enabled = selectedWord == null && query.isNotBlank()) {
        query = ""
        results = emptyList()
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        if (selectedWord == null) {
            SearchScreen(
                query = query,
                results = results,
                loading = loading,
                contentPadding = padding,
                onQuery = { query = it },
                onQuickSearch = { query = it },
                onOpen = { summary ->
                    scope.launch {
                        repository.addHistory(query.ifBlank { summary.word }, summary.id)
                        selectedWord = repository.detail(summary.id)
                    }
                },
            )
        } else {
            DetailScreen(
                detail = selectedWord!!,
                speechReady = speechReady,
                contentPadding = padding,
                onBack = { selectedWord = null },
                onSpeak = onSpeak,
            )
        }
    }
}

@Composable
private fun SearchScreen(
    query: String,
    results: List<WordSummary>,
    loading: Boolean,
    contentPadding: PaddingValues,
    onQuery: (String) -> Unit,
    onQuickSearch: (String) -> Unit,
    onOpen: (WordSummary) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + if (query.isBlank()) 96.dp else 14.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 20.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(if (query.isBlank()) 18.dp else 10.dp),
    ) {
        item { CompactHeader() }
        item { SearchBar(query = query, onQuery = onQuery) }

        if (query.isBlank()) {
            item { QuickSearch(onQuickSearch) }
        } else {
            item {
                Text(
                    text = if (loading) "\u6b63\u5728\u641c\u7d22" else "${results.size} \u4e2a\u7ed3\u679c",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (!loading && results.isEmpty()) {
                item { EmptyState() }
            }
            items(results, key = { it.id }) { word ->
                ResultItem(word = word, onOpen = { onOpen(word) })
            }
        }
    }
}

@Composable
private fun CompactHeader() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "MyEnglish",
            fontSize = 28.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "\u79bb\u7ebf\u82f1\u6c49\u8bcd\u5178",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 76.dp)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "\u67e5",
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                textAlign = TextAlign.Center,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                singleLine = true,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                decorationBox = { innerTextField ->
                    if (query.isBlank()) {
                        Text(
                            text = "\u8f93\u5165\u5355\u8bcd\u6216\u4e2d\u6587",
                            color = MaterialTheme.colorScheme.secondary,
                            fontSize = 22.sp,
                        )
                    }
                    innerTextField()
                },
            )
            if (query.isNotBlank()) {
                Text(
                    text = "\u6e05\u9664",
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onQuery("") }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickSearch(onQuickSearch: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "\u8bd5\u8bd5\u8fd9\u4e9b\u8bcd",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("apple", "one", "he", "dictionary", "\u91cd\u8981", "\u5f71\u54cd").forEach { word ->
                AssistChip(onClick = { onQuickSearch(word) }, label = { Text(word) })
            }
        }
    }
}

@Composable
private fun ResultItem(word: WordSummary, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = word.word,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                word.phoneticUs?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = word.primaryDefinition,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = "\u6ca1\u6709\u627e\u5230\u5339\u914d\u8bcd\u6761",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailScreen(
    detail: WordDetail,
    speechReady: Boolean,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onSpeak: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 18.dp,
            top = contentPadding.calculateTopPadding() + 10.dp,
            end = 18.dp,
            bottom = contentPadding.calculateBottomPadding() + 22.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("\u8fd4\u56de") }
            }
        }
        item {
            WordHeader(
                detail = detail,
                speechReady = speechReady,
                onSpeak = onSpeak,
            )
        }
        item { SectionTitle("\u91ca\u4e49") }
        items(detail.definitions) { definition ->
            DefinitionItem(
                pos = definition.partOfSpeech,
                chinese = definition.chinese,
                english = definition.english,
            )
        }
        if (detail.forms.isNotEmpty()) {
            item {
                SectionTitle("\u8bcd\u5f62\u53d8\u5316")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    detail.forms.take(12).forEach {
                        AssistChip(onClick = {}, label = { Text("${it.type}: ${it.value}") })
                    }
                }
            }
        }
    }
}

@Composable
private fun WordHeader(
    detail: WordDetail,
    speechReady: Boolean,
    onSpeak: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primary,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = detail.word,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    detail.phoneticUs?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
                        )
                    }
                }
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSpeak(detail.word) },
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = if (speechReady) 0.18f else 0.10f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = "\u53d1\u97f3",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            detail.difficulty?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f),
                )
            }
        }
    }
}

@Composable
private fun DefinitionItem(pos: String, chinese: String, english: String?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = pos.ifBlank { "\u91ca\u4e49" },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(text = chinese, style = MaterialTheme.typography.bodyLarge)
            if (!english.isNullOrBlank()) {
                Text(
                    text = english,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}
