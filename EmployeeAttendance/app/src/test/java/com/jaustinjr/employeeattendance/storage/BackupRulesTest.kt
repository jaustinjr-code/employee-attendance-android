package com.jaustinjr.employeeattendance.storage

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Guards issue #9: no SharedPreferences store this app writes may leave the device via Google
 * auto-backup (API < 31, `@xml/backup_rules`) or cloud backup / device transfer (API 31+,
 * `@xml/data_extraction_rules`).
 *
 * The test discovers every `const val PREFS_NAME = "..."` in main sources rather than hard-coding a
 * list, so adding a new store without adding exclude rules fails the build instead of silently
 * shipping a fail-open backup config.
 *
 * Each store contributes two on-disk file names: `<name>_secure.xml`, which is what
 * [SecurePreferences] actually writes (EncryptedSharedPreferences), and the legacy plaintext
 * `<name>.xml`, which older installs may still have on disk and which
 * `SecurePreferences.migratePlaintext` reads back in on the next launch. Both must be excluded —
 * the second one so that a restored plaintext file can't be migrated into the encrypted store.
 */
class BackupRulesTest {

    private val moduleDir: File = findModuleDir()

    private val xmlDir = File(moduleDir, "src/main/res/xml")

    /** Names discovered from `const val PREFS_NAME = "..."` declarations in main sources. */
    private val prefsNames: Set<String> = File(moduleDir, "src/main/java")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { PREFS_NAME_REGEX.findAll(it.readText()) }
        .map { it.groupValues[1] }
        .toSortedSet()

    /** Both on-disk prefs file names produced for each logical store. */
    private val requiredFileNames: Set<String> =
        prefsNames.flatMap { listOf("$it.xml", "${it}_secure.xml") }.toSortedSet()

    @Test
    fun `test discovers the app's prefs stores`() {
        // Sanity check: if the discovery regex ever stops matching, the exclusion assertions below
        // would vacuously pass. The app is known to have at least these stores.
        assertTrue(
            "expected to discover PREFS_NAME constants in main sources, found $prefsNames",
            prefsNames.containsAll(
                listOf(
                    "attendance_events",
                    "clock_notification_settings",
                    "privacy_settings",
                    "proximity_state",
                    "work_locations",
                ),
            ),
        )
    }

    @Test
    fun `backup_rules excludes every sensitive prefs file from auto-backup`() {
        val root = parse(File(xmlDir, "backup_rules.xml"))
        assertEquals("full-backup-content", root.tagName)
        assertExcludesAll(root, "backup_rules.xml/<full-backup-content>")
    }

    @Test
    fun `data_extraction_rules excludes every sensitive prefs file from cloud backup`() {
        val section = section(parse(File(xmlDir, "data_extraction_rules.xml")), "cloud-backup")
        assertExcludesAll(section, "data_extraction_rules.xml/<cloud-backup>")
    }

    @Test
    fun `data_extraction_rules excludes every sensitive prefs file from device transfer`() {
        val section = section(parse(File(xmlDir, "data_extraction_rules.xml")), "device-transfer")
        assertExcludesAll(section, "data_extraction_rules.xml/<device-transfer>")
    }

    @Test
    fun `cloud backup is disabled when the device cannot encrypt backups`() {
        val section = section(parse(File(xmlDir, "data_extraction_rules.xml")), "cloud-backup")
        assertEquals(
            "<cloud-backup> must set disableIfNoEncryptionCapabilities so unencrypted device " +
                "backups are skipped entirely",
            "true",
            section.getAttribute("disableIfNoEncryptionCapabilities"),
        )
    }

    @Test
    fun `rule files contain no include elements that would re-admit excluded data`() {
        for (fileName in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val includes = parse(File(xmlDir, fileName)).getElementsByTagName("include")
            assertEquals(
                "$fileName must not declare <include> elements; they can re-admit excluded prefs",
                0,
                includes.length,
            )
        }
    }

    private fun assertExcludesAll(scope: Element, description: String) {
        val excluded = excludedSharedPrefPaths(scope)
        val missing = requiredFileNames - excluded
        assertTrue(
            "$description is missing sharedpref excludes for: $missing (found: $excluded)",
            missing.isEmpty(),
        )
    }

    private fun excludedSharedPrefPaths(scope: Element): Set<String> {
        val nodes = scope.getElementsByTagName("exclude")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .filter { it.getAttribute("domain") == "sharedpref" }
            .map { it.getAttribute("path") }
            .toSortedSet()
    }

    private fun section(root: Element, tagName: String): Element {
        val nodes = root.getElementsByTagName(tagName)
        assertEquals("expected exactly one <$tagName> section", 1, nodes.length)
        return nodes.item(0) as Element
    }

    private fun parse(file: File): Element {
        assertTrue("missing rules file: ${file.absolutePath}", file.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply {
            // These are trusted in-repo resources, but keep external entity resolution off anyway.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private companion object {
        val PREFS_NAME_REGEX = Regex("""const\s+val\s+PREFS_NAME\s*=\s*"([^"]+)"""")

        /**
         * Unit tests run with the Gradle module directory as the working directory, but resolve by
         * walking up so the test also works from the repo or project root.
         */
        fun findModuleDir(): File {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                val candidate = File(dir, "src/main/res/xml/backup_rules.xml")
                if (candidate.isFile) return dir
                val nested = File(dir, "app/src/main/res/xml/backup_rules.xml")
                if (nested.isFile) return File(dir, "app")
                dir = dir.parentFile
            }
            throw AssertionError("could not locate the app module from ${File("").absolutePath}")
        }
    }
}
