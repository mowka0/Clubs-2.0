package com.clubs.skladchina.template

import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.SkladchinaTemplate
import org.springframework.stereotype.Component

/**
 * Разрешает [SkladchinaTemplate] в соответствующую стратегию. Spring инжектирует каждый bean
 * стратегии, так что добавление шаблона — это просто новый @Component, без правок здесь.
 */
@Component
class SkladchinaTemplateRegistry(strategies: List<SkladchinaTemplateStrategy>) {
    private val byType: Map<SkladchinaTemplate, SkladchinaTemplateStrategy> = strategies.associateBy { it.type }

    fun forType(type: SkladchinaTemplate): SkladchinaTemplateStrategy =
        byType[type] ?: throw ValidationException("Unsupported skladchina template: $type")
}
