package ch.heuscher.gentleconnect.model

enum class TapZone { PREVIOUS, NONE, NEXT }

object ViewerNavigation {
    fun zoneOf(x: Float, width: Float): TapZone {
        if (width <= 0) return TapZone.NONE
        return when {
            x < width / 3 -> TapZone.PREVIOUS
            x > 2 * width / 3 -> TapZone.NEXT
            else -> TapZone.NONE
        }
    }

    fun target(current: Int, zone: TapZone, count: Int): Int {
        if (count <= 0) return 0
        val target = when (zone) {
            TapZone.PREVIOUS -> current - 1
            TapZone.NEXT -> current + 1
            TapZone.NONE -> current
        }
        return target.coerceIn(0, count - 1)
    }
}
