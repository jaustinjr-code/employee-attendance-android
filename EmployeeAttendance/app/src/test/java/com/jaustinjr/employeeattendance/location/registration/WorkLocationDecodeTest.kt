package com.jaustinjr.employeeattendance.location.registration

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the per-entry decode of the persisted worksite list (issue #25).
 *
 * The old implementation decoded the whole array in one `decodeFromString<List<WorkLocation>>`, so
 * a single entry failing [WorkLocation]'s `init` validation discarded every worksite. These assert
 * that one bad entry costs you exactly that entry.
 */
class WorkLocationDecodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun location(id: String, name: String = "Site $id") = WorkLocation(
        id = id,
        name = name,
        latitudeDegrees = 37.7749,
        longitudeDegrees = -122.4194,
        radiusMeters = 150f,
    )

    private fun encode(vararg locations: WorkLocation) = json.encodeToString(locations.toList())

    @Test
    fun `a well-formed list round-trips unchanged`() {
        val stored = listOf(location("a"), location("b"), location("c"))

        assertEquals(stored, decodeWorkLocations(json, json.encodeToString(stored)))
    }

    @Test
    fun `an entry with a blank id is dropped and the rest are kept`() {
        // Written by hand: WorkLocation's init would reject a blank id at construction time, so a
        // payload like this can only come from tampering, corruption, or an older/newer schema.
        val raw = """
            [
              {"id":"a","name":"Site a","latitudeDegrees":37.7749,"longitudeDegrees":-122.4194,"radiusMeters":150.0},
              {"id":"","name":"Broken","latitudeDegrees":37.0,"longitudeDegrees":-122.0,"radiusMeters":150.0},
              {"id":"c","name":"Site c","latitudeDegrees":37.7749,"longitudeDegrees":-122.4194,"radiusMeters":150.0}
            ]
        """.trimIndent()

        val decoded = decodeWorkLocations(json, raw)

        assertEquals(listOf("a", "c"), decoded.map { it.id })
    }

    @Test
    fun `an entry with an out-of-range radius is dropped and the rest are kept`() {
        // The schema-drift case from the issue: a future version writes a radius this build's
        // MAX_RADIUS_METERS rejects. Only that worksite should be lost.
        val tooBig = com.jaustinjr.employeeattendance.location.proximity.GeofenceTarget
            .MAX_RADIUS_METERS + 1_000f
        val raw = """
            [
              {"id":"a","name":"Site a","latitudeDegrees":37.7749,"longitudeDegrees":-122.4194,"radiusMeters":150.0},
              {"id":"b","name":"Site b","latitudeDegrees":37.7749,"longitudeDegrees":-122.4194,"radiusMeters":$tooBig}
            ]
        """.trimIndent()

        val decoded = decodeWorkLocations(json, raw)

        assertEquals(listOf("a"), decoded.map { it.id })
    }

    @Test
    fun `an entry with an out-of-range latitude is dropped and the rest are kept`() {
        val raw = """
            [
              {"id":"bad","name":"Bad","latitudeDegrees":999.0,"longitudeDegrees":-122.0,"radiusMeters":150.0},
              {"id":"good","name":"Good","latitudeDegrees":37.7749,"longitudeDegrees":-122.4194,"radiusMeters":150.0}
            ]
        """.trimIndent()

        val decoded = decodeWorkLocations(json, raw)

        assertEquals(listOf("good"), decoded.map { it.id })
    }

    @Test
    fun `an entry missing a required field is dropped and the rest are kept`() {
        val raw = """
            [
              {"name":"No id","latitudeDegrees":37.0,"longitudeDegrees":-122.0,"radiusMeters":150.0},
              {"id":"good","name":"Good","latitudeDegrees":37.7749,"longitudeDegrees":-122.4194,"radiusMeters":150.0}
            ]
        """.trimIndent()

        val decoded = decodeWorkLocations(json, raw)

        assertEquals(listOf("good"), decoded.map { it.id })
    }

    @Test
    fun `an entry of the wrong JSON shape is dropped and the rest are kept`() {
        val raw = """
            [
              "not-an-object",
              {"id":"good","name":"Good","latitudeDegrees":37.7749,"longitudeDegrees":-122.4194,"radiusMeters":150.0}
            ]
        """.trimIndent()

        val decoded = decodeWorkLocations(json, raw)

        assertEquals(listOf("good"), decoded.map { it.id })
    }

    @Test
    fun `multiple bad entries are dropped without taking the good ones with them`() {
        val raw = """
            [
              {"id":"","name":"Blank id","latitudeDegrees":37.0,"longitudeDegrees":-122.0,"radiusMeters":150.0},
              {"id":"a","name":"Site a","latitudeDegrees":37.7749,"longitudeDegrees":-122.4194,"radiusMeters":150.0},
              {"id":"b","name":"","latitudeDegrees":37.7749,"longitudeDegrees":-122.4194,"radiusMeters":150.0},
              {"id":"c","name":"Site c","latitudeDegrees":37.7749,"longitudeDegrees":-122.4194,"radiusMeters":150.0}
            ]
        """.trimIndent()

        val decoded = decodeWorkLocations(json, raw)

        assertEquals(listOf("a", "c"), decoded.map { it.id })
    }

    @Test
    fun `unknown fields on an entry do not drop it`() {
        // Forward compatibility: a newer version adding a field must not cost this build the entry.
        val raw = """
            [
              {"id":"a","name":"Site a","latitudeDegrees":37.7749,"longitudeDegrees":-122.4194,
               "radiusMeters":150.0,"someFutureField":"whatever"}
            ]
        """.trimIndent()

        assertEquals(listOf("a"), decodeWorkLocations(json, raw).map { it.id })
    }

    @Test
    fun `a payload that is not a JSON array yields an empty list`() {
        assertTrue(decodeWorkLocations(json, "{\"id\":\"a\"}").isEmpty())
        assertTrue(decodeWorkLocations(json, "not json at all").isEmpty())
        assertTrue(decodeWorkLocations(json, "").isEmpty())
    }

    @Test
    fun `an empty array yields an empty list`() {
        assertTrue(decodeWorkLocations(json, "[]").isEmpty())
    }

    @Test
    fun `an array where every entry is invalid yields an empty list`() {
        val raw = """[{"id":"","name":"x","latitudeDegrees":0.0,"longitudeDegrees":0.0,"radiusMeters":10.0}]"""

        assertTrue(decodeWorkLocations(json, raw).isEmpty())
    }

    @Test
    fun `optional address survives the per-entry decode`() {
        val stored = listOf(location("a").copy(address = "1 Market St"))

        assertEquals("1 Market St", decodeWorkLocations(json, encode(stored[0])).single().address)
    }
}
