package com.example.portofit.ui.components

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.example.portofit.R
import com.example.portofit.data.model.ActivityLevel
import com.example.portofit.data.model.Rating
import com.example.portofit.data.repository.DataError
import java.util.Locale

private val PORTUGUESE = Locale.forLanguageTag("pt-PT")

/** Média com uma casa decimal e vírgula, como em português (ex.: 4,3). */
fun formatRatingAverage(average: Double): String = String.format(PORTUGUESE, "%.1f", average)

/** Texto de uma avaliação: média e pessoas, "Sem avaliações" ou "temporariamente indisponíveis". */
@Composable
fun ratingText(rating: Rating): String = when (rating) {
    is Rating.Available -> pluralStringResource(
        R.plurals.rating_available,
        rating.peopleCount,
        formatRatingAverage(rating.average),
        rating.peopleCount,
    )
    Rating.NoRatings -> stringResource(R.string.rating_none)
    Rating.Unavailable -> stringResource(R.string.rating_unavailable)
}

/**
 * Avaliação com estilo próprio para cada caso: média em destaque; "Sem avaliações" discreto; indisponível em itálico
 * (é uma falha temporária, não uma ausência de avaliações, e nunca aparece como zero).
 */
@Composable
fun RatingText(rating: Rating, style: TextStyle, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    when (rating) {
        is Rating.Available -> Text(ratingText(rating), style = style, fontWeight = FontWeight.Medium, color = colors.onSurface, modifier = modifier)
        Rating.NoRatings -> Text(ratingText(rating), style = style, color = colors.onSurfaceVariant, modifier = modifier)
        Rating.Unavailable -> Text(ratingText(rating), style = style, fontStyle = FontStyle.Italic, color = colors.onSurfaceVariant, modifier = modifier)
    }
}

@StringRes
fun levelLabelRes(level: ActivityLevel): Int = when (level) {
    ActivityLevel.BEGINNER -> R.string.level_beginner
    ActivityLevel.INTERMEDIATE -> R.string.level_intermediate
    ActivityLevel.ADVANCED -> R.string.level_advanced
    ActivityLevel.ALL_LEVELS -> R.string.level_all
    ActivityLevel.UNKNOWN -> R.string.level_unknown
}

@StringRes
fun errorMessageRes(error: DataError): Int = when (error) {
    DataError.NETWORK -> R.string.error_network
    DataError.SERVER -> R.string.error_server
    DataError.NOT_FOUND -> R.string.error_not_found
    DataError.INVALID_REQUEST -> R.string.error_invalid_request
    DataError.NOT_CONFIGURED -> R.string.error_not_configured
    DataError.UNEXPECTED -> R.string.error_unexpected
}
