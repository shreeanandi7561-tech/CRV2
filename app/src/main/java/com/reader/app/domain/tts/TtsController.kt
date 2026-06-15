package com.reader.app.domain.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wraps Android's native TextToSpeech with full pitch / speed / language /
 * voice control plus a strict one-shot speak helper.
 *
 * **Word-level highlighting** is exposed through [currentWordRange]. When
 * the TTS engine starts speaking a word it fires [UtteranceProgressListener.onRangeStart]
 * with the `[start, end)` character range inside the spoken utterance —
 * we forward that as a `IntRange` and the UI uses it to draw a
 * one-word-at-a-time highlight inside the active chat message.
 */
class TtsController(
    private val appContext: Context
) {

    sealed interface State {
        data object Idle : State
        data object Initializing : State
        data object Ready : State
        data class Error(val message: String) : State
    }

    enum class PlaybackState {
        Idle,
        Preparing,
        Speaking,
        Paused,
        Resumed,
        Stopped,
        Cancelled,
        Recovering
    }

    private val _state          = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _isSpeaking          = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _currentIndex          = MutableStateFlow(0)
    val currentIndex: StateFlow<Int>   = _currentIndex.asStateFlow()

    private val _currentWordRange          = MutableStateFlow<IntRange?>(null)
    val currentWordRange: StateFlow<IntRange?> = _currentWordRange.asStateFlow()

    private val _pitch          = MutableStateFlow(DEFAULT_PITCH)
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    private val _speechRate          = MutableStateFlow(DEFAULT_RATE)
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    private val _languageTag          = MutableStateFlow(DEFAULT_LANG)
    val languageTag: StateFlow<String> = _languageTag.asStateFlow()

    private val _voiceName          = MutableStateFlow<String?>(null)
    val voiceName: StateFlow<String?> = _voiceName.asStateFlow()

    private val _availableVoices          = MutableStateFlow<List<Voice>>(emptyList())
    val availableVoices: StateFlow<List<Voice>> = _availableVoices.asStateFlow()

    private var chunks: List<String> = emptyList()
    private var tts: TextToSpeech? = null

    var onCursorAdvanced: ((Int) -> Unit)? = null

    /** Playback state tracking and session details */
    private var lastOriginalText: String? = null
    var activeContentId: String? = null
    var activeChunkIndex: Int = 0
    var subPositionOffset: Int = 0
    var currentHighlightAnchor: IntRange? = null
    var playbackSessionId: String? = null
    var pausedTimestamp: Long = 0L
    private var sessionOffset: Int = 0

    /** Map of unique one-shot utterance IDs to their onDone callbacks. */
    private val pendingOneShots = ConcurrentHashMap<String, () -> Unit>()
    private val oneShotCounter = AtomicInteger(0)

    private val mainScope = CoroutineScope(Dispatchers.Main)
    private var highlightJob: Job? = null
    private var receivedNativeRangeStart = false
    private val utteranceTexts = ConcurrentHashMap<String, String>()
    private val utteranceSpokenTexts = ConcurrentHashMap<String, String>()
    private val utteranceIndices = ConcurrentHashMap<String, IntArray>()

    fun init(
        languageTag: String = DEFAULT_LANG,
        pitch: Float = DEFAULT_PITCH,
        speechRate: Float = DEFAULT_RATE,
        voiceName: String? = null,
        onReady: (() -> Unit)? = null
    ) {
        if (_state.value == State.Ready) {
            applyPreferences(languageTag, pitch, speechRate, voiceName)
            onReady?.invoke()
            return
        }
        _state.value = State.Initializing
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(progressListener)
                applyPreferences(languageTag, pitch, speechRate, voiceName)
                _state.value = State.Ready
                onReady?.invoke()
            } else {
                _state.value = State.Error("TTS init failed: $status")
            }
        }
    }

    fun setLanguage(tag: String) {
        val normalizedTag = tag.replace('_', '-')
        tts?.language = Locale.forLanguageTag(normalizedTag)
        _languageTag.value = normalizedTag
        refreshVoiceList()
    }

    fun setPitch(value: Float) {
        val clamped = value.coerceIn(MIN_PITCH, MAX_PITCH)
        tts?.setPitch(clamped)
        _pitch.value = clamped
    }

    fun setSpeechRate(value: Float) {
        val clamped = value.coerceIn(MIN_RATE, MAX_RATE)
        tts?.setSpeechRate(clamped)
        _speechRate.value = clamped
    }

    fun setVoice(name: String?) {
        val engine = tts ?: return
        if (name == null) {
            engine.voice = engine.defaultVoice
            _voiceName.value = null
            return
        }
        val voice = engine.voices?.firstOrNull { it.name == name }
        if (voice != null) {
            engine.voice = voice
            _voiceName.value = name
        }
    }

    private fun applyPreferences(
        languageTag: String,
        pitch: Float,
        speechRate: Float,
        voiceName: String?
    ) {
        val engine = tts ?: return
        val normalizedTag = languageTag.replace('_', '-')
        engine.language = Locale.forLanguageTag(normalizedTag)
        engine.setPitch(pitch.coerceIn(MIN_PITCH, MAX_PITCH))
        engine.setSpeechRate(speechRate.coerceIn(MIN_RATE, MAX_RATE))
        _languageTag.value = normalizedTag
        _pitch.value       = pitch
        _speechRate.value  = speechRate
        refreshVoiceList()
        if (voiceName != null) setVoice(voiceName)
    }

    private fun refreshVoiceList() {
        val engine = tts ?: return
        val locale = Locale.forLanguageTag(_languageTag.value)
        _availableVoices.value = engine.voices?.filter { it.locale == locale }
            ?.sortedBy { it.name } ?: emptyList()
    }

    fun setChunks(newChunks: List<String>, startIndex: Int = 0) {
        chunks = newChunks
        _currentIndex.value = startIndex.coerceIn(0, newChunks.size)
    }

    fun start() {
        val engine = tts ?: return
        if (chunks.isEmpty()) return
        if (_currentIndex.value >= chunks.size) return
        playbackSessionId = java.util.UUID.randomUUID().toString()
        _playbackState.value = PlaybackState.Speaking
        subPositionOffset = 0
        sessionOffset = 0
        _isSpeaking.value = true
        activeChunkIndex = _currentIndex.value
        speakIndex(_currentIndex.value, engine)
    }

    fun pause() {
        _playbackState.value = PlaybackState.Paused
        tts?.stop()
        _isSpeaking.value = false
        _currentWordRange.value?.let { range ->
            subPositionOffset = range.first
        }
        _currentWordRange.value = null
        stopSimulatedHighlight()
        pausedTimestamp = System.currentTimeMillis()
    }

    fun reset() {
        _playbackState.value = PlaybackState.Stopped
        tts?.stop()
        _isSpeaking.value = false
        _currentIndex.value = 0
        _currentWordRange.value = null
        stopSimulatedHighlight()
        utteranceTexts.clear()
        utteranceSpokenTexts.clear()
        utteranceIndices.clear()
        lastOriginalText = null
        activeContentId = null
        activeChunkIndex = 0
        subPositionOffset = 0
        currentHighlightAnchor = null
        playbackSessionId = null
        sessionOffset = 0
    }

    data class MappedText(val text: String, val originalIndices: IntArray)

    private fun makeTtsFriendlyMapped(text: String): MappedText {
        if (text.isBlank()) return MappedText(text, IntArray(0))
        
        val currentTextChars = text.toCharArray()
        var currentMap = IntArray(text.length) { it }
        
        fun replaceWithMapping(search: String, replacement: String, inputStr: String, inputMap: IntArray): Pair<String, IntArray> {
            val sbText = StringBuilder()
            val newMapList = mutableListOf<Int>()
            
            var i = 0
            val len = inputStr.length
            val sLen = search.length
            while (i < len) {
                if (sLen > 0 && i + sLen <= len && inputStr.substring(i, i + sLen) == search) {
                    val origIndex = inputMap[i]
                    sbText.append(replacement)
                    for (k in 0 until replacement.length) {
                        newMapList.add(origIndex)
                    }
                    i += sLen
                } else {
                    sbText.append(inputStr[i])
                    newMapList.add(inputMap[i])
                    i++
                }
            }
            return Pair(sbText.toString(), newMapList.toIntArray())
        }
        
        var currentText = text
        
        // 1. Replace standard multiplication symbol * and × and LaTeX \cdot with " guna "
        val multReplacements = listOf("*", "×", "·")
        for (m in multReplacements) {
            val result = replaceWithMapping(m, " guna ", currentText, currentMap)
            currentText = result.first
            currentMap = result.second
        }
        
        // 2. Replace division / and ÷ with " batta " and " bhaage "
        var res = replaceWithMapping("/", " batta ", currentText, currentMap)
        currentText = res.first
        currentMap = res.second
        
        res = replaceWithMapping("÷", " bhaage ", currentText, currentMap)
        currentText = res.first
        currentMap = res.second
        
        // 3. Replace addition + with " plus "
        res = replaceWithMapping("+", " plus ", currentText, currentMap)
        currentText = res.first
        currentMap = res.second
        
        // 4. Replace equality = with " barabar "
        res = replaceWithMapping("=", " barabar ", currentText, currentMap)
        currentText = res.first
        currentMap = res.second
        
        // 5. Replace percentage % with " pratishat "
        res = replaceWithMapping("%", " pratishat ", currentText, currentMap)
        currentText = res.first
        currentMap = res.second
        
        fun replaceRegexWithMapping(pattern: Regex, rText: String, inputStr: String, inputMap: IntArray): Pair<String, IntArray> {
            val sbText = StringBuilder()
            val newMapList = mutableListOf<Int>()
            
            val matches = pattern.findAll(inputStr).toList()
            if (matches.isEmpty()) return Pair(inputStr, inputMap)
            
            var lastIdx = 0
            for (match in matches) {
                val start = match.range.first
                val end = match.range.last + 1
                
                for (i in lastIdx until start) {
                    sbText.append(inputStr[i])
                    newMapList.add(inputMap[i])
                }
                
                val matchStr = match.value
                val group1 = if (match.groups.size > 1) match.groups[1]?.value else null
                val group2 = if (match.groups.size > 2) match.groups[2]?.value else null
                
                val replacedRangeText = if (group1 != null && group2 != null) {
                    if (pattern.pattern.contains("anupaat")) {
                        "$group1 anupaat $group2"
                    } else if (pattern.pattern.contains("minus")) {
                        "$group1 minus $group2"
                    } else {
                        rText
                    }
                } else if (group1 != null) {
                    " minus $group1"
                } else {
                    rText
                }
                
                val oldLen = end - start
                val newLen = replacedRangeText.length
                for (k in 0 until newLen) {
                    val mapIdx = start + (k * oldLen / newLen).coerceIn(0, oldLen - 1)
                    sbText.append(replacedRangeText[k])
                    newMapList.add(inputMap[mapIdx])
                }
                
                lastIdx = end
            }
            
            for (i in lastIdx until inputStr.length) {
                sbText.append(inputStr[i])
                newMapList.add(inputMap[i])
            }
            
            return Pair(sbText.toString(), newMapList.toIntArray())
        }
        
        // 6. Replace colon as ratio with " anupaat "
        res = replaceWithMapping(" : ", " anupaat ", currentText, currentMap)
        currentText = res.first
        currentMap = res.second
        
        var rPair = replaceRegexWithMapping(Regex("(\\d)\\s*:\\s*(\\d)"), "$1 anupaat $2", currentText, currentMap)
        currentText = rPair.first
        currentMap = rPair.second
        
        rPair = replaceRegexWithMapping(Regex("\\b([xyzabcXYZABC])\\s*:\\s*([xyzabcXYZABC])\\b"), "$1 anupaat $2", currentText, currentMap)
        currentText = rPair.first
        currentMap = rPair.second
        
        // 7. Replace minus - with " minus "
        rPair = replaceRegexWithMapping(Regex("\\s+-\\s+"), " minus ", currentText, currentMap)
        currentText = rPair.first
        currentMap = rPair.second
        
        rPair = replaceRegexWithMapping(Regex("(\\d)-(\\d)"), "$1 minus $2", currentText, currentMap)
        currentText = rPair.first
        currentMap = rPair.second
        
        rPair = replaceRegexWithMapping(Regex("(?<=^|[\\s=()+\\/*×])-\\s*(\\d+)"), " minus $1", currentText, currentMap)
        currentText = rPair.first
        currentMap = rPair.second
        
        rPair = replaceRegexWithMapping(Regex("\\b([xyzabcXYZABC])\\s*-\\s*([xyzabcXYZABC])\\b"), "$1 minus $2", currentText, currentMap)
        currentText = rPair.first
        currentMap = rPair.second
        
        // 8. Clean up multiple whitespaces to keep speech rhythm natural
        val sbCollapse = StringBuilder()
        val collapsedMap = mutableListOf<Int>()
        var i = 0
        while (i < currentText.length) {
            if (currentText[i].isWhitespace()) {
                sbCollapse.append(' ')
                collapsedMap.add(currentMap[i])
                i++
                while (i < currentText.length && currentText[i].isWhitespace()) {
                    i++
                }
            } else {
                sbCollapse.append(currentText[i])
                collapsedMap.add(currentMap[i])
                i++
            }
        }
        
        val trimmedText = sbCollapse.toString().trim()
        val startIndex = sbCollapse.indexOf(trimmedText).coerceAtLeast(0)
        val finalMap = collapsedMap.subList(startIndex, (startIndex + trimmedText.length).coerceAtMost(collapsedMap.size)).toIntArray()
        
        return MappedText(trimmedText, finalMap)
    }

    private fun makeTtsFriendly(text: String): String {
        return makeTtsFriendlyMapped(text).text
    }

    /**
     * Speak a single ad-hoc string. Each call gets a unique utteranceId so
     * back-to-back calls don't collide and silently drop in the engine queue.
     *
     * STRICT GUARANTEE: onDone fires for every terminal state.
     */
    fun speakOneShot(text: String, onDone: (() -> Unit)? = null) {
        val engine = tts ?: run { onDone?.invoke(); return }
        if (text.isBlank()) { onDone?.invoke(); return }

        val isResume = _playbackState.value == PlaybackState.Paused && text == lastOriginalText

        if (isResume) {
            _playbackState.value = PlaybackState.Resumed
            sessionOffset = subPositionOffset
        } else {
            playbackSessionId = java.util.UUID.randomUUID().toString()
            lastOriginalText = text
            subPositionOffset = 0
            sessionOffset = 0
            _playbackState.value = PlaybackState.Speaking
        }

        val textToSpeak = if (isResume && subPositionOffset > 0 && subPositionOffset < text.length) {
            text.substring(subPositionOffset)
        } else {
            text
        }

        if (textToSpeak.isBlank()) {
            _playbackState.value = PlaybackState.Idle
            onDone?.invoke()
            return
        }

        val id = "session:${playbackSessionId}:oneshot:${oneShotCounter.incrementAndGet()}"
        val mapped = makeTtsFriendlyMapped(textToSpeak)
        utteranceTexts[id] = textToSpeak
        utteranceSpokenTexts[id] = mapped.text
        utteranceIndices[id] = mapped.originalIndices
        if (onDone != null) pendingOneShots[id] = onDone

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        }
        _isSpeaking.value = true
        _currentWordRange.value = null
        
        engine.speak(mapped.text, TextToSpeech.QUEUE_FLUSH, params, id)
    }

    fun shutdown() {
        _playbackState.value = PlaybackState.Stopped
        tts?.stop()
        tts?.shutdown()
        tts = null
        _state.value = State.Idle
        _isSpeaking.value = false
        _currentWordRange.value = null
        stopSimulatedHighlight()
        utteranceTexts.clear()
        utteranceSpokenTexts.clear()
        utteranceIndices.clear()
        lastOriginalText = null
        activeContentId = null
        activeChunkIndex = 0
        subPositionOffset = 0
        currentHighlightAnchor = null
        playbackSessionId = null
        sessionOffset = 0
        pendingOneShots.values.forEach { runCatching { it.invoke() } }
        pendingOneShots.clear()
    }

    private fun extractSessionId(utteranceId: String?): String? {
        if (utteranceId == null) return null
        if (utteranceId.startsWith("session:")) {
            val parts = utteranceId.split(":")
            if (parts.size >= 2) {
                return parts[1]
            }
        }
        return null
    }

    private fun extractIndex(utteranceId: String?): Int? {
        if (utteranceId == null) return null
        if (utteranceId.startsWith("session:")) {
            val parts = utteranceId.split(":")
            if (parts.size >= 4 && parts[2] == "chunk") {
                return parts[3].toIntOrNull()
            }
        }
        return null
    }

    private fun isOneShotId(utteranceId: String?): Boolean {
        if (utteranceId == null) return false
        if (utteranceId.startsWith("session:")) {
            val parts = utteranceId.split(":")
            return parts.size >= 3 && parts[2] == "oneshot"
        }
        return false
    }

    private fun speakIndex(index: Int, engine: TextToSpeech) {
        val text = chunks.getOrNull(index) ?: return
        val id = "session:${playbackSessionId}:chunk:${index}"
        val mapped = makeTtsFriendlyMapped(text)
        utteranceTexts[id] = text
        utteranceSpokenTexts[id] = mapped.text
        utteranceIndices[id] = mapped.originalIndices
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        }
        engine.speak(mapped.text, TextToSpeech.QUEUE_FLUSH, params, id)
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val sessId = extractSessionId(utteranceId)
            if (sessId != playbackSessionId) {
                return
            }
            _currentWordRange.value = null
            if (utteranceId != null) {
                val spoken = utteranceSpokenTexts[utteranceId]
                val origIndices = utteranceIndices[utteranceId]
                if (spoken != null && origIndices != null) {
                    startSimulatedHighlight(utteranceId, spoken, origIndices)
                }
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            val sessId = extractSessionId(utteranceId)
            if (sessId != playbackSessionId) {
                return
            }
            receivedNativeRangeStart = true
            if (utteranceId != null) {
                val origIndices = utteranceIndices[utteranceId]
                if (origIndices != null) {
                    val origStart = origIndices.getOrNull(start) ?: start
                    val origEnd = origIndices.getOrNull((end - 1).coerceAtLeast(0))?.plus(1) ?: end
                    
                    val finalStart = origStart + sessionOffset
                    val finalEnd = origEnd + sessionOffset
                    _currentWordRange.value = finalStart until finalEnd
                    subPositionOffset = finalStart
                }
            }
        }

        override fun onDone(utteranceId: String?) {
            if (utteranceId == null) return
            val sessId = extractSessionId(utteranceId)
            if (sessId != playbackSessionId) {
                return
            }
            _currentWordRange.value = null
            utteranceTexts.remove(utteranceId)
            utteranceSpokenTexts.remove(utteranceId)
            utteranceIndices.remove(utteranceId)
            stopSimulatedHighlight()
            if (isOneShotId(utteranceId)) {
                _isSpeaking.value = false
                _playbackState.value = PlaybackState.Idle
                val cb = pendingOneShots.remove(utteranceId)
                cb?.invoke()
                return
            }
            val justSpoken = extractIndex(utteranceId) ?: return
            val next = justSpoken + 1
            _currentIndex.value = next
            onCursorAdvanced?.invoke(next)
            val engine = tts
            if (_isSpeaking.value && engine != null && next < chunks.size) {
                activeChunkIndex = next
                speakIndex(next, engine)
            } else {
                _isSpeaking.value = false
                _playbackState.value = PlaybackState.Idle
            }
        }

        @Deprecated("Deprecated, but still required.")
        override fun onError(utteranceId: String?) {
            if (utteranceId == null) return
            val sessId = extractSessionId(utteranceId)
            if (sessId != playbackSessionId) {
                return
            }
            _isSpeaking.value = false
            _playbackState.value = PlaybackState.Idle
            _currentWordRange.value = null
            utteranceTexts.remove(utteranceId)
            utteranceSpokenTexts.remove(utteranceId)
            utteranceIndices.remove(utteranceId)
            stopSimulatedHighlight()
            if (isOneShotId(utteranceId)) {
                val cb = pendingOneShots.remove(utteranceId)
                cb?.invoke()
                return
            }
            _state.value = State.Error("TTS error on $utteranceId")
        }
    }

    private fun utteranceIdFor(index: Int) = "session:${playbackSessionId}:chunk:${index}"
    private fun parseIndex(id: String): Int? = extractIndex(id)

    data class WordRange(val start: Int, val end: Int, val word: String)

    private fun getWordRanges(text: String): List<WordRange> {
        val list = mutableListOf<WordRange>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && (text[i].isWhitespace() || isSeparator(text[i]))) {
                i++
            }
            if (i >= text.length) break
            val start = i
            while (i < text.length && !text[i].isWhitespace() && !isSeparator(text[i])) {
                i++
            }
            val end = i
            if (start < end) {
                list.add(WordRange(start, i, text.substring(start, i)))
            }
        }
        return list
    }

    private fun isSeparator(c: Char): Boolean {
        return c == ',' || c == '.' || c == '?' || c == '!' || c == ';' || c == ':' || 
               c == '"' || c == '\'' || c == '(' || c == ')' || c == '[' || c == ']' || 
               c == '{' || c == '}' || c == '-' || c == '—' || c == '।' || c == '॥'
    }

    private fun startSimulatedHighlight(utteranceId: String, text: String, origIndices: IntArray) {
        highlightJob?.cancel()
        receivedNativeRangeStart = false
        highlightJob = mainScope.launch {
            val wordRanges = getWordRanges(text)
            if (wordRanges.isEmpty()) return@launch

            for (range in wordRanges) {
                val sessId = extractSessionId(utteranceId)
                if (sessId != playbackSessionId) {
                    break
                }
                if (receivedNativeRangeStart) {
                    break
                }
                
                val origStart = origIndices.getOrNull(range.start) ?: range.start
                val origEnd = origIndices.getOrNull((range.end - 1).coerceAtLeast(0))?.plus(1) ?: range.end
                
                val finalStart = origStart + sessionOffset
                val finalEnd = origEnd + sessionOffset
                _currentWordRange.value = finalStart until finalEnd
                subPositionOffset = finalStart

                val word = range.word
                val charDuration = 45 
                val wordPadding = 135  
                val calculatedWordDurationMs = (word.length * charDuration + wordPadding)

                val currentRate = _speechRate.value.coerceIn(MIN_RATE, MAX_RATE)
                val durationMs = (calculatedWordDurationMs / currentRate).toLong()

                delay(durationMs)
            }
        }
    }

    private fun stopSimulatedHighlight() {
        highlightJob?.cancel()
        highlightJob = null
    }

    companion object {
        private const val CHUNK_ID_PREFIX  = "chunk_"
        private const val ONE_SHOT_PREFIX  = "one_shot_"

        const val DEFAULT_LANG  = "hi-IN"
        const val DEFAULT_PITCH = 1.0f
        const val DEFAULT_RATE  = 1.0f

        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 2.0f
        const val MIN_RATE  = 0.5f
        const val MAX_RATE  = 2.0f
    }
}
