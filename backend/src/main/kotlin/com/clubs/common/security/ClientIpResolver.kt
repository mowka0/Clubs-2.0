package com.clubs.common.security

import jakarta.servlet.http.HttpServletRequest

/**
 * Адрес клиента из цепочки прокси Traefik → nginx фронта → бэкенд. Общий для rate limit и
 * allowlist ResultURL провайдера: без него allowlist видел бы адрес Traefik и отвергал бы всё.
 *
 * Каждый прокси дописывает в `X-Forwarded-For` того, от кого получил запрос, поэтому хвост
 * цепочки выглядит как `…, <клиент>, <traefik>`. Последний элемент — внутренний адрес
 * Traefik, один на всё окружение: ключ по нему складывал всех пользователей в один бакет
 * (баг прода 2026-08-19). Берём предпоследний — его дописал доверенный Traefik, подделать
 * клиент не может. Первые элементы клиентские и ненадёжны: ротацией фейков можно было бы
 * штамповать свежие ключи `ip:*` и обходить лимит (security-ревью feedback).
 */
object ClientIpResolver {

    /**
     * Сколько прокси между клиентом и бэкендом дописывают себя в `X-Forwarded-For`.
     * Сейчас один — nginx фронта, дописывающий адрес Traefik (docker-compose.prod.yml).
     * Меняется вместе с цепочкой прокси, иначе ключ съедет на внутренний адрес.
     * Российский прокси перед Traefik (infra/ru-proxy) сюда не входит: он L4 и отдаёт адрес
     * клиента Traefik по PROXY protocol, в `X-Forwarded-For` себя не дописывает.
     */
    const val TRUSTED_PROXY_HOPS = 1

    fun resolve(request: HttpServletRequest): String {
        val chain = request.getHeader("X-Forwarded-For")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        return when {
            chain.size >= TRUSTED_PROXY_HOPS + 1 -> chain[chain.size - 1 - TRUSTED_PROXY_HOPS]
            chain.isNotEmpty() -> chain.last()
            else -> request.remoteAddr
        }
    }
}
