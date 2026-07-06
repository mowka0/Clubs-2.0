package com.clubs.membership

import com.clubs.generated.jooq.enums.MembershipStatus
import com.clubs.generated.jooq.tables.references.MEMBERSHIPS
import org.jooq.Condition

/**
 * Канонический предикат «это membership сейчас даёт доступ к КОНТЕНТУ клуба»:
 * статус `active`. Доступ контролирует организатор (de-Stars, Slice 2): организатор
 * либо принимает участника (`active`), либо тот ждёт оплаты взноса вне платформы
 * (`frozen` — первый взнос, `expired` — просрочка продления; доступа нет). У `frozen` /
 * `expired` / `cancelled` (и мёртвого легаси `grace_period`) доступа нет ни у одного.
 *
 * Единый источник истины, чтобы предикат не расходился между местами вызова — именно
 * такое расхождение раньше позволяло участнику голосовать по событию (и получать
 * DM о нём), при этом само событие никогда не появлялось в его ленте активностей.
 * Используется в JooqMembershipRepository (isMember / isActiveMemberInActiveClub /
 * findMemberTelegramIds) и JooqEventRepository (findMyFeed).
 *
 * ПРИМЕЧАНИЕ: этот предикат — только про доступ к КОНТЕНТУ. «Принадлежность клубу»
 * (ростеры участников, список my-clubs, find-for-management, занятость слотов) — это
 * БОЛЕЕ ШИРОКОЕ множество, включающее `frozen` (ждёт первого взноса) и `expired`
 * (просрочил продление) — эти предикаты живут инлайн в JooqMembershipRepository и
 * НЕ являются этим. См. матрицу status×surface в docs/modules/membership-lifecycle.md.
 */
object MembershipAccess {
    fun hasAccess(): Condition = MEMBERSHIPS.STATUS.eq(MembershipStatus.active)
}
