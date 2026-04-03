package com.pranava.tvsbridge.utils

import android.util.Log

object NavigationTranslator {
    private const val TAG = "NavigationTranslator"

    // Placeholder: This will eventually be replaced by the actual hex array from Phase 2
    fun getPayloadForNavigation(title: String?, text: String?): ByteArray {
        Log.d(TAG, "Translating Navigation -> Title: '${title ?: ""}', Text: '${text ?: ""}'")
        
        // This is a dummy translation logic. 
        // Example: If title contains "right", send a specific payload.
        // Once the HCI Snoop log is captured, we will map exact strings to TVS byte arrays.
        val commandPrefix = byteArrayOf(0x01, 0x02) // Dummy standard header

        val safeTitle = title ?: ""
        val directionByte = when {
            safeTitle.contains("right", ignoreCase = true) -> 0x01.toByte()
            safeTitle.contains("left", ignoreCase = true) -> 0x02.toByte()
            safeTitle.contains("straight", ignoreCase = true) -> 0x03.toByte()
            else -> 0x00.toByte()
        }

        // We can just encode the street text for now, or specific distance logic
        // This structure will be entirely rewritten once the true payload shape is discovered.
        return commandPrefix + byteArrayOf(directionByte)
    }
}
