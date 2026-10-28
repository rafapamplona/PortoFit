package com.example.portofit.ui.components

import com.example.portofit.data.model.GeoPoint
import java.net.URLEncoder
import java.util.Locale

/**
 * Endereços para abrir noutras aplicações (navegador, marcador, mapas). Só devolvem valor quando o dado é válido: o
 * ecrã mostra a ação apenas nesse caso. Funções puras (sem Android), para serem testadas na JVM.
 */
object ExternalLinks {

    private val WEBSITE = Regex("^https?://[^\\s/?#.]+(\\.[^\\s/?#.]+)+([/?#]\\S*)?$", RegexOption.IGNORE_CASE)
    private val PHONE_SEPARATORS = Regex("[\\s.()-]")
    private val PHONE = Regex("^\\+?\\d{6,15}$")

    /** Site http(s) com domínio; caso contrário null. */
    fun website(raw: String?): String? = raw?.trim()?.takeIf { WEBSITE.matches(it) }

    /**
     * Número para o marcador (`tel:`), sem separadores; "00" inicial passa a "+". Só abre o marcador com o número
     * preenchido: nunca liga diretamente.
     */
    fun dialNumber(raw: String?): String? {
        val compact = raw?.replace(PHONE_SEPARATORS, "")?.let { if (it.startsWith("00")) "+" + it.drop(2) else it } ?: return null
        return compact.takeIf { PHONE.matches(it) }
    }

    fun telUri(number: String): String = "tel:$number"

    /** URI `geo:` com um marcador nas coordenadas e o nome como etiqueta; null se as coordenadas forem inválidas. */
    fun geoUri(point: GeoPoint?, label: String): String? {
        if (point == null || point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0) return null
        val lat = String.format(Locale.ROOT, "%.6f", point.latitude)
        val lng = String.format(Locale.ROOT, "%.6f", point.longitude)
        val name = URLEncoder.encode(label.trim(), "UTF-8").replace("+", "%20")
        return "geo:$lat,$lng?q=$lat,$lng($name)"
    }
}
