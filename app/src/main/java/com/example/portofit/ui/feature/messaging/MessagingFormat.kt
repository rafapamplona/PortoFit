package com.example.portofit.ui.feature.messaging

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

/** Hora de uma mensagem no fuso do aparelho: só a hora se for de hoje, senão data e hora (ex.: 21/10/2026 09:05). */
fun formatMessageTime(instant: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val date = instant.atZone(zone)
    return if (date.toLocalDate() == now.atZone(zone).toLocalDate()) TIME.format(date) else DATE_TIME.format(date)
}

/** Contagem de por ler como na caixa: até 99, depois "99+" (o servidor conta até 100). */
fun unreadLabel(count: Int, many: String): String = if (count > 99) many else count.toString()
