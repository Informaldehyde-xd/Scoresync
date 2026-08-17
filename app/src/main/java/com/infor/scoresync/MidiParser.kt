package com.infor.scoresync

import android.content.Context

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
        readInt16() // format (0, 1, or 2)
        val numTracks = readInt16()
        val division = readInt16() // ticks per quarter note

        var tempoUsPerQuarter = 500000 // default 120 BPM, may be overwritten by a tempo meta event
        val allTickEvents = mutableListOf<Triple<Long, Int, Boolean>>() // (tick, note, isNoteOn), tick is GLOBAL across all tracks (each track restarts at 0 though)

        repeat(numTracks) {
            // Each track chunk
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
                    status = runningStatus
                } else {
                    pos += 1
                    runningStatus = status
                }

                when {
                    status == 0xFF -> {
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
                        val len = readVarLen()
                        pos += len
                    }
                    status in 0x80..0xEF -> {
                        val type = status and 0xF0
                        val note = data[pos].toInt() and 0xFF
                        val velocity = if (pos + 1 < trackEnd) data[pos + 1].toInt() and 0xFF else 0

                        // Determine how many data bytes this status type has (1 or 2)
                        val dataBytes = when (type) {
                            0xC0, 0xD0 -> 1 // program change, channel pressure: 1 data byte
                            else -> 2
                        }
                        pos += dataBytes

                        if (dataBytes == 2) {
                            when (type) {
                                0x90 -> allTickEvents.add(Triple(tick, note, velocity > 0))
                                0x80 -> allTickEvents.add(Triple(tick, note, false))
                                else -> {}
                            }
                        }
                    }
                    else -> {
                        pos += 1
                    }
                }
            }

            pos = trackEnd // ensure we're aligned even if a track parses oddly
        }

        // Convert ticks to milliseconds using tempo (assumes single tempo for the whole piece for now)
        val msPerTick = tempoUsPerQuarter / 1000.0 / division
        return allTickEvents.map { (tickVal, note, isOn) ->
            MidiNoteEvent((tickVal * msPerTick).toLong(), note, isOn)
        }
    }
}
