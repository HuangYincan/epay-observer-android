package dev.epay.observer.protocol

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** The pairing/ACK wire format is a flat object of strings and unsigned integers.
 * Validate before using a platform JSON decoder, which may accept duplicate keys. */
internal class StrictObject private constructor(private val fields: Map<String, Value>) {
    private sealed interface Value {
        data class Text(val value: String) : Value
        data class Number(val value: Long) : Value
    }
    fun requireFields(vararg names: String) { require(fields.keys == names.toSet()) }
    fun text(name: String): String = (fields[name] as? Value.Text)?.value ?: error("invalid_payload")
    fun number(name: String, min: Long = 0, max: Long = 9_007_199_254_740_991): Long =
        ((fields[name] as? Value.Number)?.value ?: error("invalid_payload")).also { require(it in min..max) }

    companion object {
        fun parse(bytes: ByteArray): StrictObject {
            require(bytes.isNotEmpty() && bytes.size <= 4096)
            val input = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            var position = 0
            fun whitespace() { while (position < input.length && input[position] in " \t\r\n") position++ }
            fun take(character: Char) { whitespace(); require(position < input.length && input[position++] == character) }
            fun string(): String {
                take('"')
                val result = StringBuilder()
                var ended = false
                while (position < input.length) {
                    val c = input[position++]
                    if (c == '"') { ended = true; break }
                    require(c.code >= 0x20)
                    if (c != '\\') { result.append(c); continue }
                    require(position < input.length)
                    when (val escaped = input[position++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(position + 4 <= input.length)
                            val hex = input.substring(position, position + 4)
                            require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                            result.append(hex.toInt(16).toChar()); position += 4
                        }
                        else -> error("invalid_payload")
                    }
                }
                require(ended)
                var i = 0
                while (i < result.length) {
                    val c = result[i++]
                    if (c.isHighSurrogate()) require(i < result.length && result[i++].isLowSurrogate())
                    else require(!c.isLowSurrogate())
                }
                return result.toString()
            }
            val fields = linkedMapOf<String, Value>()
            take('{'); whitespace()
            if (position < input.length && input[position] != '}') {
                while (true) {
                    val name = string(); require(name !in fields); take(':'); whitespace()
                    require(position < input.length)
                    val value = if (input[position] == '"') Value.Text(string()) else {
                        val start = position
                        while (position < input.length && input[position] in '0'..'9') position++
                        val number = input.substring(start, position)
                        require(number.isNotEmpty() && (number == "0" || number[0] != '0'))
                        Value.Number(number.toLong())
                    }
                    fields[name] = value; whitespace()
                    require(position < input.length)
                    if (input[position] == '}') break
                    take(',')
                }
            }
            take('}'); whitespace(); require(position == input.length)
            return StrictObject(fields)
        }
    }
}
