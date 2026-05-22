package com.dograh.voiceagent.llm

import android.util.Log

enum class DetectedLanguage {
    HINDI,
    HINGLISH,
    TELUGU,
    TELUGU_ENGLISH,
    ENGLISH
}

class PromptRouter {

    /**
     * Identifies the primary dialect/language variant in the user transcript.
     * Uses sub-millisecond lexical heuristic keyword matching for high speed on edge.
     */
    fun detectLanguage(transcript: String): DetectedLanguage {
        val text = transcript.lowercase()

        // Telugu-specific keywords (Telugu script and transliterated)
        val teluguKeywords = listOf(
            "undi", "cheyyandi", "cheppandi", "matladandi", "avunu", "oddu", "vaddu",
            "ledu", "chala", "bagundi", "enti", "eppudu", "ekkada", "telugu", "namaskaram",
            "ఉంది", "చేయండి", "చెప్పండి", "మాట్లాడండి", "అవును", "వద్దు", "లేదు", "ఎప్పుడు"
        )

        // Hindi-specific keywords (Devanagari script and transliterated)
        val hindiKeywords = listOf(
            "kariye", "karo", "kahiye", "baat", "theek", "dobara", "samajh", "bhai",
            "namaste", "shukriya", "aaye", "gaya", "ho", "raha", "hai", "hain", "naam",
            "नमस्ते", "ठीक", "बात", "करो", "कहिए", "है", "हैं", "जी", "नाम", "दोबारा"
        )

        // Code-switching markers
        val hinglishMarkers = listOf("call karo", "transfer karo", "schedule karo", "meeting hai", "phone rakho")
        val teluguEnglishMarkers = listOf("schedule cheyyandi", "call transfer cheyyandi", "meeting undi", "otp cheppandi")

        var teluguScore = 0
        var hindiScore = 0

        teluguKeywords.forEach { if (text.contains(it)) teluguScore += 2 }
        teluguEnglishMarkers.forEach { if (text.contains(it)) teluguScore += 4 }

        hindiKeywords.forEach { if (text.contains(it)) hindiScore += 2 }
        hinglishMarkers.forEach { if (text.contains(it)) hindiScore += 4 }

        val lang = when {
            teluguScore > 0 && hindiScore == 0 -> DetectedLanguage.TELUGU_ENGLISH
            hindiScore > 0 && teluguScore == 0 -> DetectedLanguage.HINGLISH
            teluguScore > hindiScore -> DetectedLanguage.TELUGU
            hindiScore > teluguScore -> DetectedLanguage.HINDI
            else -> DetectedLanguage.ENGLISH
        }
        
        Log.d(TAG, "Language detected: $lang (Telugu score: $teluguScore, Hindi score: $hindiScore)")
        return lang
    }

    /**
     * Returns the tailored system prompt based on detected language and context.
     */
    fun getSystemPrompt(detectedLanguage: DetectedLanguage, pastHistoryContext: String): String {
        val basePrompt = """
            You are a super fast, polite, and helpful offline voice caller assistant.
            Keep your responses extremely short (1 to 2 sentences max) so that speech synthesis is quick.
            Never use markdown formatting, bullet points, or special characters like stars, as this is converted directly to speech.
            Local context from past interactions:
            $pastHistoryContext
        """.trimIndent()

        return when (detectedLanguage) {
            DetectedLanguage.HINGLISH -> """
                $basePrompt
                Style: Casual Hinglish (mixed Hindi-English transliterated in Latin text).
                Guidelines: Respond naturally using casual Hinglish as real users speak. Use mixed phrases like "Haan, main kar deta hoon" or "Bilkul, call transfer ho raha hai". Keep it simple, clear, and extremely concise.
            """.trimIndent()

            DetectedLanguage.TELUGU_ENGLISH -> """
                $basePrompt
                Style: Casual Telugu-English hybrid (mixed Telugu-English transliterated in Latin text).
                Guidelines: Respond naturally using casual Telugu-English hybrid phrasing (e.g. "Haan schedule cheyyandi", "Sure, meeting undi tomorrow"). Keep it conversational, warm, and very brief.
            """.trimIndent()

            DetectedLanguage.HINDI -> """
                $basePrompt
                Style: Pure Hindi (polite and respectful, using Devanagari or Latin Hindi text).
                Guidelines: Keep your sentences concise, formal, and helpful. (e.g., "जी, मैं आपकी सहायता अवश्य करूँगा।").
            """.trimIndent()

            DetectedLanguage.TELUGU -> """
                $basePrompt
                Style: Pure Telugu (respectful and clear).
                Guidelines: Respond in simple Telugu script or transliterated Telugu. (e.g., "అవును, నేను సహాయం చేస్తాను.").
            """.trimIndent()

            DetectedLanguage.ENGLISH -> """
                $basePrompt
                Style: Clear, concise professional English.
                Guidelines: Keep responses conversational, helpful, and direct. (e.g., "Sure, I can help with that. Let me look up the details.").
            """.trimIndent()
        }
    }

    companion object {
        private const val TAG = "PromptRouter"
    }
}
