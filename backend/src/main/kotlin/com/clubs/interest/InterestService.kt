package com.clubs.interest

import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.ClubCategory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class InterestService(private val interestRepository: InterestRepository) {

    private val log = LoggerFactory.getLogger(InterestService::class.java)

    /**
     * Автодополнение по словарю. [clubsOnly] = true — режим поиска каталога: только темы,
     * которыми размечен хотя бы один клуб, в порядке употребления клубами.
     */
    @Transactional(readOnly = true)
    fun suggest(rawQuery: String, limit: Int, clubsOnly: Boolean = false): List<String> {
        val query = InterestNormalizer.normalize(rawQuery) ?: return emptyList()
        if (query.length < InterestNormalizer.MIN_QUERY_LEN) return emptyList()
        return interestRepository.suggest(query, limit.coerceIn(1, MAX_SUGGEST), clubsOnly)
    }

    /** Чипы полки при разметке клуба: топ-темы категории по употреблению клубами. */
    @Transactional(readOnly = true)
    fun suggestByCategory(rawCategory: String, limit: Int): List<String> {
        val category = try {
            ClubCategory.valueOf(rawCategory)
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Invalid category: $rawCategory")
        }
        return interestRepository.suggestByCategory(category, limit.coerceIn(1, MAX_CATEGORY_CHIPS))
    }

    @Transactional(readOnly = true)
    fun getUserInterests(userId: UUID): List<String> =
        interestRepository.findUserInterestNames(userId)

    /**
     * Заменяет интересы пользователя на [rawNames] (нормализованные + без дублей).
     * Сравнивает с текущими связями, чтобы счётчики популярности менялись только
     * для настоящих добавлений/удалений. Выполняется в транзакции вызывающего
     * (обновление профиля).
     */
    @Transactional
    fun replaceUserInterests(userId: UUID, rawNames: List<String>) {
        val normalized = InterestNormalizer.normalizeList(rawNames)
        val nameToId = interestRepository.upsertAll(normalized)
        val newIds = normalized.mapNotNull { nameToId[it] }.toSet()
        val currentIds = interestRepository.findUserInterestIds(userId)

        val toRemove = currentIds - newIds
        val toAdd = newIds - currentIds
        if (toRemove.isNotEmpty()) {
            interestRepository.unlinkUserInterests(userId, toRemove)
            interestRepository.adjustUsage(toRemove, -1)
        }
        if (toAdd.isNotEmpty()) {
            interestRepository.linkUserInterests(userId, toAdd)
            interestRepository.adjustUsage(toAdd, +1)
        }
        log.info("Interests updated: userId={} count={} added={} removed={}",
            userId, newIds.size, toAdd.size, toRemove.size)
    }

    @Transactional(readOnly = true)
    fun getClubInterests(clubId: UUID): List<String> =
        interestRepository.findClubInterestNames(clubId)

    /**
     * Заменяет темы клуба на [rawNames] (нормализованные, без дублей, не более
     * [InterestNormalizer.MAX_CLUB_COUNT]). Лишние отбрасываются молча — как и в профиле:
     * запрос с восемью темами создаёт клуб с семью, а не падает 400 на ровном месте.
     *
     * Счётчик клубов (`club_usage_count`) ведётся отдельно от пользовательского: разметка
     * клубов не должна двигать сортировку автодополнения в профиле, и наоборот.
     * Выполняется в транзакции вызывающего (создание/обновление клуба).
     */
    @Transactional
    fun replaceClubInterests(clubId: UUID, rawNames: List<String>) {
        val normalized = InterestNormalizer.normalizeList(rawNames, InterestNormalizer.MAX_CLUB_COUNT)
        val nameToId = interestRepository.upsertAll(normalized)
        // Порядок сохраняем: первая тема — главная, её увидят на карточке каталога.
        val orderedIds = normalized.mapNotNull { nameToId[it] }
        val newIds = orderedIds.toSet()
        val currentIds = interestRepository.findClubInterestIds(clubId)

        // Счётчики двигаем по разнице наборов, а связи переписываем целиком: перестановка тем
        // местами не меняет состав, но обязана менять позиции.
        val toRemove = currentIds - newIds
        val toAdd = newIds - currentIds
        if (toRemove.isNotEmpty()) interestRepository.adjustClubUsage(toRemove, -1)
        if (toAdd.isNotEmpty()) interestRepository.adjustClubUsage(toAdd, +1)
        interestRepository.replaceClubInterestLinks(clubId, orderedIds)

        log.info("Club interests updated: clubId={} count={} added={} removed={}",
            clubId, newIds.size, toAdd.size, toRemove.size)
    }

    companion object {
        // Верхний предел количества подсказок интересов, отдаваемых за один запрос.
        private const val MAX_SUGGEST = 10
        // Верхний предел чипов полки за один запрос — экран разметки показывает их сеткой,
        // больше трёх десятков превращаются в стену.
        private const val MAX_CATEGORY_CHIPS = 30
    }
}
