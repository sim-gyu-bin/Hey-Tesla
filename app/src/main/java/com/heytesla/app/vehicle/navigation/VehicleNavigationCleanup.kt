package com.heytesla.app.vehicle.navigation

/** Native source release must precede FGS release; completion is consumed before invoking its owner. */
internal fun closeVehicleNavigationResources(
    closeSource: () -> Boolean,
    closeForeground: ((Boolean) -> Unit) -> Unit,
    complete: (Boolean) -> Unit,
) {
    val sourceReleased = try { closeSource() } catch (_: Exception) { false }
    if (!sourceReleased) {
        complete(false)
        return
    }
    var foregroundCallReturned = false
    var inlineResult: Boolean? = null
    var delivered = false
    fun deliver(success: Boolean) {
        if (delivered) return
        delivered = true
        complete(success)
    }
    try {
        closeForeground { success ->
            if (!foregroundCallReturned) {
                if (inlineResult == null) inlineResult = success
            } else deliver(success)
        }
    } catch (_: Exception) {
        inlineResult = false
    }
    // Defer synchronous completion beyond the native-close catch; a consumer exception is not native failure.
    foregroundCallReturned = true
    val result = inlineResult
    if (result != null) deliver(result)
}
