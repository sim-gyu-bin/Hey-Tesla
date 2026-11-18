package com.heytesla.app.vehicle.detection

/** 필터와 callback이 공유하는 RAM 타깃. 식별자를 data class/toString으로 노출하지 않는다. */
internal class BleScanTarget private constructor(
    identifier: String,
) {
    @Volatile private var identifier: String? = identifier

    fun matches(advertisedName: String?): Boolean {
        val expected = identifier ?: return false
        return advertisedName == expected
    }

    /** 순서가 뒤섞인 batch에서도 타깃과 일치하는 최신 표본 하나만 반환한다. */
    inline fun <T : Any> latestMatching(
        results: Iterable<T>,
        advertisedName: (T) -> String?,
        timestamp: (T) -> Long,
    ): T? {
        var latest: T? = null
        var latestAt = Long.MIN_VALUE
        for (result in results) {
            if (!matches(advertisedName(result))) continue
            val at = timestamp(result)
            if (latest == null || at > latestAt) {
                latest = result
                latestAt = at
            }
        }
        return latest
    }

    /** Android 이름 필터를 생성할 때만 식별자를 빌더에 건넨다. */
    fun <T> configure(name: (String) -> T): T? {
        val expected = identifier ?: return null
        return name(expected)
    }

    fun clear() { identifier = null }
    override fun toString(): String = "BleScanTarget(redacted)"

    companion object {
        fun vehicleName(name: String) = BleScanTarget(name)
    }
}

/** 요청의 소유권과 association을 확인한 한 번의 RAM handoff만 허용한다. */
internal class BlePendingAdvertisedName {
    private var request: String? = null
    private var name: String? = null

    fun arm(token: String, advertisedName: String) {
        check(request == null && name == null)
        request = token
        name = advertisedName
    }

    fun consume(token: String, ownsAssociation: Boolean): String? {
        if (request != token || !ownsAssociation) return null
        val selected = name
        clear(token)
        return selected
    }

    fun clear(token: String? = null) {
        if (token != null && request != token) return
        request = null
        name = null
    }

    override fun toString(): String = "BlePendingAdvertisedName(redacted)"
}
