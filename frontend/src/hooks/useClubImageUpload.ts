import { ChangeEvent, useRef, useState } from 'react';
import { useHaptic } from './useHaptic';
import { uploadImage } from '../api/clubs';
import { useUpdateClubMutation } from '../queries/clubs';
import { validateImageFile } from '../utils/imageUpload';

/**
 * Какую картинку клуба меняем. С V70 это два независимых поля: `avatarUrl` — кружок в шапке,
 * карточках и лентах; `coverUrl` — баннер шапки страницы клуба.
 */
export type ClubImageField = 'avatarUrl' | 'coverUrl';

/**
 * Общий флоу «выбрал файл → загрузили в /api/upload → сохранили ссылку в клубе».
 * Аватар и обложка отличаются только именем поля и видом кнопки, поэтому логика одна.
 *
 * В запросе уходит ровно одно поле: по контракту бэкенда null-поля означают «оставить как есть»,
 * поэтому смена обложки не задевает аватар и наоборот — ровно то разделение, ради которого
 * поле и заводилось.
 */
export function useClubImageUpload(clubId: string, field: ClubImageField) {
  const inputRef = useRef<HTMLInputElement>(null);
  const haptic = useHaptic();
  const updateClub = useUpdateClubMutation();
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const busy = uploading || updateClub.isPending;

  const pick = () => {
    if (busy) return;
    haptic.impact('light');
    inputRef.current?.click();
  };

  const handleFile = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    // Сбрасываем input сразу: иначе повторный выбор того же файла не даёт события change.
    e.target.value = '';
    if (!file) return;

    setError(null);
    const invalid = validateImageFile(file);
    if (invalid) {
      setError(invalid);
      haptic.notify('error');
      return;
    }

    setUploading(true);
    try {
      const url = await uploadImage(file);
      await updateClub.mutateAsync({ id: clubId, body: { [field]: url } });
      haptic.notify('success');
    } catch (err) {
      setError((err as Error).message || 'Не удалось сохранить картинку');
      haptic.notify('error');
    } finally {
      setUploading(false);
    }
  };

  return { inputRef, pick, handleFile, busy, error };
}
