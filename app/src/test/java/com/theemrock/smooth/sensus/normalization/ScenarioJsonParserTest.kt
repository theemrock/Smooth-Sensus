package com.theemrock.smooth.sensus.normalization

import com.theemrock.smooth.sensus.model.CapabilityScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioJsonParserTest {
    private val parser = ScenarioJsonParser()

    @Test
    fun `A valid app launch JSON succeeds`() {
        val request = "Spotify'ı aç"
        val scenario = success(
            json(request, goal("APP_LAUNCH", "APP", "\"com.spotify.music\"")),
            request
        )

        assertEquals(request, scenario.originalRequest)
        assertEquals("APP_LAUNCH", scenario.goals.single().capabilityName)
        assertEquals(CapabilityScope.APP, scenario.goals.single().scope)
        assertEquals("com.spotify.music", scenario.goals.single().targetPackageName)
    }

    @Test
    fun `B valid multi goal JSON succeeds in order`() {
        val request = "Spotify'ı aç, sonrakine geç"
        val scenario = success(
            json(
                request,
                goal("APP_LAUNCH", "APP", "\"com.spotify.music\""),
                goal("MEDIA_NEXT", "SYSTEM")
            ),
            request
        )

        assertEquals(listOf("APP_LAUNCH", "MEDIA_NEXT"), scenario.goals.map { it.capabilityName })
    }

    @Test
    fun `C valid navigation JSON preserves destination`() {
        val request = "Beni Çorlu'ya götür"
        val scenario = success(
            json(
                request,
                goal("MAP_NAVIGATION", "APP", arguments = "{\"destination\":\"Çorlu\"}")
            ),
            request
        )

        assertEquals(null, scenario.goals.single().targetPackageName)
        assertEquals("Çorlu", scenario.goals.single().arguments["destination"])
    }

    @Test
    fun `D valid system volume JSON succeeds`() {
        val scenario = success(json("Sesi kıs", goal("VOLUME_DOWN", "SYSTEM")), "Sesi kıs")

        assertEquals(CapabilityScope.SYSTEM, scenario.goals.single().scope)
        assertEquals(null, scenario.goals.single().targetPackageName)
    }

    @Test fun `E blank raw output is invalid`() = assertInvalid("   ", "request")

    @Test fun `F malformed JSON is invalid`() = assertInvalid("{not-json", "request")

    @Test fun `G top level array is invalid`() = assertInvalid("[]", "request")

    @Test
    fun `H unknown top level field is invalid`() = assertInvalid(
        """{"originalRequest":"r","goals":[${goal("X", "APP")}],"comment":"done"}""",
        "r"
    )

    @Test
    fun `I missing original request is invalid`() = assertInvalid(
        """{"goals":[${goal("X", "APP") }]}""",
        "r"
    )

    @Test
    fun `J changed original request is invalid`() = assertInvalid(
        json("rewritten", goal("X", "APP")),
        "original"
    )

    @Test
    fun `K missing goals is invalid`() = assertInvalid(
        """{"originalRequest":"r"}""",
        "r"
    )

    @Test
    fun `L empty goals is invalid`() = assertInvalid(
        """{"originalRequest":"r","goals":[]}""",
        "r"
    )

    @Test
    fun `M unknown goal field is invalid`() = assertInvalid(
        json("r", """{"capabilityName":"X","scope":"APP","targetPackageName":null,"arguments":{},"extra":true}"""),
        "r"
    )

    @Test
    fun `N blank capability name is invalid`() = assertInvalid(
        json("r", goal("   ", "APP")),
        "r"
    )

    @Test
    fun `O unknown scope is invalid`() = assertInvalid(json("r", goal("X", "DEVICE")), "r")

    @Test
    fun `P lowercase app scope is invalid`() = assertInvalid(json("r", goal("X", "app")), "r")

    @Test
    fun `Q system target package is invalid`() = assertInvalid(
        json("r", goal("MEDIA_PAUSE", "SYSTEM", "\"com.aimp.player\"")),
        "r"
    )

    @Test
    fun `R app null target package succeeds`() {
        val scenario = success(json("r", goal("MAP_NAVIGATION", "APP")), "r")

        assertEquals(null, scenario.goals.single().targetPackageName)
    }

    @Test fun `S numeric argument is invalid`() = assertInvalidArgument("1")

    @Test fun `T boolean argument is invalid`() = assertInvalidArgument("true")

    @Test fun `U null argument is invalid`() = assertInvalidArgument("null")

    @Test fun `V nested object argument is invalid`() = assertInvalidArgument("{}")

    @Test fun `W array argument is invalid`() = assertInvalidArgument("[]")

    @Test
    fun `X future capability name remains structurally valid`() {
        val scenario = success(json("r", goal("VEHICLE_TELEMETRY_QUERY", "APP")), "r")

        assertEquals("VEHICLE_TELEMETRY_QUERY", scenario.goals.single().capabilityName)
    }

    @Test
    fun `Y markdown fenced JSON is invalid`() = assertInvalid(
        "```json\n${json("r", goal("X", "APP"))}\n```",
        "r"
    )

    @Test
    fun `Z prose around JSON is invalid`() {
        val valid = json("r", goal("X", "APP"))

        assertInvalid("Here is the result:\n$valid", "r")
        assertInvalid("$valid\nDone.", "r")
    }

    @Test
    fun `AA surrounding whitespace with valid JSON succeeds`() {
        val scenario = success(" \n\t${json("r", goal("X", "APP"))}\r\n ", "r")

        assertEquals("X", scenario.goals.single().capabilityName)
    }

    @Test
    fun `AB wrong target package type is invalid`() = assertInvalid(
        json("r", goal("X", "APP", "123")),
        "r"
    )

    @Test
    fun `AC wrong capability name type is invalid`() = assertInvalid(
        json("r", """{"capabilityName":1,"scope":"APP","targetPackageName":null,"arguments":{}}"""),
        "r"
    )

    @Test
    fun `AD wrong arguments type is invalid`() = assertInvalid(
        json("r", """{"capabilityName":"X","scope":"APP","targetPackageName":null,"arguments":[]}"""),
        "r"
    )

    @Test
    fun `AE wrong original request type is invalid`() = assertInvalid(
        """{"originalRequest":1,"goals":[${goal("X", "APP") }]}""",
        "r"
    )

    @Test
    fun `AF JSON goal ordering is preserved exactly`() {
        val scenario = success(
            json(
                "r",
                goal("FIRST", "APP"),
                goal("SECOND", "APP"),
                goal("THIRD", "APP")
            ),
            "r"
        )

        assertEquals(listOf("FIRST", "SECOND", "THIRD"), scenario.goals.map { it.capabilityName })
    }

    @Test
    fun `duplicate object keys are invalid`() = assertInvalid(
        """{"originalRequest":"r","originalRequest":"r","goals":[${goal("X", "APP") }]}""",
        "r"
    )

    private fun assertInvalidArgument(value: String) = assertInvalid(
        json("r", goal("X", "APP", arguments = "{\"key\":$value}")),
        "r"
    )

    private fun assertInvalid(raw: String, expectedRequest: String) {
        assertEquals(
            NormalizationResult.Failure(NormalizationFailureReason.INVALID_OUTPUT),
            parser.parse(raw, expectedRequest)
        )
    }

    private fun success(raw: String, expectedRequest: String) =
        (parser.parse(raw, expectedRequest) as NormalizationResult.Success).scenario

    private fun json(request: String, vararg goals: String) =
        """{"originalRequest":"$request","goals":[${goals.joinToString(",")}]}"""

    private fun goal(
        capabilityName: String,
        scope: String,
        targetPackageName: String = "null",
        arguments: String = "{}"
    ) = """{"capabilityName":"$capabilityName","scope":"$scope","targetPackageName":$targetPackageName,"arguments":$arguments}"""
}
