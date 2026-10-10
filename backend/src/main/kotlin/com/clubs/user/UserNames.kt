package com.clubs.user

/** Имя человека так, как его показывает список участников: «Имя Фамилия», фамилия — если указана. */
fun displayName(firstName: String, lastName: String?): String =
    if (lastName.isNullOrBlank()) firstName else "$firstName $lastName"
