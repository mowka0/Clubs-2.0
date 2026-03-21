package com.clubs.common.auth

/**
 * Ensures the authenticated user is an active member of the club identified by [clubIdParam].
 * [clubIdParam] must match the controller method parameter name that holds the club UUID.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresMembership(val clubIdParam: String = "id")

/**
 * Ensures the authenticated user is the owner (organizer) of the club identified by [clubIdParam].
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresOrganizer(val clubIdParam: String = "id")
