package com.clubs.award

import java.util.UUID

/**
 * Набор наград участника изменился — слушают титулы наград чат-интеграции (слайс 4,
 * AFTER_COMMIT): титул в чате = последняя выданная награда, пересчитывается на каждое
 * изменение. Публикуются внутри транзакции AwardService.
 */
data class AwardGrantedEvent(
    val clubId: UUID,
    val userId: UUID
)

data class AwardRevokedEvent(
    val clubId: UUID,
    val userId: UUID
)
