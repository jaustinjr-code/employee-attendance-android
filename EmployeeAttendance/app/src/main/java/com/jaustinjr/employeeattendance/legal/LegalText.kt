package com.jaustinjr.employeeattendance.legal

/** One rendered unit of a legal document. */
sealed interface LegalBlock {
    /** A heading; [level] 1 is the document title, 2 a section, 3 a subsection. */
    data class Heading(val level: Int, val text: String) : LegalBlock {
        init {
            require(level in 1..3) { "heading level must be 1..3, was $level" }
        }
    }

    data class Paragraph(val text: String) : LegalBlock

    data class Bullet(val text: String) : LegalBlock
}

private val headingPattern = Regex("""^(#{1,3})\s+(.*)$""")
private val bulletPattern = Regex("""^[-*]\s+(.*)$""")

/**
 * Parses the small Markdown subset legal documents are written in: `#`/`##`/`###` headings, `- `
 * (or `* `) bullets, and paragraphs separated by blank lines. Consecutive non-blank lines join into
 * one paragraph (or continue the bullet above them), as Markdown does, so the source can be
 * hard-wrapped.
 *
 * Anything else — emphasis, links, numbered lists — is kept as literal text rather than half
 * rendered. That is deliberate: legal wording must reach the reader verbatim, so an unsupported
 * construct should look wrong in review, not silently lose characters.
 */
fun parseLegalText(source: String): List<LegalBlock> {
    val blocks = mutableListOf<LegalBlock>()
    // The block being accumulated: lines joined with spaces until a blank line or a new block.
    var pending: StringBuilder? = null
    var pendingIsBullet = false

    fun flush() {
        val text = pending?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            blocks += if (pendingIsBullet) LegalBlock.Bullet(text) else LegalBlock.Paragraph(text)
        }
        pending = null
        pendingIsBullet = false
    }

    source.lineSequence().map { it.trim() }.forEach { line ->
        val heading = headingPattern.matchEntire(line)
        val bullet = bulletPattern.matchEntire(line)
        when {
            line.isEmpty() -> flush()
            heading != null -> {
                flush()
                blocks += LegalBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
            }
            bullet != null -> {
                flush()
                pending = StringBuilder(bullet.groupValues[1])
                pendingIsBullet = true
            }
            else -> {
                val current = pending
                if (current == null) pending = StringBuilder(line) else current.append(' ').append(line)
            }
        }
    }
    flush()
    return blocks
}
