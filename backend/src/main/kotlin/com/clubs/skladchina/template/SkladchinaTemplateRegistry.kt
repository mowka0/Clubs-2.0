package com.clubs.skladchina.template

import com.clubs.common.exception.ValidationException
import com.clubs.generated.jooq.enums.SkladchinaTemplate
import org.springframework.stereotype.Component

/**
 * Resolves a [SkladchinaTemplate] to its strategy. Spring injects every strategy bean, so adding a
 * template is just adding a @Component — no edit here.
 */
@Component
class SkladchinaTemplateRegistry(strategies: List<SkladchinaTemplateStrategy>) {
    private val byType: Map<SkladchinaTemplate, SkladchinaTemplateStrategy> = strategies.associateBy { it.type }

    fun forType(type: SkladchinaTemplate): SkladchinaTemplateStrategy =
        byType[type] ?: throw ValidationException("Unsupported skladchina template: $type")
}
