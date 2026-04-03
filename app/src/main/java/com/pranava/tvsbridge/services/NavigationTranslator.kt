package com.pranava.tvsbridge.services

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

object NavigationTranslator {

    /**
     * Map textual Google Maps navigation instructions to TVS Jupiter 125 Pictogram IDs.
     * Mapped via reverse engineering of TVS Connect app's ManeuverInst translations.
     */
    fun getPictogramId(instruction: String): Byte {
        val lowerInst = instruction.lowercase()
        return when {
            // Destinations
            lowerInst.contains("arrive") || lowerInst.contains("destination") -> 10 // END
            lowerInst.contains("start") || lowerInst.contains("head to") -> 58 // START

            // Roundabouts (simplified to generic roundabout icons)
            lowerInst.contains("roundabout") -> 32 // ROUNDABOUT_1

            // U-Turns
            lowerInst.contains("u-turn") || lowerInst.contains("u turn") -> {
                if (lowerInst.contains("right")) 57 // U_TURN_RIGHT
                else 21 // U_TURN_LEFT
            }

            // Sharp turns
            lowerInst.contains("sharp right") || lowerInst.contains("hard right") -> 6 // HEAVY_RIGHT
            lowerInst.contains("sharp left") || lowerInst.contains("hard left") -> 28 // HEAVY_LEFT

            // Slight turns
            lowerInst.contains("slight right") -> 14 // LIGHT_RIGHT
            lowerInst.contains("slight left") -> 47 // LIGHT_LEFT

            // Normal turns
            lowerInst.contains("turn right") || lowerInst.matches(Regex(".*\\bright\\b.*")) && !lowerInst.contains("keep") -> 23 // RIGHT
            lowerInst.contains("turn left") || lowerInst.matches(Regex(".*\\bleft\\b.*")) && !lowerInst.contains("keep") -> 27 // LEFT

            // Keep directions / Highway merges
            lowerInst.contains("keep right") -> 4 // KEEP_RIGHT
            lowerInst.contains("keep left") -> 8 // KEEP_LEFT
            lowerInst.contains("keep middle") -> 7 // KEEP_MIDDLE

            // Default straight
            lowerInst.contains("straight") || lowerInst.contains("head") || lowerInst.contains("continue") -> 22 // GO_STRAIGHT

            // Fallback (Straight)
            else -> 22
        }
    }

    /**
     * Constructs the two 20-byte BLE payloads for TVS Navigation
     * Packet 1: Distance, ETA, Pictogram ID, etc.  (Starts with 'Z', 'P')
     * Packet 2: Primary textual instruction        (Starts with '[', 'J')
     */
    fun createNavigationPayloads(
        distanceToManeuverMeters: Int,
        etaInSeconds: Long,
        totalDistanceLeftMeters: Int,
        primaryInstruction: String,
        isNavigationStopped: Boolean = false
    ): Pair<ByteArray, ByteArray> {

        // 1. ZP Packet - Binary parameters
        val packet1 = ByteArray(20)
        packet1[0] = 90 // 'Z'
        packet1[1] = 80 // 'P' (SampleGattAttributes.DATA_ID_NAVIGATION_CONTROL)

        // Distance to next maneuver (Big-Endian Int16)
        val distBytes = ByteArray(2)
        ByteBuffer.wrap(distBytes).putShort(distanceToManeuverMeters.toShort())
        packet1[2] = distBytes[0]
        packet1[3] = distBytes[1]

        // Remaining time in minutes (Big-Endian Int16)
        val etaMinutes = etaInSeconds / 60
        val etaBytes = ByteArray(2)
        ByteBuffer.wrap(etaBytes).putShort(etaMinutes.toShort())
        packet1[4] = etaBytes[0]
        packet1[5] = etaBytes[1]

        // Total distance left (Big-Endian 24-bit / 3 bytes)
        val totalDistBytes = ByteArray(4)
        ByteBuffer.wrap(totalDistBytes).putInt(totalDistanceLeftMeters)
        packet1[6] = totalDistBytes[1]
        packet1[7] = totalDistBytes[2]
        packet1[8] = totalDistBytes[3]

        // Pictogram ID
        packet1[9] = getPictogramId(primaryInstruction)

        // Number of strings (1 or 2 chunks string)
        packet1[10] = if (primaryInstruction.length > 17) 2.toByte() else 1.toByte()

        // Nav Status flag
        packet1[11] = if (isNavigationStopped) (-1).toByte() else 0.toByte()

        // Padding & Constant Byte offsets
        packet1[12] = 0
        packet1[13] = 0
        packet1[14] = 0
        packet1[15] = 0
        packet1[16] = 0
        packet1[17] = 0
        packet1[18] = 0
        packet1[19] = -1 // 0xFF

        // 2. [J Packet - Text Instruction
        val packet2 = ByteArray(20)
        packet2[0] = 91 // '['
        packet2[1] = 74 // 'J' (SampleGattAttributes.DATA_ID_NAVIGATIONDATA1)

        val textBytes = primaryInstruction.take(17).toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(textBytes, 0, packet2, 2, textBytes.size)
        // remaining bytes are already padded with zero
        packet2[19] = -1 // 0xFF

        return Pair(packet1, packet2)
    }

    /**
     * Create the [R Rider Name packet (91, 82)
     */
    fun createRiderNamePacket(name: String = "Rider"): ByteArray {
        val packet = ByteArray(20)
        packet[0] = 91 // '['
        packet[1] = 82 // 'R'
        val bytes = name.take(17).toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(bytes, 0, packet, 2, bytes.size)
        // Pad the rest with 0 if necessary (already 0)
        packet[19] = (-1).toByte() // 0xFF
        return packet
    }

    /**
     * Create the ZP Mobile Status packet (90, 80)
     */
    fun createMobileStatusPacket(
        batteryLevel: Int = 100,
        signalStrength: Int = 4,
        is24Hour: Boolean = true
    ): ByteArray {
        val packet = ByteArray(20)
        packet[0] = 90 // 'Z'
        packet[1] = 80 // 'P'
        
        // TVS App logic: Battery 0-100 mapped to cluster level
        packet[2] = (batteryLevel / 10).toByte() 
        packet[3] = signalStrength.toByte() // 0-4 bars
        packet[4] = 0 // Call status
        packet[5] = 0 // SMS status
        
        val calendar = java.util.Calendar.getInstance()
        packet[6] = calendar.get(java.util.Calendar.HOUR_OF_DAY).toByte()
        packet[7] = calendar.get(java.util.Calendar.MINUTE).toByte()
        packet[8] = calendar.get(java.util.Calendar.SECOND).toByte()
        
        packet[9] = if (is24Hour) 1.toByte() else (if (calendar.get(java.util.Calendar.AM_PM) == 0) 16.toByte() else 17.toByte())
        packet[10] = 0 // Missed Call count
        packet[11] = 0 // ?
        
        packet[12] = calendar.get(java.util.Calendar.DAY_OF_MONTH).toByte()
        packet[13] = (calendar.get(java.util.Calendar.MONTH) + 1).toByte()
        packet[14] = (calendar.get(java.util.Calendar.YEAR) % 100).toByte()
        
        packet[15] = 0 // Missed SMS count
        packet[16] = 0 // Voice assist
        packet[17] = 0 // Crash alert
        packet[18] = 0 // Padding
        packet[19] = (-1).toByte() // 0xFF
        
        return packet
    }

    /**
     * Create the [L Location packet (91, 76)
     */
    fun createLocationPacket(locationName: String = "Navigating..."): ByteArray {
        val packet = ByteArray(20)
        packet[0] = 91 // '['
        packet[1] = 76 // 'L'
        val bytes = locationName.take(17).toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(bytes, 0, packet, 2, bytes.size)
        // Pad the rest with 0 if necessary (already 0)
        packet[19] = (-1).toByte() // 0xFF
        return packet
    }
}
