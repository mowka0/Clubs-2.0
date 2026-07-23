import { FC, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Modal } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import {
  ActivityTypeOptions,
  EventFormatOptions,
  SkladchinaTemplateOptions,
  type EventFormatKey,
  type SkladchinaTemplateKey,
} from './CreateActivityPicker';
import { ClubPickerList, type ClubPickerOption } from './ClubPickerModal';
import type { ActivityType } from '../../api/activities';

interface CreateActivityFlowProps {
  /** Открыт ли флоу создания. */
  open: boolean;
  /**
   * Организует ли пользователь хотя бы один клуб. Флоу открывается всем (пункт
   * «Сообщить о проблеме» общедоступен), но пункты создания видят только организаторы.
   */
  canCreate: boolean;
  /** Клубы, которыми пользователь управляет — флоу навигирует только внутри них. */
  organizerClubs: ClubPickerOption[];
  /**
   * Если задан — выбор клуба пропускается целиком: после выбора типа флоу сразу
   * уходит в этот клуб. Используется, когда FAB нажат со страницы клуба, который
   * пользователь сейчас просматривает (и организует).
   */
  presetClubId?: string | null;
  /** Закрывает весь флоу (сбрасывает внутреннее состояние шага). */
  onClose: () => void;
}

type Step = 'type' | 'template' | 'event_format' | 'club';

function createRoute(
  clubId: string,
  type: ActivityType,
  template: SkladchinaTemplateKey | null,
  eventFormat: EventFormatKey | null,
): string {
  // Открытая встреча (V62) — та же форма создания события, форма читает ?format=open и прячет степпер лимита.
  if (type === 'event') return `/clubs/${clubId}/events/new${eventFormat === 'open' ? '?format=open' : ''}`;
  // У split_bill своя страница, не зависящая от точки входа (выбирает событие, делит счёт).
  if (template === 'split_bill') return `/clubs/${clubId}/skladchina/split`;
  return `/clubs/${clubId}/skladchina/new`;
}

/**
 * Контроллер глобального флоу создания «тип → клуб → форма».
 *
 * Рендерит ОДИН Modal, тело которого переключается по `step`. Шаг 'type' выбирает
 * тип активности; шаг 'club' — целевой клуб, если пользователь организует несколько.
 * Один Modal обязателен: при отдельном Modal на каждый шаг закрывающийся сносил
 * общий portal/scroll-lock оверлей, который только что смонтировал открывающийся,
 * и второй модал мгновенно схлопывался.
 *
 * После определения типа и клуба навигируем на per-club маршрут создания
 * (CreateEventPage / CreateSkladchinaPage читают :id).
 */
export const CreateActivityFlow: FC<CreateActivityFlowProps> = ({
  open,
  canCreate,
  organizerClubs,
  presetClubId,
  onClose,
}) => {
  const navigate = useNavigate();
  const location = useLocation();
  const haptic = useHaptic();
  const [step, setStep] = useState<Step>('type');
  const [pendingType, setPendingType] = useState<ActivityType | null>(null);
  const [pendingTemplate, setPendingTemplate] = useState<SkladchinaTemplateKey | null>(null);
  const [pendingEventFormat, setPendingEventFormat] = useState<EventFormatKey | null>(null);

  // Единый сброс flow к первому шагу — используется и при закрытии, и перед навигацией на форму,
  // чтобы повторное открытие «+» никогда не стартовало с призрачным состоянием прошлого прохода.
  const resetFlow = () => {
    setStep('type');
    setPendingType(null);
    setPendingTemplate(null);
    setPendingEventFormat(null);
    onClose();
  };

  const dismiss = resetFlow;

  const goToCreate = (
    clubId: string,
    type: ActivityType,
    template: SkladchinaTemplateKey | null,
    eventFormat: EventFormatKey | null,
  ) => {
    resetFlow();
    navigate(createRoute(clubId, type, template, eventFormat));
  };

  // Определяем клуб для выбранной тройки (тип, шаблон, формат): если неоднозначности нет — пикер пропускаем.
  const resolveClub = (
    type: ActivityType,
    template: SkladchinaTemplateKey | null,
    eventFormat: EventFormatKey | null,
  ) => {
    if (presetClubId) {
      goToCreate(presetClubId, type, template, eventFormat);
      return;
    }
    if (organizerClubs.length === 1) {
      goToCreate(organizerClubs[0]!.id, type, template, eventFormat);
      return;
    }
    setPendingType(type);
    setPendingTemplate(template);
    setPendingEventFormat(eventFormat);
    setStep('club');
  };

  const handlePickType = (type: ActivityType) => {
    haptic.impact('medium');
    // Оба типа разветвляются на промежуточный шаг: «Сбор» — выбор шаблона,
    // «Событие» — выбор формата (с местами / открытая встреча, PO 2026-07-21).
    if (type === 'skladchina') {
      setPendingType(type);
      setStep('template');
      return;
    }
    setPendingType(type);
    setStep('event_format');
  };

  const handlePickFeedback = () => {
    haptic.impact('medium');
    // Путь запоминаем ДО resetFlow: форма кладёт его в репорт как «где воспроизвелось».
    const from = location.pathname;
    resetFlow();
    navigate('/feedback', { state: { from } });
  };

  const handlePickTemplate = (template: SkladchinaTemplateKey) => {
    haptic.impact('medium');
    resolveClub('skladchina', template, null);
  };

  const handlePickEventFormat = (format: EventFormatKey) => {
    haptic.impact('medium');
    resolveClub('event', null, format);
  };

  const handlePickClub = (clubId: string) => {
    if (!pendingType) return;
    haptic.impact('medium');
    goToCreate(clubId, pendingType, pendingTemplate, pendingEventFormat);
  };

  const handleOpenChange = (next: boolean) => {
    if (!next) {
      haptic.impact('light');
      dismiss();
    }
  };

  return (
    <Modal open={open} onOpenChange={handleOpenChange}>
      {step === 'type' && (
        <ActivityTypeOptions onPick={handlePickType} onPickFeedback={handlePickFeedback} canCreate={canCreate} />
      )}
      {step === 'template' && <SkladchinaTemplateOptions onPick={handlePickTemplate} />}
      {step === 'event_format' && <EventFormatOptions onPick={handlePickEventFormat} />}
      {step === 'club' && <ClubPickerList clubs={organizerClubs} onPick={handlePickClub} />}
    </Modal>
  );
};
