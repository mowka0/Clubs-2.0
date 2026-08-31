import { FC, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Modal } from '@telegram-apps/telegram-ui';
import { useHaptic } from '../../hooks/useHaptic';
import {
  ActivityTypeOptions,
  EventFormatOptions,
  EventTemplateOptions,
  SkladchinaTemplateOptions,
  type SkladchinaTemplateKey,
} from './CreateActivityPicker';
import { ClubPickerList, type ClubPickerOption } from './ClubPickerModal';
import {
  useDeleteEventTemplateMutation,
  useMyEventTemplatesQuery,
} from '../../queries/eventTemplates';
import type { ActivityType } from '../../api/activities';
import type { EventTemplateDto } from '../../api/eventTemplates';
import type { EventFormat } from '../../types/api';

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

type Step = 'type' | 'template' | 'event_format' | 'event_templates' | 'club';

function createRoute(
  clubId: string,
  type: ActivityType,
  template: SkladchinaTemplateKey | null,
  eventFormat: EventFormat | null,
): string {
  // Все три формата (V85) — одна форма создания: она читает ?format и адаптирует поля
  // (подпись лимита, правило под степпером, ограничение даты у «минимума»).
  if (type === 'event') {
    return `/clubs/${clubId}/events/new${eventFormat ? `?format=${eventFormat}` : ''}`;
  }
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
 * и второй модал мгновенно схлопывался. По той же причине переименование и
 * подтверждение удаления шаблона — подшаги здесь, а не всплывающие меню.
 *
 * После определения типа и клуба навигируем на per-club маршрут создания
 * (CreateEventPage / CreateSkladchinaPage читают :id). Шаблон встречи — короткий путь:
 * он несёт и клуб, и формат, поэтому уводит на форму, минуя оба шага.
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
  const [pendingEventFormat, setPendingEventFormat] = useState<EventFormat | null>(null);
  // Список тянем только когда флоу открыт и пользователь вообще может создавать — иначе
  // запрос уходил бы у каждого участника при каждом монтировании дока.
  const templatesQuery = useMyEventTemplatesQuery(open && canCreate);
  const deleteTemplateMut = useDeleteEventTemplateMutation();

  // На странице клуба «+» показывает шаблоны только этого клуба: предлагать чужие там,
  // где контекст однозначен, — сбивать с толку.
  const templates = (templatesQuery.data ?? []).filter(
    (t) => !presetClubId || t.clubId === presetClubId,
  );

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
    eventFormat: EventFormat | null,
  ) => {
    resetFlow();
    navigate(createRoute(clubId, type, template, eventFormat));
  };

  // Определяем клуб для выбранной тройки (тип, шаблон, формат): если неоднозначности нет — пикер пропускаем.
  const resolveClub = (
    type: ActivityType,
    template: SkladchinaTemplateKey | null,
    eventFormat: EventFormat | null,
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

  const handlePickEventFormat = (format: EventFormat) => {
    haptic.impact('medium');
    resolveClub('event', null, format);
  };

  const handleOpenEventTemplates = () => {
    haptic.impact('medium');
    setStep('event_templates');
  };

  /**
   * Возврат на шаг назад. Предыдущий шаг у каждого свой, и для 'club' он зависит от типа
   * активности — потому это switch, а не стек истории: шагов пять, ветвление одно, и стек
   * пришлось бы чистить в resetFlow наравне со всем остальным состоянием.
   */
  const handleBack = () => {
    haptic.impact('light');
    switch (step) {
      case 'template':
      case 'event_format':
        setPendingType(null);
        setStep('type');
        return;
      case 'event_templates':
        setStep('event_format');
        return;
      case 'club':
        setStep(pendingType === 'skladchina' ? 'template' : 'event_format');
        return;
      default:
        return;
    }
  };

  // Шаблон знает и клуб, и формат — оба шага пропускаются, форма читает содержимое по ?template.
  const handlePickEventTemplate = (template: EventTemplateDto) => {
    haptic.impact('medium');
    resetFlow();
    navigate(`/clubs/${template.clubId}/events/new?template=${template.id}`);
  };

  /**
   * Полная правка шаблона — отдельный экран с той же формой встречи (EventForm в режиме
   * `template`). Раньше карандаш открывал только переименование, а поправить место или
   * лимит можно было лишь заодно с созданием ненужной встречи (правка PO 2026-08-12).
   */
  const handleEditTemplate = (template: EventTemplateDto) => {
    haptic.impact('medium');
    resetFlow();
    navigate(`/clubs/${template.clubId}/event-templates/${template.id}/edit`);
  };

  const handleDeleteTemplate = async (template: EventTemplateDto) => {
    try {
      await deleteTemplateMut.mutateAsync({ clubId: template.clubId, templateId: template.id });
      haptic.notify('success');
    } catch {
      haptic.notify('error');
    }
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
    <Modal className="rd-pick-modal" open={open} onOpenChange={handleOpenChange}>
      {step === 'type' && (
        <ActivityTypeOptions onPick={handlePickType} onPickFeedback={handlePickFeedback} canCreate={canCreate} />
      )}
      {step === 'template' && <SkladchinaTemplateOptions onPick={handlePickTemplate} onBack={handleBack} />}
      {step === 'event_format' && (
        <EventFormatOptions
          onPick={handlePickEventFormat}
          templateCount={templates.length}
          onPickTemplates={handleOpenEventTemplates}
          onBack={handleBack}
        />
      )}
      {step === 'event_templates' && (
        <EventTemplateOptions
          templates={templates}
          onPick={handlePickEventTemplate}
          onEdit={handleEditTemplate}
          onDelete={handleDeleteTemplate}
          onBack={handleBack}
          isDeleting={deleteTemplateMut.isPending}
        />
      )}
      {step === 'club' && (
        <ClubPickerList clubs={organizerClubs} onPick={handlePickClub} onBack={handleBack} />
      )}
    </Modal>
  );
};
