# A11y: DialogTitle/Description warnings от telegram-ui Modal

Обнаружено 2026-04-25 при тесте PR #24 (`feature/pre-design-stores`) на staging.

## Что не так

DevTools Console показывает:
```
DialogContent requires a DialogTitle for the component to be accessible
for screen reader users.
Warning: Missing Description or aria-describedby={undefined} for DialogContent.
```

Источник — `Modal` из `@telegram-apps/telegram-ui`, который под капотом использует `Drawer` от `@xelene/vaul-with-scroll-fix`, который в свою очередь Radix `@radix-ui/react-dialog`. Radix требует наличия `<DialogTitle>` (хотя бы visually-hidden) для screen readers.

5 модалок без правильного title:
- `pages/ClubPage.tsx:244` — apply-modal
- `pages/OrganizerClubManage.tsx:82` — MemberProfileModal
- `pages/OrganizerClubManage.tsx:331` — EventDetailModal
- `pages/OrganizerClubManage.tsx:602` — Attendance Modal
- `pages/OrganizerClubManage.tsx:913` — Delete confirm Modal

Все имеют **визуальный** заголовок внутри body (`<Text weight="2">Заголовок</Text>`), но это обычный Text без Radix-семантики DialogTitle.

## Pre-existing? Да

Модалки появились до миграции. Warning существовал и до PR #24, просто стал заметнее на свежем staging deploy (или пользователь только что обратил внимание).

## Влияние

- **Runtime:** нулевое. UI работает, кликается, отображается.
- **A11y:** screen reader не озвучит заголовок диалога — пользователь не понимает контекст
- **Console noise:** засоряет debug при работе с модалками

## Варианты фикса

### Вариант A: Direct dep на `@radix-ui/react-dialog` + VisuallyHidden
```bash
npm install --legacy-peer-deps @radix-ui/react-dialog @radix-ui/react-visually-hidden
```

В каждой модалке:
```tsx
import { DialogTitle } from '@radix-ui/react-dialog';
import { VisuallyHidden } from '@radix-ui/react-visually-hidden';

<Modal open onOpenChange={...}>
  <VisuallyHidden asChild>
    <DialogTitle>Профиль участника</DialogTitle>
  </VisuallyHidden>
  {/* остальной контент */}
</Modal>
```

**Минус:** +2 deps. Хотя они уже transitively через telegram-ui — explicit лучше для control.

### Вариант B: Import `Drawer.Title` from `@xelene/vaul-with-scroll-fix`
```tsx
import { Drawer } from '@xelene/vaul-with-scroll-fix';

<Modal open ...>
  <Drawer.Title style={{ position: 'absolute', /* visually-hidden styles */ }}>
    Заголовок
  </Drawer.Title>
  ...
</Modal>
```

**Минус:** transitive dep. Если telegram-ui обновится и сменит drawer-fork — наш код сломается без предупреждения.

### Вариант C (рекомендую): обновить telegram-ui до версии с DialogTitle поддержкой
Проверить changelog `@telegram-apps/telegram-ui` — возможно в новой версии Modal принимает `title` prop, который автоматически прокидывает DialogTitle. Если да — простой апгрейд + добавить prop в наши 5 usages.

### Вариант D: localized fix в helper компоненте
Создать `frontend/src/components/AccessibleModal.tsx` — обёртка вокруг telegram-ui Modal, добавляет `<VisuallyHidden><DialogTitle>` автоматически. Все 5 usages переключить на новый компонент.

```tsx
export const AccessibleModal: FC<AccessibleModalProps> = ({ title, children, ...modalProps }) => (
  <Modal {...modalProps}>
    <VisuallyHidden asChild>
      <DialogTitle>{title}</DialogTitle>
    </VisuallyHidden>
    {children}
  </Modal>
);
```

## Когда фиксить

Не блокирует. Низкий приоритет (Mini App users редко используют screen reader, в Telegram среде вообще не уверены что доступно). Но a11y warnings в консоли могут маскировать другие важные warnings.

Кандидат: `bugfix/modal-dialog-title-a11y`, ~30 мин (вариант A или D), либо вместе с другими a11y-фиксами в общую ветку перед public release.
