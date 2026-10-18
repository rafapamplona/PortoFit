package com.example.portofit.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException

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

/** Executa uma chamada Retrofit e classifica as falhas (partilhado pelos repositórios públicos). */
suspend fun <T> apiCall(block: suspend () -> T): DataResult<T> =
    try {
        DataResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        DataResult.Failure(
            when (e.code()) {
                404 -> DataError.NOT_FOUND
                400 -> DataError.INVALID_REQUEST
                in 500..599 -> DataError.SERVER
                else -> DataError.UNEXPECTED
            },
        )
    } catch (e: IOException) {
        DataResult.Failure(DataError.NETWORK)
    } catch (e: SerializationException) {
        DataResult.Failure(DataError.UNEXPECTED)
    } catch (e: IllegalArgumentException) {
        // Também lançada pelo kotlinx.serialization quando o JSON não corresponde ao formato.
        DataResult.Failure(DataError.UNEXPECTED)
    }
