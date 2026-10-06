package ru.transcrib.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TextAssemblerTest {
    // Real GigaAM v3 output for "Добрый день, коллеги. Давайте начнём наше совещание."
    private val tokens = arrayOf(
        " До", "б", "ры", "й", " день", ",", " ко", "л", "ле", "ги", ".",
        " Давай", "те", " на", "ч", "н", "ём", " на", "ше", " со", "ве", "ща", "ние", ".",
    )
    private val ts = floatArrayOf(
        0.08f, 0.16f, 0.24f, 0.32f, 0.36f, 0.52f, 0.72f, 0.84f, 0.92f, 1.08f, 1.28f,
        1.60f, 1.84f, 2.00f, 2.12f, 2.20f, 2.24f, 2.36f, 2.48f, 2.60f, 2.72f, 2.88f, 3.04f, 3.24f,
    )

    @Test
    fun wordsJoinPiecesAndAttachPunctuation() {
        val words = TextAssembler.words(tokens, ts, 10.0, 14.0)
        assertEquals(listOf("Добрый", "день,", "коллеги.", "Давайте", "начнём", "наше", "совещание."), words.map { it.text })
        assertEquals(10.08, words[0].start, 1e-6)
        assertEquals(11.60, words[3].start, 1e-6)
        // Word end never passes the next word start.
        for (i in 0 until words.size - 1) assert(words[i].end <= words[i + 1].start + 1e-9)
    }

    @Test
    fun sentencesWithoutSpeakers() {
        val words = TextAssembler.words(tokens, ts, 0.0, 4.0)
        val u = TextAssembler.assemble(words, null)
        assertEquals(listOf("Добрый день, коллеги.", "Давайте начнём наше совещание."), u.map { it.text })
        assertEquals(0, u[0].speaker)
    }

    @Test
    fun speakersAreAssignedPerSentence() {
        val words = TextAssembler.words(tokens, ts, 0.0, 4.0)
        val turns = listOf(SpeakerTurn(0.0, 1.4, 0), SpeakerTurn(1.5, 4.0, 1))
        val u = TextAssembler.assemble(words, turns)
        assertEquals(listOf(0, 1), u.map { it.speaker })
    }

    @Test
    fun speakerChangeInsideSentenceSplitsIt() {
        // "да я согласен но есть риски связанные с интеграцией" without punctuation, two voices.
        val t = arrayOf(" да", " я", " согласен", " но", " есть", " риски", " связанные", " с", " интеграцией")
        val s = floatArrayOf(0.0f, 0.4f, 0.6f, 2.0f, 2.3f, 2.6f, 3.0f, 3.5f, 3.7f)
        val words = TextAssembler.words(t, s, 0.0, 5.0)
        val u = TextAssembler.assemble(words, listOf(SpeakerTurn(0.0, 1.5, 0), SpeakerTurn(1.9, 5.0, 1)))
        assertEquals(2, u.size)
        assertEquals("Да я согласен", u[0].text)
        assertEquals("Но есть риски связанные с интеграцией", u[1].text)
        assertEquals(listOf(0, 1), u.map { it.speaker })
    }

    @Test
    fun shortDiarizationFlickerIsIgnored() {
        val words = TextAssembler.words(tokens, ts, 0.0, 4.0)
        // Speaker 1 appears for 0.2 s in the middle of speaker 0's sentence.
        val turns = listOf(SpeakerTurn(0.0, 0.7, 0), SpeakerTurn(0.7, 0.9, 1), SpeakerTurn(0.9, 4.0, 0))
        val u = TextAssembler.assemble(words, turns)
        assertEquals(2, u.size)
        assertEquals(listOf(0, 0), u.map { it.speaker })
    }
}
