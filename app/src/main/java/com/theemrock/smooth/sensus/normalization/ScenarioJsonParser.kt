package com.theemrock.smooth.sensus.normalization

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.theemrock.smooth.sensus.model.CapabilityScope
import com.theemrock.smooth.sensus.model.Goal
import com.theemrock.smooth.sensus.model.Scenario
import java.io.StringReader

class ScenarioJsonParser {
    fun parse(
        rawOutput: String,
        expectedOriginalRequest: String
    ): NormalizationResult {
        if (rawOutput.isBlank()) return invalidResult()

        return runCatching {
            JsonReader(StringReader(rawOutput)).use { reader ->
                reader.strictness = Strictness.STRICT
                val scenario = readScenario(reader, expectedOriginalRequest)
                if (reader.peek() != JsonToken.END_DOCUMENT) invalid()
                NormalizationResult.Success(scenario)
            }
        }.getOrElse {
            invalidResult()
        }
    }

    private fun readScenario(
        reader: JsonReader,
        expectedOriginalRequest: String
    ): Scenario {
        requireToken(reader, JsonToken.BEGIN_OBJECT)
        reader.beginObject()

        val seenFields = mutableSetOf<String>()
        var originalRequest: String? = null
        var goals: List<Goal>? = null
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (!seenFields.add(name)) invalid()
            when (name) {
                "originalRequest" -> originalRequest = readString(reader)
                "goals" -> goals = readGoals(reader)
                else -> invalid()
            }
        }
        reader.endObject()

        val parsedRequest = originalRequest ?: invalid()
        val parsedGoals = goals ?: invalid()
        if (parsedRequest != expectedOriginalRequest || parsedGoals.isEmpty()) invalid()
        return Scenario(
            originalRequest = expectedOriginalRequest,
            goals = parsedGoals
        )
    }

    private fun readGoals(reader: JsonReader): List<Goal> {
        requireToken(reader, JsonToken.BEGIN_ARRAY)
        reader.beginArray()
        val goals = buildList {
            while (reader.hasNext()) add(readGoal(reader))
        }
        reader.endArray()
        return goals
    }

    private fun readGoal(reader: JsonReader): Goal {
        requireToken(reader, JsonToken.BEGIN_OBJECT)
        reader.beginObject()

        val seenFields = mutableSetOf<String>()
        var capabilityName: String? = null
        var scope: CapabilityScope? = null
        var targetPackageName: String? = null
        var arguments: Map<String, String>? = null
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (!seenFields.add(name)) invalid()
            when (name) {
                "capabilityName" -> capabilityName = readString(reader)
                "scope" -> scope = readScope(reader)
                "targetPackageName" -> targetPackageName = readNullableString(reader)
                "arguments" -> arguments = readArguments(reader)
                else -> invalid()
            }
        }
        reader.endObject()

        val parsedCapabilityName = capabilityName ?: invalid()
        val parsedScope = scope ?: invalid()
        val parsedArguments = arguments ?: invalid()
        if (parsedCapabilityName.isBlank()) invalid()
        if (parsedScope == CapabilityScope.SYSTEM && targetPackageName != null) invalid()

        return Goal(
            capabilityName = parsedCapabilityName,
            scope = parsedScope,
            targetPackageName = targetPackageName,
            arguments = parsedArguments
        )
    }

    private fun readScope(reader: JsonReader): CapabilityScope =
        when (readString(reader)) {
            "APP" -> CapabilityScope.APP
            "SYSTEM" -> CapabilityScope.SYSTEM
            else -> invalid()
        }

    private fun readNullableString(reader: JsonReader): String? =
        when (reader.peek()) {
            JsonToken.STRING -> reader.nextString()
            JsonToken.NULL -> {
                reader.nextNull()
                null
            }
            else -> invalid()
        }

    private fun readArguments(reader: JsonReader): Map<String, String> {
        requireToken(reader, JsonToken.BEGIN_OBJECT)
        reader.beginObject()
        val arguments = linkedMapOf<String, String>()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (key in arguments) invalid()
            arguments[key] = readString(reader)
        }
        reader.endObject()
        return arguments
    }

    private fun readString(reader: JsonReader): String {
        requireToken(reader, JsonToken.STRING)
        return reader.nextString()
    }

    private fun requireToken(reader: JsonReader, expected: JsonToken) {
        if (reader.peek() != expected) invalid()
    }

    private fun invalid(): Nothing = throw InvalidScenarioJsonException()

    private fun invalidResult() =
        NormalizationResult.Failure(NormalizationFailureReason.INVALID_OUTPUT)

    private class InvalidScenarioJsonException : RuntimeException()
}
