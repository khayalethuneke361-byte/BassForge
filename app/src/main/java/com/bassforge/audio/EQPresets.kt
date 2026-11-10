package com.bassforge.audio

/** Ten-band EQ presets. Bands: 31 62 125 250 500 1k 2k 4k 8k 16k Hz, gains in dB. */
object EQPresets {

    class Preset(val name: String, val gains: FloatArray)

    val FLAT       = Preset("Flat",       floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
    val BASS       = Preset("Bass",       floatArrayOf(6f, 4f, 3f, 1f, 0f, 0f, -1f, -1f, 0f, 1f))
    val ROCK       = Preset("Rock",       floatArrayOf(4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 4f, 3f))
    val POP        = Preset("Pop",        floatArrayOf(-1f, 0f, 2f, 4f, 3f, 1f, -1f, -1f, 0f, 1f))
    val JAZZ       = Preset("Jazz",       floatArrayOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f))
    val HIPHOP     = Preset("Hip-Hop",    floatArrayOf(6f, 5f, 3f, 0f, -1f, -1f, 0f, 1f, 2f, 2f))
    val ELECTRONIC = Preset("Electronic", floatArrayOf(5f, 4f, 1f, 0f, -1f, 1f, 2f, 3f, 3f, 2f))
    val CLASSICAL  = Preset("Classical",  floatArrayOf(3f, 2f, 1f, 0f, -1f, -1f, 0f, 2f, 3f, 4f))
    val VOCAL      = Preset("Vocal",      floatArrayOf(-2f, -1f, 0f, 2f, 3f, 3f, 2f, 1f, 0f, 0f))
    val MOVIE      = Preset("Movie",      floatArrayOf(2f, 3f, 2f, 1f, 2f, 3f, 2f, 1f, 0f, 0f))

    val ALL = listOf(FLAT, BASS, ROCK, POP, JAZZ, HIPHOP, ELECTRONIC, CLASSICAL, VOCAL, MOVIE)

    fun byName(name: String?): Preset? = ALL.firstOrNull { it.name == name }
}
