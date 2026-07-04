import { FC, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Modal } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import {
  ActivityTypeOptions,
  SkladchinaTemplateOptions,
  type SkladchinaTemplateKey,
} from './CreateActivityPicker';
import { ClubPickerList, type ClubPickerOption } from './ClubPickerModal';
import type { ActivityType } from '../../api/activities';

interface CreateActivityFlowProps {
  /** Открыт ли флоу создания. */
  open: boolean;
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

type Step = 'type' | 'template' | 'club';

function createRoute(clubId: string, type: ActivityType, template: SkladchinaTemplateKey | null): string {
  if (type === 'event') return `/clubs/${clubId}/events/new`;
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
  organizerClubs,
  presetClubId,
  onClose,
}) => {
  const navigate = useNavigate();
  const haptic = useHaptic();
  const [step, setStep] = useState<Step>('type');
  const [pendingType, setPendingType] = useState<ActivityType | null>(null);
  const [pendingTemplate, setPendingTemplate] = useState<SkladchinaTemplateKey | null>(null);

  const dismiss = () => {
    setStep('type');
    setPendingType(null);
    setPendingTemplate(null);
    onClose();
  };

  const goToCreate = (clubId: string, type: ActivityType, template: SkladchinaTemplateKey | null) => {
    setStep('type');
    setPendingType(null);
    setPendingTemplate(null);
    onClose();
    navigate(createRoute(clubId, type, template));
  };

  // Определяем клуб для выбранной пары (тип, шаблон): если неоднозначности нет — пикер пропускаем.
  const resolveClub = (type: ActivityType, template: SkladchinaTemplateKey | null) => {
    if (presetClubId) {
      goToCreate(presetClubId, type, template);
      return;
    }
    if (organizerClubs.length === 1) {
      goToCreate(organizerClubs[0]!.id, type, template);
      return;
    }
    setPendingType(type);
    setPendingTemplate(template);
    setStep('club');
  };

  const handlePickType = (type: ActivityType) => {
    haptic.impact('medium');
    // «Сбор» сначала разветвляется на шаг выбора шаблона; «Событие» идёт сразу к выбору клуба.
    if (type === 'skladchina') {
      setPendingType(type);
      setStep('template');
      return;
    }
    resolveClub(type, null);
  };

  const handlePickTemplate = (template: SkladchinaTemplateKey) => {
    haptic.impact('medium');
    resolveClub('skladchina', template);
  };

  const handlePickClub = (clubId: string) => {
    if (!pendingType) return;
    haptic.impact('medium');
    goToCreate(clubId, pendingType, pendingTemplate);
  };

  const handleOpenChange = (next: boolean) => {
    if (!next) {
      haptic.impact('light');
      dismiss();
    }
  };

  return (
    <Modal open={open} onOpenChange={handleOpenChange}>
      {step === 'type' && <ActivityTypeOptions onPick={handlePickType} />}
      {step === 'template' && <SkladchinaTemplateOptions onPick={handlePickTemplate} />}
      {step === 'club' && <ClubPickerList clubs={organizerClubs} onPick={handlePickClub} />}
    </Modal>
  );
};
