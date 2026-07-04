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
 * параметром [clubIdParam].
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresOrganizer(val clubIdParam: String = "id")
