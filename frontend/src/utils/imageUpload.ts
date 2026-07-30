/**
 * Ограничения загрузки картинок — зеркалят backend `/api/upload` (`StorageController`).
 *
 * Значения нужны трём формам (аватар клуба, фото активности, чек складчины) и раньше были
 * скопированы в каждую. Расхождение с бэкендом даёт пользователю невнятную 400 вместо честной
 * подсказки до отправки, поэтому держим их в одном месте.
 */

/** Максимальный размер файла: 5 МБ. */
export const IMAGE_MAX_BYTES = 5 * 1024 * 1024;

/** Разрешённые MIME-типы: только JPEG и PNG. */
export const IMAGE_ALLOWED_MIMES: ReadonlySet<string> = new Set(['image/jpeg', 'image/png']);

/** Сообщение об ошибке для показа пользователю или null, если файл проходит проверку. */
export function validateImageFile(file: File): string | null {
  if (!IMAGE_ALLOWED_MIMES.has(file.type)) return 'Только JPEG и PNG';
  if (file.size > IMAGE_MAX_BYTES) return 'Файл больше 5 МБ';
  return null;
}
