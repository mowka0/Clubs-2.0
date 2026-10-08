import { describe, it, expect } from 'vitest';
import { resolveDeepLink } from '../../components/DeepLinkHandler';
import type { MembershipDto } from '../../types/api';

/**
 * Куда ведёт ссылка запуска. Главный случай — кнопка «Открыть клуб» в чате клуба: участника
 * она приводит в клуб, а того, кто ещё не вступил, — на экран приглашения (PO 2026-10-08).
 */

const CLUB = '11111111-2222-3333-4444-555555555555';

function membership(status: string, clubId = CLUB, subscriptionExpiresAt: string | null = null): MembershipDto {
  return { clubId, status, subscriptionExpiresAt } as MembershipDto;
}

const IN_A_WEEK = new Date(Date.now() + 7 * 24 * 3600 * 1000).toISOString();

describe('resolveDeepLink', () => {
  it('ссылка на клуб участнику — сам клуб', () => {
    expect(resolveDeepLink(`club_${CLUB}`, [membership('active')])).toBe(`/clubs/${CLUB}`);
    // Должник место занимает: ему нужен клуб с оплатой, а не приглашение вступить.
    expect(resolveDeepLink(`club_${CLUB}`, [membership('frozen')])).toBe(`/clubs/${CLUB}`);
  });

  it('отменил подписку, но период оплачен — всё ещё участник, а не гость', () => {
    // Повторное «Вступить» обнулило бы оплаченный срок: сервер пускает cancelled заново.
    expect(resolveDeepLink(`club_${CLUB}`, [membership('cancelled', CLUB, IN_A_WEEK)])).toBe(`/clubs/${CLUB}`);
  });

  it('ссылка на клуб не участнику — экран приглашения', () => {
    expect(resolveDeepLink(`club_${CLUB}`, [])).toBe(`/clubs/${CLUB}/join`);
    expect(resolveDeepLink(`club_${CLUB}`, [membership('cancelled')])).toBe(`/clubs/${CLUB}/join`);
    expect(resolveDeepLink(`club_${CLUB}`, [membership('active', 'other-club')])).toBe(`/clubs/${CLUB}/join`);
  });

  it('клубы человека ещё грузятся — ответа нет; не загрузились — открываем клуб', () => {
    expect(resolveDeepLink(`club_${CLUB}`, undefined)).toBeUndefined();
    expect(resolveDeepLink(`club_${CLUB}`, null)).toBe(`/clubs/${CLUB}`);
  });

  it('остальные ссылки от членства не зависят', () => {
    expect(resolveDeepLink(`event_${CLUB}`)).toBe(`/events/${CLUB}`);
    expect(resolveDeepLink(`skladchina_${CLUB}`)).toBe(`/skladchina/${CLUB}`);
    expect(resolveDeepLink(`billing_${CLUB}`)).toBe(`/clubs/${CLUB}/manage?billing=done`);
    expect(resolveDeepLink('invite_0123456789abcdef')).toBe('/invite/0123456789abcdef');
  });

  it('без ссылки или с незнакомой — никуда', () => {
    expect(resolveDeepLink(null)).toBeNull();
    expect(resolveDeepLink('ad_tg1')).toBeNull();
  });
});
