package com.clubs.payment

interface TransactionRepository {

    fun existsByTelegramChargeId(chargeId: String): Boolean

    fun save(transaction: Transaction): Transaction
}
