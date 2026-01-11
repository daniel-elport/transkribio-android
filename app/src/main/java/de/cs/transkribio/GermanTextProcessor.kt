package de.cs.transkribio

/**
 * Text post-processor for improving transcription quality.
 *
 * Two levels of processing:
 * 1. GENERAL (always on): Remove bracket tokens, basic cleanup
 * 2. GERMAN (optional): Language-specific improvements
 *
 * CONSERVATIVE APPROACH: Only make changes that are safe and unlikely to corrupt text.
 * It's better to leave imperfect text than to introduce errors.
 */
object GermanTextProcessor {

    // Pattern to match any bracket token (complete or incomplete)
    // Matches: [MUSIK], [MOTOR, [anything...
    private val BRACKET_TOKEN_PATTERN = Regex("""\[[A-Z_]*\]?""", RegexOption.IGNORE_CASE)

    // Whisper special tokens to remove entirely
    private val SPECIAL_TOKENS = listOf(
        // German
        "[MUSIK]", "[APPLAUS]", "[LACHEN]", "[RÄUSPERN]", "[HUSTEN]",
        "[GERÄUSCH]", "[STILLE]", "[UNVERSTÄNDLICH]", "[PAUSE]",
        // English (Whisper sometimes outputs these)
        "[MUSIC]", "[APPLAUSE]", "[LAUGHTER]", "[COUGH]", "[SNEEZE]",
        "[BLANK_AUDIO]", "[NO_SPEECH]", "[SILENCE]", "[NOISE]",
        "[INAUDIBLE]", "[BACKGROUND_NOISE]",
        // Parenthesized versions
        "(Musik)", "(Applaus)", "(Lachen)", "(Husten)", "(Pause)",
        "(Music)", "(Applause)", "(Laughter)", "(Cough)",
        // Music symbols
        "♪", "♫", "🎵", "🎶"
    )

    // Regex for bracketed/parenthesized non-speech annotations
    // Only match known patterns, not arbitrary text
    private val ANNOTATION_PATTERN = Regex(
        """\[(MUSIK|MUSIC|APPLAUS|APPLAUSE|LACHEN|LAUGHTER|PAUSE|STILLE|SILENCE|GERÄUSCH|NOISE|HUSTEN|COUGH)\]""",
        RegexOption.IGNORE_CASE
    )

    // Sentence boundary detection for paragraphing
    private val SENTENCE_END = Regex("""[.!?]+\s*""")

    // Words that typically start a new topic/paragraph
    private val PARAGRAPH_STARTERS = listOf(
        "Erstens", "Zweitens", "Drittens", "Viertens",
        "Zunächst", "Dann", "Danach", "Schließlich", "Abschließend",
        "Außerdem", "Darüber hinaus", "Des Weiteren", "Ferner",
        "Jedoch", "Allerdings", "Dennoch", "Trotzdem",
        "Also", "Zusammenfassend", "Insgesamt", "Letztendlich",
        "Einerseits", "Andererseits",
        "Zum einen", "Zum anderen",
        "Im Gegensatz", "Im Vergleich",
        "Beispielsweise", "Zum Beispiel",
        "Das bedeutet", "Das heißt",
        "Wichtig ist", "Interessant ist", "Bemerkenswert ist"
    )

    /**
     * GENERAL cleanup - ALWAYS runs, regardless of settings.
     * Removes all bracket tokens (complete or incomplete like [MOTOR, [MUSIK], etc.)
     * and cleans up whitespace.
     */
    fun generalCleanup(text: String): String {
        if (text.isBlank()) return ""

        var result = text

        // Remove ALL bracket tokens (complete or incomplete)
        result = BRACKET_TOKEN_PATTERN.replace(result, "")

        // Remove music symbols
        result = result.replace("♪", "").replace("♫", "").replace("🎵", "").replace("🎶", "")

        // Clean up whitespace
        result = result.replace(Regex("""\s+"""), " ").trim()

        return result
    }

    /**
     * Process a single transcription segment with German-specific improvements.
     * Returns cleaned text with minimal, safe corrections.
     * NOTE: generalCleanup() should be called first.
     */
    fun processSegment(text: String): String {
        if (text.isBlank()) return ""

        var result = text

        // Step 1: Remove special tokens (SAFE)
        result = removeSpecialTokens(result)

        // Step 2: Clean up whitespace (SAFE)
        result = cleanWhitespace(result)

        // Step 3: Basic capitalization - only first letter (SAFE)
        result = capitalizeFirstLetter(result)

        // Step 4: Basic punctuation cleanup (SAFE)
        result = cleanPunctuation(result)

        return result.trim()
    }

    /**
     * Process multiple segments and format into paragraphs.
     * Groups related sentences together for better readability.
     */
    fun formatAsParagraphs(segments: List<String>, maxSentencesPerParagraph: Int = 4): String {
        val processedSegments = segments.map { processSegment(it) }.filter { it.isNotEmpty() }

        if (processedSegments.isEmpty()) return ""
        if (processedSegments.size == 1) return processedSegments.first()

        val paragraphs = mutableListOf<StringBuilder>()
        var currentParagraph = StringBuilder()
        var sentenceCount = 0

        for (segment in processedSegments) {
            val startsNewParagraph = PARAGRAPH_STARTERS.any {
                segment.startsWith(it, ignoreCase = true)
            }

            if (startsNewParagraph && currentParagraph.isNotEmpty()) {
                paragraphs.add(currentParagraph)
                currentParagraph = StringBuilder()
                sentenceCount = 0
            }

            if (currentParagraph.isNotEmpty()) {
                currentParagraph.append(" ")
            }
            currentParagraph.append(segment)

            // Count sentences in this segment
            sentenceCount += SENTENCE_END.findAll(segment).count().coerceAtLeast(1)

            // Start new paragraph after max sentences
            if (sentenceCount >= maxSentencesPerParagraph) {
                paragraphs.add(currentParagraph)
                currentParagraph = StringBuilder()
                sentenceCount = 0
            }
        }

        if (currentParagraph.isNotEmpty()) {
            paragraphs.add(currentParagraph)
        }

        return paragraphs.joinToString("\n\n") { it.toString().trim() }
    }

    private fun removeSpecialTokens(text: String): String {
        var result = text

        // Remove exact matches (case-insensitive)
        for (token in SPECIAL_TOKENS) {
            result = result.replace(token, "", ignoreCase = true)
        }

        // Remove known annotation patterns
        result = ANNOTATION_PATTERN.replace(result, "")

        return result
    }

    private fun cleanWhitespace(text: String): String {
        return text
            .replace(Regex("""\s+"""), " ")  // Multiple spaces/newlines to single space
            .trim()
    }

    private fun capitalizeFirstLetter(text: String): String {
        if (text.isEmpty()) return text

        // Only capitalize the very first letter of the text
        return text.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }
    }

    private fun cleanPunctuation(text: String): String {
        var result = text

        // Remove space before punctuation
        result = Regex("""\s+([.,!?:;])""").replace(result, "$1")

        // Ensure single space after punctuation (if followed by letter)
        result = Regex("""([.,!?:;])(?=\p{L})""").replace(result, "$1 ")

        // Remove duplicate punctuation
        result = Regex("""([.!?])\1+""").replace(result, "$1")
        result = Regex("""([,;:])\1+""").replace(result, "$1")

        return result
    }

    /**
     * Check if text is likely just noise/non-speech.
     * Returns true if the text should be discarded.
     *
     * CONSERVATIVE: Only filter out very obvious noise.
     */
    fun isNoise(text: String): Boolean {
        val cleaned = removeSpecialTokens(text).trim()

        // Empty after cleaning special tokens
        if (cleaned.isEmpty()) return true

        // Only punctuation or special characters (no letters at all)
        if (cleaned.all { !it.isLetter() }) return true

        // Very short and only special characters
        if (cleaned.length == 1 && !cleaned[0].isLetterOrDigit()) return true

        return false
    }
}
