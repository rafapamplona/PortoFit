package com.example.portofit.data.repository

/** Resultado de uma operação de dados: sucesso com valor, ou falha com um erro já classificado. */
sealed interface DataResult<out T> {
    data class Success<T>(val value: T) : DataResult<T>
    data class Failure(val error: DataError) : DataResult<Nothing>
}

/** Tipos de erro que a interface sabe explicar ao utilizador. */
enum class DataError {
    /** Sem ligação ao servidor (rede, servidor desligado, timeout). */
    NETWORK,

    /** O servidor respondeu com erro 5xx (ex.: base de dados indisponível). */
    SERVER,

    /** O recurso pedido não existe (404). */
    NOT_FOUND,

    /** O servidor recusou o pedido (400). */
    INVALID_REQUEST,

    /** A app não tem o endereço do serviço configurado. */
    NOT_CONFIGURED,

    /** Resposta inesperada (ex.: JSON inválido). */
    UNEXPECTED,
}
