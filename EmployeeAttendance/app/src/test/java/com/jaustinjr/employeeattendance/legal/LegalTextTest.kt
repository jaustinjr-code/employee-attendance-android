package com.jaustinjr.employeeattendance.legal

import com.jaustinjr.employeeattendance.legal.LegalBlock.Bullet
import com.jaustinjr.employeeattendance.legal.LegalBlock.Heading
import com.jaustinjr.employeeattendance.legal.LegalBlock.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Test

class LegalTextTest {

    @Test
    fun `headings bullets and paragraphs are recognised`() {
        val source = """
            # Title

            Intro paragraph.

            ## Section

            ### Subsection

            - first
            * second
        """.trimIndent()

        assertEquals(
            listOf(
                Heading(1, "Title"),
                Paragraph("Intro paragraph."),
                Heading(2, "Section"),
                Heading(3, "Subsection"),
                Bullet("first"),
                Bullet("second"),
            ),
            parseLegalText(source),
        )
    }

    @Test
    fun `hard-wrapped lines join into one paragraph or bullet`() {
        val source = """
            A paragraph that
            wraps across lines.
            - a bullet that
              continues here
            Next line after a bullet continues it too.
        """.trimIndent()

        assertEquals(
            listOf(
                Paragraph("A paragraph that wraps across lines."),
                Bullet("a bullet that continues here Next line after a bullet continues it too."),
            ),
            parseLegalText(source),
        )
    }

    @Test
    fun `a blank line ends a paragraph`() {
        assertEquals(
            listOf(Paragraph("one"), Paragraph("two")),
            parseLegalText("one\n\n\ntwo\n"),
        )
    }

    @Test
    fun `a heading directly after text ends the paragraph`() {
        assertEquals(
            listOf(Paragraph("text"), Heading(2, "Next")),
            parseLegalText("text\n## Next"),
        )
    }

    @Test
    fun `unsupported markup is kept verbatim rather than dropped`() {
        // Legal wording must reach the reader intact: deeper headings, emphasis and numbered lists
        // stay literal text so they show up in review instead of silently losing characters.
        assertEquals(
            listOf(Paragraph("#### Deep **bold** 1. numbered")),
            parseLegalText("#### Deep **bold** 1. numbered"),
        )
    }

    @Test
    fun `markers without a following space are text`() {
        assertEquals(
            listOf(Paragraph("#hashtag -dash")),
            parseLegalText("#hashtag\n-dash"),
        )
    }

    @Test
    fun `empty input yields no blocks`() {
        assertEquals(emptyList<LegalBlock>(), parseLegalText(""))
        assertEquals(emptyList<LegalBlock>(), parseLegalText("\n  \n"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `heading levels are bounded`() {
        Heading(4, "too deep")
    }
}
