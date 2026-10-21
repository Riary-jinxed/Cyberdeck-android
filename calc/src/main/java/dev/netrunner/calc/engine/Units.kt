package dev.netrunner.calc.engine

/** Unit conversions. Each unit is a linear map to its category's base unit: base = value × factor + offset. */
object Units {
    data class Measure(val symbol: String, val name: String, val factor: Double, val offset: Double = 0.0) {
        fun toBase(v: Double) = v * factor + offset
        fun fromBase(v: Double) = (v - offset) / factor
    }

    data class Category(val name: String, val units: List<Measure>)

    fun convert(value: Double, from: Measure, to: Measure): Double = to.fromBase(from.toBase(value))

    /** Currencies, with [rates] given as units of each currency for one unit of the base currency. */
    fun convertCurrency(value: Double, from: String, to: String, rates: Map<String, Double>): Double? {
        val a = rates[from] ?: return null
        val b = rates[to] ?: return null
        return value / a * b
    }

    private const val INCH = 0.0254
    private const val POUND = 0.45359237
    private const val GALLON_US = 3.785411784e-3

    val categories = listOf(
        Category(
            "LONGUEUR",
            listOf(
                Measure("m", "mètre", 1.0),
                Measure("km", "kilomètre", 1000.0),
                Measure("cm", "centimètre", 0.01),
                Measure("mm", "millimètre", 0.001),
                Measure("µm", "micromètre", 1e-6),
                Measure("mi", "mile", 1609.344),
                Measure("yd", "yard", 0.9144),
                Measure("ft", "pied", 12 * INCH),
                Measure("in", "pouce", INCH),
                Measure("nmi", "mille marin", 1852.0),
                Measure("al", "année-lumière", 9.4607304725808e15),
            ),
        ),
        Category(
            "MASSE",
            listOf(
                Measure("kg", "kilogramme", 1.0),
                Measure("g", "gramme", 0.001),
                Measure("mg", "milligramme", 1e-6),
                Measure("t", "tonne", 1000.0),
                Measure("lb", "livre", POUND),
                Measure("oz", "once", POUND / 16),
                Measure("st", "stone", POUND * 14),
                Measure("ct", "carat", 0.0002),
            ),
        ),
        Category(
            "TEMPÉRATURE",
            listOf(
                Measure("°C", "Celsius", 1.0),
                Measure("°F", "Fahrenheit", 5.0 / 9, -32 * 5.0 / 9),
                Measure("K", "Kelvin", 1.0, -273.15),
            ),
        ),
        Category(
            "VOLUME",
            listOf(
                Measure("L", "litre", 1e-3),
                Measure("mL", "millilitre", 1e-6),
                Measure("cL", "centilitre", 1e-5),
                Measure("m³", "mètre cube", 1.0),
                Measure("gal", "gallon US", GALLON_US),
                Measure("gal UK", "gallon impérial", 4.54609e-3),
                Measure("qt", "quart US", GALLON_US / 4),
                Measure("pt", "pinte US", GALLON_US / 8),
                Measure("tasse", "tasse US", GALLON_US / 16),
                Measure("fl oz", "once liquide US", GALLON_US / 128),
            ),
        ),
        Category(
            "SURFACE",
            listOf(
                Measure("m²", "mètre carré", 1.0),
                Measure("km²", "kilomètre carré", 1e6),
                Measure("cm²", "centimètre carré", 1e-4),
                Measure("ha", "hectare", 1e4),
                Measure("a", "are", 100.0),
                Measure("ft²", "pied carré", 144 * INCH * INCH),
                Measure("in²", "pouce carré", INCH * INCH),
                Measure("acre", "acre", 4046.8564224),
                Measure("mi²", "mile carré", 1609.344 * 1609.344),
            ),
        ),
        Category(
            "VITESSE",
            listOf(
                Measure("km/h", "kilomètre/heure", 1 / 3.6),
                Measure("m/s", "mètre/seconde", 1.0),
                Measure("mph", "mile/heure", 1609.344 / 3600),
                Measure("kn", "nœud", 1852.0 / 3600),
                Measure("ft/s", "pied/seconde", 12 * INCH),
                Measure("Mach", "Mach (air, 15 °C)", 340.29),
                Measure("c", "vitesse de la lumière", 299_792_458.0),
            ),
        ),
        Category(
            "DONNÉES",
            listOf(
                Measure("o", "octet", 1.0),
                Measure("bit", "bit", 0.125),
                Measure("Ko", "kilooctet", 1e3),
                Measure("Mo", "mégaoctet", 1e6),
                Measure("Go", "gigaoctet", 1e9),
                Measure("To", "téraoctet", 1e12),
                Measure("Kio", "kibioctet", 1024.0),
                Measure("Mio", "mébioctet", 1024.0 * 1024),
                Measure("Gio", "gibioctet", 1024.0 * 1024 * 1024),
                Measure("Tio", "tébioctet", 1024.0 * 1024 * 1024 * 1024),
                Measure("Mbit", "mégabit", 1e6 / 8),
                Measure("Gbit", "gigabit", 1e9 / 8),
            ),
        ),
        Category(
            "TEMPS",
            listOf(
                Measure("s", "seconde", 1.0),
                Measure("ms", "milliseconde", 1e-3),
                Measure("min", "minute", 60.0),
                Measure("h", "heure", 3600.0),
                Measure("j", "jour", 86400.0),
                Measure("sem", "semaine", 7 * 86400.0),
                Measure("mois", "mois (moyen)", 30.436875 * 86400),
                Measure("an", "année (365,25 j)", 365.25 * 86400),
            ),
        ),
        Category(
            "ÉNERGIE",
            listOf(
                Measure("J", "joule", 1.0),
                Measure("kJ", "kilojoule", 1e3),
                Measure("cal", "calorie", 4.184),
                Measure("kcal", "kilocalorie", 4184.0),
                Measure("Wh", "wattheure", 3600.0),
                Measure("kWh", "kilowattheure", 3.6e6),
                Measure("eV", "électronvolt", 1.602176634e-19),
                Measure("BTU", "BTU", 1055.05585262),
            ),
        ),
        Category(
            "PRESSION",
            listOf(
                Measure("bar", "bar", 1e5),
                Measure("Pa", "pascal", 1.0),
                Measure("hPa", "hectopascal", 100.0),
                Measure("kPa", "kilopascal", 1e3),
                Measure("atm", "atmosphère", 101_325.0),
                Measure("psi", "livre/pouce²", 6894.757293168),
                Measure("mmHg", "millimètre de mercure", 133.322387415),
            ),
        ),
        Category(
            "ANGLE",
            listOf(
                Measure("°", "degré", 1.0),
                Measure("rad", "radian", 180 / Math.PI),
                Measure("grad", "grade", 0.9),
                Measure("tr", "tour", 360.0),
                Measure("′", "minute d'arc", 1.0 / 60),
                Measure("″", "seconde d'arc", 1.0 / 3600),
            ),
        ),
    )
}
