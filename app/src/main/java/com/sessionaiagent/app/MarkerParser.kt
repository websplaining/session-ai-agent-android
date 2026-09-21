package com.sessionaiagent.app

import org.json.JSONArray

sealed class SaaEvent {
    data class Step(val name: String) : SaaEvent()
    data class Info(val key: String, val value: String) : SaaEvent()
    data class Progress(val percent: Int, val label: String) : SaaEvent()
    data class ModelList(val models: List<String>) : SaaEvent()
    data class BotSessionId(val id: String) : SaaEvent()
    data class Error(val message: String) : SaaEvent()
    data object Ok : SaaEvent()
    data class Line(val text: String) : SaaEvent()
}

object MarkerParser {
    fun parse(raw: String): SaaEvent {
        val line = raw.trim()
        return when {
            line.startsWith("SAA:STEP ") -> SaaEvent.Step(line.removePrefix("SAA:STEP ").trim())
            line.startsWith("SAA:INFO ") -> {
                val rest = line.removePrefix("SAA:INFO ").trim()
                SaaEvent.Info(rest.substringBefore(' '), rest.substringAfter(' ', ""))
            }
            line.startsWith("SAA:PROGRESS ") -> {
                val rest = line.removePrefix("SAA:PROGRESS ").trim()
                val pct = rest.substringBefore(' ').toIntOrNull() ?: -1
                SaaEvent.Progress(pct, rest.substringAfter(' ', ""))
            }
            line.startsWith("SAA:MODEL_LIST ") -> {
                val json = line.removePrefix("SAA:MODEL_LIST ").trim()
                val models = try {
                    val arr = JSONArray(json)
                    (0 until arr.length()).map { arr.getString(it) }
                } catch (_: Exception) {
                    emptyList()
                }
                SaaEvent.ModelList(models)
            }
            line.startsWith("SAA:BOT_SESSION_ID ") ->
                SaaEvent.BotSessionId(line.removePrefix("SAA:BOT_SESSION_ID ").trim())
            line == "SAA:OK" -> SaaEvent.Ok
            line.startsWith("SAA:ERROR ") -> SaaEvent.Error(line.removePrefix("SAA:ERROR ").trim())
            else -> SaaEvent.Line(line)
        }
    }
}
