package dev.netrunner.aska

/** Her sprites (src/main/resources/sprites). */
enum class Pose(val sprite: String) {
    Waiting("chibi_waiting"),
    Happy("chibi_happy"),
    Shy("chibi_shy"),
    Mischievous("chibi_mischievous"),
    Gourmand("chibi_gourmand"),
    Talking("chibi_talking"),
    Thinking("chibi_thinking"),
    Sleep("chibi_sleep"),
    Wake("chibi_wake"),
    LowBattery("chibi_lowbatt"),
    Charging("chibi_charging"),
    Sun("weather_sun"),
    Cloudy("weather_cloudy"),
    Rain("weather_rain"),
    Storm("weather_storm"),
    Snow("weather_snow"),
    Fog("weather_fog"),
    Wind("weather_wind"),
    Heat("weather_heat"),
    Cold("weather_cold"),
    Night("weather_night");

    companion object {
        /**
         * The pose of the moment, most pressing first: what she is doing in the conversation,
         * a passing reaction, sleep, the battery, then the night and the weather.
         */
        fun pick(
            thinking: Boolean,
            talking: Boolean,
            reaction: Pose?,
            asleep: Boolean,
            battery: Battery?,
            hour: Int,
            weather: Pose?,
        ): Pose = when {
            thinking -> Thinking
            talking -> Talking
            reaction != null -> reaction
            asleep -> Sleep
            battery?.low == true -> LowBattery
            hour >= 23 || hour < 6 -> Night
            else -> weather ?: Waiting
        }
    }
}

/**
 * Her small moves at her desk (Bongo Cat drawings, bongo_<name>.png): passing ones (a yawn, a
 * stretch, a worried look, a sign to show a message) and lasting ones (focused, gamepad in hand).
 * A drawing not shipped yet is simply skipped.
 */
enum class Gesture(val frame: String, val lasting: Boolean = false) {
    Yawn("bongo_yawn"),
    Stretch("bongo_stretch"),
    Worried("bongo_worried"),
    Happy("bongo_happy"),
    Sign("bongo_sign"),
    Focus("bongo_focus", lasting = true),
    Gamepad("bongo_gamepad", lasting = true),
}
