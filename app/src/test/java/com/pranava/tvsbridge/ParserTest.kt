package com.pranava.tvsbridge

import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ParserTest {

    @Test
    fun parseLogs() {
        val out = File("C:/Users/prana/Proj/CustomVehicleApp/EvEnTLoGs/2026-04-05/test_output.txt")
        out.writeText("")
        
        val files = listOf(
            "C:/Users/prana/Proj/CustomVehicleApp/EvEnTLoGs/2026-04-05/official_tvs.cfa"
        )
        for (filename in files) {
            val file = File(filename)
            if (!file.exists()) {
                out.appendText("File not found: $filename\n")
                continue
            }
            out.appendText("--- Extracting GATT Write Payloads from $filename ---\n")
            val bytes = file.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val header = ByteArray(16)
            buffer.get(header)
            
            var count = 0
            val types = mutableSetOf<String>()
            while (buffer.remaining() >= 24 && count < 1000) {
                val origLen = buffer.getInt()
                val incLen = buffer.getInt()
                val flags = buffer.getInt()
                val drops = buffer.getInt()
                val tsHi = buffer.getInt()
                val tsLo = buffer.getInt()
                
                if (buffer.remaining() < incLen) break
                val packetData = ByteArray(incLen)
                buffer.get(packetData)
                
                val direction = flags and 0x01
                if (packetData.size < 5) continue
                
                val pktType = packetData[0].toInt() and 0xFF
                if (pktType == 0x02) { // ACL Data
                    val l2capData = packetData.sliceArray(5 until packetData.size)
                    if (l2capData.size < 4) continue
                    
                    val l2capBuffer = ByteBuffer.wrap(l2capData).order(ByteOrder.LITTLE_ENDIAN)
                    val l2capLen = l2capBuffer.getShort()
                    val l2capCid = l2capBuffer.getShort().toInt() and 0xFFFF
                    
                    if (l2capCid == 0x0004) { // ATT channel
                        val attData = l2capData.sliceArray(4 until l2capData.size)
                        if (attData.size < 3) continue
                        val attOpcode = attData[0].toInt() and 0xFF
                        if (attOpcode == 0x12 || attOpcode == 0x52) { // Write Request or Command
                            val attHandle = ByteBuffer.wrap(attData, 1, 2).order(ByteOrder.LITTLE_ENDIAN).getShort().toInt() and 0xFFFF
                            val payload = attData.sliceArray(3 until attData.size)
                            
                            val hexPayload = payload.joinToString(" ") { "%02x".format(it) }
                            if (payload.isNotEmpty()) {
                                types.add("%02x %02x".format(payload[0], if (payload.size > 1) payload[1] else 0))
                                out.appendText("Hex: $hexPayload\n")
                                count++
                            }
                        }
                    }
                }
            }
            out.appendText("Packet Headers found: $types\n")
        }
    }
}
