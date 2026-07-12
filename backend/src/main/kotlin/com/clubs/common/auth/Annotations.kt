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
 * параметром [clubIdParam]. Только для owner-only точек (роли, чат-линк, удаление клуба и т.п.);
 * управляющие точки, доступные со-организатору, используют [RequiresClubManager].
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresOrganizer(val clubIdParam: String = "id")

/**
 * Гарантирует, что авторизованный пользователь — «менеджер» клуба, определённого параметром
 * [clubIdParam]: владелец ИЛИ со-организатор со строго активным членством (fail-close).
 * Единая реализация предиката — [ClubManagerGuard].
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresClubManager(val clubIdParam: String = "id")
