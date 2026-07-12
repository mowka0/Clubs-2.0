package com.clubs.common.auth

/**
 * Гарантирует, что авторизованный пользователь — активный участник клуба, определённого
 * параметром [clubIdParam]. [clubIdParam] должен совпадать с именем параметра метода контроллера,
 * который хранит UUID клуба.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresMembership(val clubIdParam: String = "id")

/**
 * Гарантирует, что авторизованный пользователь — владелец (организатор) клуба, определённого
 * параметром [clubIdParam]. Для чисто-владельческих точек, где логика уже завязана на clubs.owner_id
 * (передача владения и т.п.). Клуб-скоуп на капабилити-модели гейтится [RequiresCapability].
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresOrganizer(val clubIdParam: String = "id")

/**
 * Гарантирует, что у авторизованного пользователя есть [capability] в клубе, определённом параметром
 * [clubIdParam] (капабилити-модель, docs/modules/club-roles.md): владелец проходит любую capability
 * (owner-bypass), прочим право действует только при строго активном членстве (fail-close).
 * Единая реализация — [ClubRoleGuard.requireCapability]; ветка — checkCapability в [AuthorizationAspect].
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresCapability(val capability: ClubCapability, val clubIdParam: String = "id")
