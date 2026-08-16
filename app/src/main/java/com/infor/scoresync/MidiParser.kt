package com.infor.scoresync

import android.content.Context
import java.io.DataInputStream
import java.io.InputStream

data class MidiNoteEvent(val timeMs: Long, val note: Int, val isNoteOn: Boolean)

object MidiParser {

    fun parse(context: Context, assetPath: String): List<MidiNoteEvent> {
        val input = context.assets.open(assetPath)
        val bytes = input.readBytes()
        input.close()
        return parseBytes(bytes)
    }

    private fun parseBytes(data: ByteArray): List<MidiNoteEvent> {
        var pos = 0

        fun readInt32(): Int {
            val v = ((data[pos].toInt() and 0xFF) shl 24) or
                    ((data[pos + 1].toInt() and 0xFF) shl 16) or
                    ((data[pos + 2].toInt() and 0xFF) shl 8) or
                    (data[pos + 3].toInt() and 0xFF)
            pos += 4
            return v
        }

        fun readInt16(): Int {
            val v = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2
            return v
        }

        // Header chunk
        pos += 4 // "MThd"
        readInt32() // header length, always 6
        readInt16() // format
        readInt16() // number of tracks
        val division = readInt16() // ticks per quarter note (assumes not SMPTE format)

        var tempoUsPerQuarter = 500000 // default 120 BPM
        val events = mutableListOf<Triple<Long, Int, Boolean>>() // (tick, note, isNoteOn)

        // Track chunk
        pos += 4 // "MTrk"
        val trackLength = readInt32()
        val trackEnd = pos + trackLength

        var tick = 0L
        var runningStatus = 0

        fun readVarLen(): Int {
            var result = 0
            while (true) {
                val b = data[pos].toInt() and 0xFF
                pos += 1
                result = (result shl 7) or (b and 0x7F)
                if (b and 0x80 == 0) break
            }
            return result
        }

        while (pos < trackEnd) {
            val delta = readVarLen()
            tick += delta

            var status = data[pos].toInt() and 0xFF
            if (status < 0x80) {
                // running status: reuse previous status byte, don't advance pos
                status = runningStatus
            } else {
                pos += 1
                runningStatus = status
            }

            when {
                status == 0xFF -> {
                    // Meta event
                    val metaType = data[pos].toInt() and 0xFF
                    pos += 1
                    val len = readVarLen()
                    if (metaType == 0x51 && len == 3) {
                        tempoUsPerQuarter = ((data[pos].toInt() and 0xFF) shl 16) or
                                ((data[pos + 1].toInt() and 0xFF) shl 8) or
                                (data[pos + 2].toInt() and 0xFF)
                    }
                    pos += len
                }
                status == 0xF0 || status == 0xF7 -> {
                    // Sysex event
                    val len = readVarLen()
                    pos += len
                }
                status in 0x80..0xEF -> {
                    val type = status and 0xF0
                    val note = data[pos].toInt() and 0xFF
                    val velocity = if (pos + 1 < trackEnd) data[pos + 1].toInt() and 0xFF else 0
                    pos += 2

                    when (type) {
                        0x90 -> events.add(Triple(tick, note, velocity > 0)) // note on (vel 0 = note off)
                        0x80 -> events.add(Triple(tick, note, false)) // note off
                        else -> {} // ignore other channel messages (control change, etc.)
                    }
                }
                else -> {
                    pos += 1 // unknown, skip a byte defensively
                }
            }
        }

        // Convert ticks to milliseconds using tempo
        val msPerTick = tempoUsPerQuarter / 1000.0 / division
        return events.map { (tickVal, note, isOn) ->
            MidiNoteEvent((tickVal * msPerTick).toLong(), note, isOn)
        }
    }
}
