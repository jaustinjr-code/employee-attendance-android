package com.jaustinjr.employeeattendance.legal

import com.jaustinjr.employeeattendance.testutil.findAppModuleDir
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the legal document registry against the shipped assets. The assets are read straight off
 * the filesystem (they are declared as unit-test inputs in build.gradle.kts), so a registry entry
 * whose text was never added fails here rather than as an "unavailable" screen on a device.
 */
class LegalDocumentTest {

    private val assetsDir = File(findAppModuleDir(), "src/main/assets")

    @Test
    fun `ids are unique`() {
        val ids = LegalDocument.entries.map { it.id }
        // A duplicate would make one document unreachable through its route.
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every document resolves from its own id`() {
        LegalDocument.entries.forEach { assertSame(it, LegalDocument.fromId(it.id)) }
    }

    @Test
    fun `an unknown or missing id resolves to nothing`() {
        assertNull(LegalDocument.fromId("not-a-document"))
        assertNull(LegalDocument.fromId(null))
        // The enum name is not the id; a route must carry the stable id.
        assertNull(LegalDocument.fromId(LegalDocument.PRIVACY_POLICY.name))
    }

    @Test
    fun `every document has an asset that opens with its title heading`() {
        LegalDocument.entries.forEach { document ->
            val file = File(assetsDir, document.assetPath)
            assertTrue("${document.assetPath} is missing", file.isFile)

            val blocks = parseLegalText(file.readText())
            val first = blocks.firstOrNull()
            assertTrue(
                "${document.assetPath} must start with a level-1 heading, was $first",
                first is LegalBlock.Heading && first.level == 1,
            )
        }
    }

    @Test
    fun `the privacy policy states its effective date`() {
        val text = File(assetsDir, LegalDocument.PRIVACY_POLICY.assetPath).readText()

        val blocks = parseLegalText(text)
        assertTrue(
            "the privacy policy must carry an effective date under its title",
            blocks.getOrNull(1).let { it is LegalBlock.Paragraph && it.text.startsWith("Effective date:") },
        )
    }
}
