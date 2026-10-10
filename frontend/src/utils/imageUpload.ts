/**
 * Ограничения загрузки картинок — зеркалят backend `/api/upload` (`StorageController`).
 *
 * Значения нужны формам аватара и обложки клуба и фото активности; раньше были скопированы
 * в каждую. Расхождение с бэкендом даёт пользователю невнятную 400 вместо честной подсказки
 * до отправки, поэтому держим их в одном месте.
 *
 * ⚠️ Чек складчины (`DuesPaymentSheet`) НАМЕРЕННО не пользуется этим списком и остаётся на
 * JPEG/PNG — решение PO 2026-08-11. Не «чинить» это расхождение как забытое.
 */

/** Максимальный размер файла: 5 МБ. */
export const IMAGE_MAX_BYTES = 5 * 1024 * 1024;

/**
 * Разрешённые MIME-типы: JPEG, PNG и WebP.
 *
 * WebP добавлен 2026-08-11: телефоны и мессенджеры отдают его всё чаще (Android-скриншоты,
 * пересланные из веба картинки), и пользователь не понимал, почему обычная с виду картинка
 * «не та». Список обязан совпадать с ALLOWED_CONTENT_TYPES на бэкенде.
 */
export const IMAGE_ALLOWED_MIMES: ReadonlySet<string> = new Set([
  'image/jpeg',
  'image/png',
  'image/webp',
]);

/**
 * Значение атрибута `accept` у `<input type="file">` — тот же список одной строкой.
 * Экспортируется, чтобы формы не выписывали типы руками: раньше они расходились с
 * валидацией, и файл проходил выбор, но отвергался проверкой (или наоборот).
 */
export const IMAGE_ACCEPT_ATTR = [...IMAGE_ALLOWED_MIMES].join(',');

/** Сообщение об ошибке для показа пользователю или null, если файл проходит проверку. */
export function validateImageFile(file: File): string | null {
  if (!IMAGE_ALLOWED_MIMES.has(file.type)) return 'Только JPEG, PNG или WebP';
  if (file.size > IMAGE_MAX_BYTES) return 'Файл больше 5 МБ';
  return null;
}

/**
 * Длинная сторона картинки после сжатия на телефоне, px. Аватар виден кружком до ~100 css px —
 * 640 с запасом на плотные экраны; обложка и фото встречи — во всю ширину телефона, 1600
 * покрывает 430 css px × 3. Раньше уходил оригинал камеры: полмегабайта и больше на кружок,
 * загрузка и показ шли секундами (PO 2026-10-08).
 */
export const IMAGE_MAX_SIDE = { avatar: 640, photo: 1600 } as const;
export type ImagePurpose = keyof typeof IMAGE_MAX_SIDE;

/** Качество при пересжатии: на глаз неотличимо от оригинала, вес в разы меньше. */
const SHRINK_QUALITY = 0.85;

/** Файл уже в размер и легче этого — не трогаем: выигрыша нет, пересжатие только портит. */
const SHRINK_SKIP_BYTES = 200 * 1024;

/** Размеры, вписанные в квадрат `maxSide`; меньшую картинку не увеличиваем. */
export function fitWithin(width: number, height: number, maxSide: number): { width: number; height: number } {
  const scale = Math.min(1, maxSide / Math.max(width, height));
  return { width: Math.round(width * scale), height: Math.round(height * scale) };
}

/**
 * Уменьшает картинку перед загрузкой. Обложка и фото уходят в JPEG: фото встречи бот шлёт в
 * Telegram ссылкой (`sendPhoto`), а прозрачность им не нужна — её закрашиваем белым. Аватар из
 * PNG/WebP идёт в WebP, чтобы сохранить прозрачность логотипа (браузер без WebP-кодера отдаст
 * PNG). Не вышло или выигрыша нет — уходит оригинал: сжатие ускоряет загрузку, но не имеет права
 * её сломать. Чеки сюда не попадают: их читают, и мелкий текст важнее веса.
 */
export async function shrinkImage(file: File, purpose: ImagePurpose): Promise<File> {
  const url = URL.createObjectURL(file);
  try {
    const image = new Image();
    image.src = url;
    await image.decode();
    const maxSide = IMAGE_MAX_SIDE[purpose];
    const fits = Math.max(image.naturalWidth, image.naturalHeight) <= maxSide;
    if (fits && file.size <= SHRINK_SKIP_BYTES) return file;

    const { width, height } = fitWithin(image.naturalWidth, image.naturalHeight, maxSide);
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d');
    if (!context) return file;
    const type = purpose === 'photo' || file.type === 'image/jpeg' ? 'image/jpeg' : 'image/webp';
    if (type === 'image/jpeg') {
      context.fillStyle = '#fff';
      context.fillRect(0, 0, width, height);
    }
    context.drawImage(image, 0, 0, width, height);

    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, type, SHRINK_QUALITY));
    if (!blob || blob.size >= file.size || !IMAGE_ALLOWED_MIMES.has(blob.type)) return file;
    return new File([blob], `image.${blob.type.split('/')[1]}`, { type: blob.type });
  } catch (error) {
    // Не декодировалось (битый файл, старый WebView) — отправляем как есть, сервер проверит сам.
    console.warn('shrinkImage failed, uploading original', error);
    return file;
  } finally {
    URL.revokeObjectURL(url);
  }
}
