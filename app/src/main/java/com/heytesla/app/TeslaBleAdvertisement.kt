package com.heytesla.app

import java.security.MessageDigest
import java.util.Locale

/** 공식 VehicleLocalName. VIN과 산출명은 차량 식별자이며 호출자 RAM 밖으로 내보내지 않는다. */
object TeslaBleAdvertisement {
    private val vinPattern = Regex("[A-HJ-NPR-Z0-9]{17}")
    private const val HEX = "0123456789abcdef"

    fun localName(input: String): String? {
        val normalized = input.trim().uppercase(Locale.ROOT)
        if (!vinPattern.matches(normalized)) return null
        val material = normalized.toByteArray(Charsets.US_ASCII)
        var digest: ByteArray? = null
        val name = CharArray(18)
        try {
            val hash = MessageDigest.getInstance("SHA-1").digest(material)
            digest = hash
            name[0] = 'S'
            for (index in 0 until 8) {
                val byte = hash[index].toInt() and 0xff
                name[1 + index * 2] = HEX[byte ushr 4]
                name[2 + index * 2] = HEX[byte and 0xf]
            }
            name[17] = 'C'
            return String(name)
        } finally {
            material.fill(0)
            digest?.fill(0)
            name.fill('\u0000')
        }
    }
}
