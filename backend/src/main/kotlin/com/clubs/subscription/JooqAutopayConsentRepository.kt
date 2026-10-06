package com.clubs.subscription

import com.clubs.bot.OFFER_UPDATED
import com.clubs.generated.jooq.tables.references.AUTOPAY_CONSENT
import org.jooq.DSLContext
import org.springframework.stereotype.Repository

@Repository
class JooqAutopayConsentRepository(private val dsl: DSLContext) : AutopayConsentRepository {

    override fun record(consent: AutopayConsent) {
        dsl.insertInto(AUTOPAY_CONSENT)
            .set(AUTOPAY_CONSENT.CLUB_ID, consent.clubId)
            .set(AUTOPAY_CONSENT.USER_ID, consent.userId)
            .set(AUTOPAY_CONSENT.PAYMENT_ID, consent.paymentId)
            .set(AUTOPAY_CONSENT.SUBSCRIPTION_ID, consent.subscriptionId)
            .set(AUTOPAY_CONSENT.GRANTED, consent.granted)
            .set(AUTOPAY_CONSENT.SOURCE, consent.source.name)
            .set(AUTOPAY_CONSENT.WORDING, consent.source.wording)
            // Редакция оферты — из той же генерации (scripts/gen-oferta.py), что текст в шите и в боте:
            // человек соглашался именно с ней.
            .set(AUTOPAY_CONSENT.OFFER_EDITION, OFFER_UPDATED)
            .execute()
    }
}
