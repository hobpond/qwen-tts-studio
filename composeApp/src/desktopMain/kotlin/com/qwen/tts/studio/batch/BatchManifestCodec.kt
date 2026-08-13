package com.qwen.tts.studio.batch

/** Minimal decoder for the manifest JSON emitted by BatchAudioStore. */
internal object BatchManifestCodec {
    fun decode(json: String): BatchManifest {
        val batchId = JsonReader(json).string("batchId") ?: error("Manifest is missing batchId")
        val expected = JsonReader(json).number("expectedChunkCount")?.toInt()
            ?: error("Manifest is missing expectedChunkCount")
        val metadata = JsonReader(JsonReader(json).objectValue("metadata") ?: "{}").stringMap()
        val chunksJson = JsonReader(json).arrayValue("chunks") ?: error("Manifest is missing chunks")
        val chunks = JsonReader.objects(chunksJson).map { objectJson ->
            val reader = JsonReader(objectJson)
            BatchChunk(
                index = reader.number("index")?.toInt() ?: error("Chunk is missing index"),
                fileName = reader.string("fileName") ?: error("Chunk is missing fileName"),
                text = reader.string("text") ?: "",
                status = reader.string("status")?.let { BatchChunkStatus.valueOf(it) } ?: BatchChunkStatus.PENDING,
                sampleRate = reader.number("sampleRate")?.toInt(),
                frameCount = reader.number("frameCount"),
                sha256 = reader.string("sha256"),
                error = reader.string("error"),
                voiceName = reader.string("voiceName"),
                modelName = reader.string("modelName"),
                voicePrompt = reader.string("voicePrompt"),
                validationPassed = reader.boolean("validationPassed"),
                validationMessage = reader.string("validationMessage"),
                validationSignature = reader.string("validationSignature")
            )
        }
        return BatchManifest(batchId, expected, chunks, metadata)
    }

    private class JsonReader(private val json: String) {
        fun string(key: String): String? = valueStart(key)?.let { parseString(it) }

        fun boolean(key: String): Boolean? = valueStart(key)?.let { start ->
            when {
                json.startsWith("true", start) -> true
                json.startsWith("false", start) -> false
                else -> null
            }
        }

        fun number(key: String): Long? = valueStart(key)?.let { start ->
            val relativeEnd = json.substring(start).indexOfFirst { it == ',' || it == '}' || it.isWhitespace() }
            val end = if (relativeEnd < 0) json.length else start + relativeEnd
            json.substring(start, end).trim().toLongOrNull()
        }

        fun objectValue(key: String): String? = compoundValue(key, '{', '}')
        fun arrayValue(key: String): String? = compoundValue(key, '[', ']')

        fun stringMap(): Map<String, String> {
            val result = linkedMapOf<String, String>()
            var cursor = 0
            while (cursor < json.length) {
                val keyStart = json.indexOf('"', cursor)
                if (keyStart < 0) break
                val keyEnd = closingQuote(keyStart)
                val colon = json.indexOf(':', keyEnd + 1)
                if (colon < 0) break
                val valueStart = skipWhitespace(colon + 1)
                if (valueStart >= json.length || json[valueStart] != '"') break
                result[parseString(keyStart)] = parseString(valueStart)
                cursor = closingQuote(valueStart) + 1
            }
            return result
        }

        private fun valueStart(key: String): Int? {
            val marker = "\"$key\":"
            val markerStart = json.indexOf(marker)
            if (markerStart < 0) return null
            return skipWhitespace(markerStart + marker.length)
        }

        private fun compoundValue(key: String, open: Char, close: Char): String? {
            val start = valueStart(key) ?: return null
            if (start >= json.length || json[start] != open) return null
            var depth = 0
            var quoted = false
            var escaped = false
            for (index in start until json.length) {
                val c = json[index]
                if (quoted) {
                    if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
                } else when (c) {
                    '"' -> quoted = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return json.substring(start + 1, index)
                    }
                }
            }
            error("Unterminated JSON value for $key")
        }

        private fun parseString(start: Int): String {
            require(json[start] == '"') { "Expected JSON string" }
            val out = StringBuilder()
            var index = start + 1
            while (index < json.length) {
                when (val c = json[index++]) {
                    '"' -> return out.toString()
                    '\\' -> when (val escaped = json[index++]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> out.append(json.substring(index, index + 4).toInt(16).toChar()).also { index += 4 }
                        else -> error("Invalid JSON escape")
                    }
                    else -> out.append(c)
                }
            }
            error("Unterminated JSON string")
        }

        private fun closingQuote(start: Int): Int {
            var index = start + 1
            var escaped = false
            while (index < json.length) {
                val c = json[index]
                if (!escaped && c == '"') return index
                escaped = !escaped && c == '\\'
                if (c != '\\') escaped = false
                index++
            }
            error("Unterminated JSON string")
        }

        private fun skipWhitespace(start: Int): Int = (start until json.length).firstOrNull { !json[it].isWhitespace() } ?: json.length

        companion object {
            fun objects(arrayJson: String): List<String> {
                val result = mutableListOf<String>()
                var start: Int? = null
                var depth = 0
                var quoted = false
                var escaped = false
                arrayJson.forEachIndexed { index, c ->
                    if (quoted) {
                        if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
                    } else when (c) {
                        '"' -> quoted = true
                        '{' -> { if (depth++ == 0) start = index }
                        '}' -> if (--depth == 0) result += arrayJson.substring(start!!, index + 1)
                    }
                }
                return result
            }
        }
    }
}
