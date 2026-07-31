package com.clubs.user

import com.clubs.generated.jooq.tables.references.USER_ONBOARDING_TOURS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.util.UUID

/** Пройденные туры онбординга (V72): строка есть — тур пройден, строки нет — не пройден. */
@Repository
class OnboardingTourRepository(private val dsl: DSLContext) {

    /**
     * Туры, пройденные пользователем. Ключи, которых больше нет в [OnboardingTour] (остатки
     * удалённого экрана), молча отбрасываются: старая строка в базе не должна ронять чтение
     * профиля — она всего лишь описывает экран, которого уже нет.
     */
    fun findCompleted(userId: UUID): Set<OnboardingTour> =
        dsl.select(USER_ONBOARDING_TOURS.TOUR_KEY)
            .from(USER_ONBOARDING_TOURS)
            .where(USER_ONBOARDING_TOURS.USER_ID.eq(userId))
            .fetch(USER_ONBOARDING_TOURS.TOUR_KEY)
            .mapNotNull { key -> key?.let(OnboardingTour::parse) }
            .toSet()

    /**
     * Помечает тур пройденным. Идемпотентно: повторный вызов (двойной тап, второе устройство,
     * ретрай сети) — не ошибка и не гонка read-then-write, конфликт по первичному ключу
     * гасится внутри самого INSERT.
     *
     * @return true — отметили сейчас; false — тур уже был пройден раньше.
     */
    fun markCompleted(userId: UUID, tour: OnboardingTour): Boolean =
        dsl.insertInto(USER_ONBOARDING_TOURS)
            .set(USER_ONBOARDING_TOURS.USER_ID, userId)
            .set(USER_ONBOARDING_TOURS.TOUR_KEY, tour.name)
            .onConflictDoNothing()
            .execute() > 0
}
