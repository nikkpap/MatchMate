package gr.matchbell.app

import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Tiny two-language helper. Every UI text is written as t("Ελληνικά", "English"),
 * so both languages sit side by side and switching is instant (no Activity restart).
 */
class Txt(val en: Boolean) {
    operator fun invoke(el: String, en: String): String = if (this.en) en else el
    val locale: Locale get() = if (en) Locale.ENGLISH else Greek
    fun pattern(p: String): DateTimeFormatter = DateTimeFormatter.ofPattern(p, locale)

    fun sport(key: String, fallback: String? = null): String = when (key) {
        "podosfairo" -> this("Ποδόσφαιρο", "Football")
        "basket" -> this("Μπάσκετ", "Basketball")
        "tennis" -> this("Τένις", "Tennis")
        "volley" -> this("Βόλεϊ", "Volleyball")
        "handball" -> this("Χάντμπολ", "Handball")
        "polo" -> this("Πόλο", "Water polo")
        "mixanokinitos-athlitismos" -> this("Μηχανοκίνητα", "Motorsport")
        "stivos" -> this("Στίβος", "Athletics")
        "polemikes-texnes" -> this("Πολεμικές τέχνες", "Martial arts")
        "podosfairo_salas" -> this("Ποδόσφαιρο σάλας", "Futsal")
        "show" -> this("Εκπομπές", "Shows")
        "other" -> this("Άλλα αθλήματα", "Other sports")
        "f1" -> "Formula 1"
        "motogp" -> "MotoGP"
        else -> fallback ?: key
    }

    fun offset(minutes: Int): String = when {
        minutes == 0 -> this("Στην έναρξη", "At start")
        minutes == 1440 -> this("1 ημέρα", "1 day")
        minutes % 60 == 0 -> { val h = minutes / 60; if (h == 1) this("1 ώρα", "1 hour") else this("$h ώρες", "$h hours") }
        else -> "$minutes′"
    }

    fun inMinutes(m: Long): String = when {
        m <= 0 -> this("Τώρα", "Now")
        m < 60 -> this("Σε $m′", "In $m′")
        else -> { val h = m / 60; val r = m % 60; this("Σε ${h}ω ${r}′", "In ${h}h ${r}′") }
    }

    fun teamLabel(rule: TeamRule): String =
        rule.name + " · " + if (rule.sport == "all") this("Όλα", "All") else sport(rule.sport)
}

val GreekTxt = Txt(false)
fun txtFor(language: String) = Txt(language == "en")
