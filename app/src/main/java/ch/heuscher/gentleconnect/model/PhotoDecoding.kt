package ch.heuscher.gentleconnect.model

/**
 * How far to shrink a photograph while decoding it.
 *
 * Its own object, away from the Compose code that uses it, because it is the part that can be
 * wrong without anything looking wrong. A decode that is too large costs nothing visible until
 * the phone is short of memory, and then the launcher is killed — which she experiences as her
 * phone going blank in her hands, with no connection to the photograph that caused it.
 */
object PhotoDecoding {

    /** Roughly twice the widest a photograph is ever drawn on the phone she has. */
    const val TARGET_PIXELS = 1200

    /**
     * The `inSampleSize` to decode an image of this size with.
     *
     * Powers of two, because `inSampleSize` honours nothing else — asking for 3 quietly gets you
     * 2 — and large enough that neither side exceeds [target] afterwards. Width and height are
     * judged separately: a panorama is enormous in one direction only, and judging by area would
     * decode it whole.
     *
     * Returns 1 for dimensions that make no sense. BitmapFactory reports 0 for a file whose header
     * it could not read, which for a photograph that is still downloading is an ordinary thing to
     * happen, and the decode that follows will fail on its own terms rather than on a division.
     */
    fun sampleSizeFor(width: Int, height: Int, target: Int = TARGET_PIXELS): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1

        var sample = 1
        while (width / sample > target || height / sample > target) {
            sample *= 2
        }
        return sample
    }
}
