package com.theemrock.smooth.sensus.ui

import com.theemrock.smooth.sensus.conversation.ChatMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal sealed interface ChatTimelineItem {
    data class DateSeparator(val epochDay: Long, val label: String) : ChatTimelineItem
    data class Message(val value: ChatMessage) : ChatTimelineItem
}

internal fun buildChatTimeline(
    messages: List<ChatMessage>,
    zoneId: ZoneId,
    locale: Locale
): List<ChatTimelineItem> = buildList {
    var previousEpochDay: Long? = null
    val dateFormatter = DateTimeFormatter.ofPattern("d MMMM uuuu EEEE", locale)
    messages.forEach { message ->
        val date = Instant.ofEpochMilli(message.timestamp).atZone(zoneId).toLocalDate()
        if (date.toEpochDay() != previousEpochDay) {
            add(ChatTimelineItem.DateSeparator(date.toEpochDay(), date.format(dateFormatter)))
            previousEpochDay = date.toEpochDay()
        }
        add(ChatTimelineItem.Message(message))
    }
}

internal fun formatMessageTime(timestamp: Long, zoneId: ZoneId): String =
    Instant.ofEpochMilli(timestamp).atZone(zoneId).format(DateTimeFormatter.ofPattern("HH:mm"))

internal fun formatResponseDuration(durationMs: Long, locale: Locale): String =
    String.format(locale, "%.1f sn", durationMs.coerceAtLeast(0) / 1_000.0)

internal enum class MarkdownInlineStyle { BOLD, ITALIC, CODE }

internal data class MarkdownSpan(val start: Int, val end: Int, val style: MarkdownInlineStyle)
internal data class MarkdownInline(val text: String, val spans: List<MarkdownSpan>)

internal sealed interface MarkdownBlock {
    data class Paragraph(val content: MarkdownInline) : MarkdownBlock
    data class Heading(val level: Int, val content: MarkdownInline) : MarkdownBlock
    data class Bullet(val content: MarkdownInline) : MarkdownBlock
    data class Numbered(val number: Int, val content: MarkdownInline) : MarkdownBlock
    data class Code(val code: String, val language: String?) : MarkdownBlock
}

internal fun parseAssistantMarkdown(source: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val lines = source.lines()
    var index = 0

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(parseInlineMarkdown(paragraph.joinToString("\n")))
            paragraph.clear()
        }
    }

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                flushParagraph()
                val language = trimmed.removePrefix("```").trim().ifEmpty { null }
                val code = mutableListOf<String>()
                index++
                while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                    code += lines[index]
                    index++
                }
                blocks += MarkdownBlock.Code(code.joinToString("\n"), language)
            }
            trimmed.isBlank() -> flushParagraph()
            HEADING.matches(trimmed) -> {
                flushParagraph()
                val match = requireNotNull(HEADING.matchEntire(trimmed))
                blocks += MarkdownBlock.Heading(match.groupValues[1].length, parseInlineMarkdown(match.groupValues[2]))
            }
            BULLET.matches(trimmed) -> {
                flushParagraph()
                val match = requireNotNull(BULLET.matchEntire(trimmed))
                blocks += MarkdownBlock.Bullet(parseInlineMarkdown(match.groupValues[1]))
            }
            NUMBERED.matches(trimmed) -> {
                flushParagraph()
                val match = requireNotNull(NUMBERED.matchEntire(trimmed))
                blocks += MarkdownBlock.Numbered(match.groupValues[1].toInt(), parseInlineMarkdown(match.groupValues[2]))
            }
            else -> paragraph += line
        }
        index++
    }
    flushParagraph()
    return blocks
}

private fun parseInlineMarkdown(source: String): MarkdownInline {
    val text = StringBuilder()
    val spans = mutableListOf<MarkdownSpan>()
    var index = 0
    while (index < source.length) {
        val marker = when {
            source.startsWith("**", index) -> "**" to MarkdownInlineStyle.BOLD
            source.startsWith("__", index) -> "__" to MarkdownInlineStyle.BOLD
            source.startsWith("`", index) -> "`" to MarkdownInlineStyle.CODE
            source.startsWith("*", index) -> "*" to MarkdownInlineStyle.ITALIC
            source.startsWith("_", index) -> "_" to MarkdownInlineStyle.ITALIC
            else -> null
        }
        if (marker == null) {
            text.append(source[index++])
            continue
        }
        val (delimiter, style) = marker
        val closing = source.indexOf(delimiter, index + delimiter.length)
        if (closing < 0 || closing == index + delimiter.length) {
            text.append(source[index++])
            continue
        }
        val start = text.length
        text.append(source, index + delimiter.length, closing)
        spans += MarkdownSpan(start, text.length, style)
        index = closing + delimiter.length
    }
    return MarkdownInline(text.toString(), spans)
}

private val HEADING = Regex("^(#{1,6})\\s+(.+)$")
private val BULLET = Regex("^[-+*]\\s+(.+)$")
private val NUMBERED = Regex("^(\\d+)[.)]\\s+(.+)$")
