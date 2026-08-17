import { FC } from 'react';
import { useHaptic } from '../../hooks/useHaptic';
import { useNewClubChatLinkQuery, useStartChatLinkingMutation } from '../../queries/chatLink';

interface CreateClubChoiceSheetProps {
  /** Человек выбрал длинный путь — форму создания клуба с нуля. */
  onPickFromScratch: () => void;
}

/**
 * Развилка «+ Клуб»: из телеграм-чата или с нуля.
 *
 * Раньше кнопка вела прямо в форму, и путь «клуб из чата» существовал только для человека без
 * клубов — на первом экране. У того, кто уже завёл клуб, второго чата как бы не было, хотя это
 * ровно наш сценарий: одна группа — один клуб (решение PO 2026-08-17).
 *
 * Чат стоит первым намеренно: в чат-модели это главный путь, а форма — запасной для тех, у кого
 * группы ещё нет.
 */
export const CreateClubChoiceSheet: FC<CreateClubChoiceSheetProps> = ({ onPickFromScratch }) => {
  const haptic = useHaptic();
  const { data } = useNewClubChatLinkQuery();
  const startLinking = useStartChatLinkingMutation();

  return (
    <div className="rd-pick">
      <div className="rd-pick-item">
        {/* Пункт ждёт ссылку: username бота живёт на сервере, и тап до её приезда был бы тапом
            в пустоту (та же причина, что на экране подключения чата). */}
        <button
          type="button"
          className="rd-pick-row"
          disabled={!data || startLinking.isPending}
          onClick={() => {
            if (!data) return;
            haptic.impact('medium');
            startLinking.mutate({ clubId: null, startGroupUrl: data.startGroupUrl });
          }}
        >
          <span className="rd-pick-ic" aria-hidden="true">💬</span>
          <span className="rd-pick-txt">
            <b>Из телеграм-чата</b>
            <span>Клуб соберётся сам из группы: название, размер, участники</span>
          </span>
        </button>
      </div>
      <div className="rd-pick-item">
        <button
          type="button"
          className="rd-pick-row"
          onClick={() => { haptic.impact('light'); onPickFromScratch(); }}
        >
          <span className="rd-pick-ic" aria-hidden="true">✏️</span>
          <span className="rd-pick-txt">
            <b>Создать с нуля</b>
            <span>Заполнить форму самому — если группы ещё нет</span>
          </span>
        </button>
      </div>
    </div>
  );
};
