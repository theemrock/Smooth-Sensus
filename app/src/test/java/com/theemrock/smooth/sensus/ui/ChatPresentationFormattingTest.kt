package com.theemrock.smooth.sensus.ui

import com.theemrock.smooth.sensus.conversation.ChatMessage
import com.theemrock.smooth.sensus.conversation.MessageInputMode
import com.theemrock.smooth.sensus.conversation.MessageRole
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPresentationFormattingTest {
    private val zone = ZoneId.of("Europe/Istanbul")
    private val locale = Locale.forLanguageTag("tr-TR")

    @Test fun `timeline inserts one localized separator before each calendar day`() {
        val first = message("m1", MessageRole.USER, 2026, 10, 1, 8, 42)
        val sameDay = message("m2", MessageRole.ASSISTANT, 2026, 10, 1, 9, 5)
        val nextDay = message("m3", MessageRole.USER, 2026, 10, 2, 0, 1)

        val timeline = buildChatTimeline(listOf(first, sameDay, nextDay), zone, locale)

        assertEquals(
            listOf("1 Ekim 2026 Perşembe", "m1", "m2", "2 Ekim 2026 Cuma", "m3"),
            timeline.map {
                when (it) {
                    is ChatTimelineItem.DateSeparator -> it.label
                    is ChatTimelineItem.Message -> it.value.id
                }
            }
        )
        assertEquals("08:42", formatMessageTime(first.timestamp, zone))
    }

    @Test fun `duration formatting keeps raw milliseconds out of domain data`() {
        assertEquals("2,9 sn", formatResponseDuration(2_940, locale))
    }

    @Test fun `markdown parser recognizes required block and inline forms`() {
        val blocks = parseAssistantMarkdown(
            """# Başlık

                |**Kalın** ve *italik* ve `kod`
                |- Madde
                |1. Birinci
                |```kotlin
                |val x = 1
                |```""".trimMargin()
        )

        assertTrue(blocks.any { it is MarkdownBlock.Heading && it.level == 1 && it.content.text == "Başlık" })
        val paragraph = blocks.filterIsInstance<MarkdownBlock.Paragraph>().single()
        assertEquals("Kalın ve italik ve kod", paragraph.content.text)
        assertEquals(
            setOf(MarkdownInlineStyle.BOLD, MarkdownInlineStyle.ITALIC, MarkdownInlineStyle.CODE),
            paragraph.content.spans.mapTo(mutableSetOf()) { it.style }
        )
        assertTrue(blocks.any { it is MarkdownBlock.Bullet && it.content.text == "Madde" })
        assertTrue(blocks.any { it is MarkdownBlock.Numbered && it.number == 1 && it.content.text == "Birinci" })
        assertTrue(blocks.any { it is MarkdownBlock.Code && it.code == "val x = 1" })
    }

    private fun message(id: String, role: MessageRole, year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        ChatMessage(
            id = id,
            conversationId = "c",
            role = role,
            content = id,
            timestamp = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli(),
            inputMode = MessageInputMode.TEXT,
            replyToMessageId = null
        )
}
