package com.clubs.membership

sealed interface JoinResult {
    data class Joined(val membership: MembershipDto) : JoinResult
    data class PendingPayment(val dto: PendingPaymentDto) : JoinResult
}
