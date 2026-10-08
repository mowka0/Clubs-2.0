import { describe, expect, it } from 'vitest';
import { IMAGE_ACCEPT_ATTR, IMAGE_ALLOWED_MIMES, IMAGE_MAX_BYTES, validateImageFile, fitWithin, shrinkImage } from '../../utils/imageUpload';

/** Файл нужного типа и размера: содержимое неважно, проверяется только type/size. */
function fileOf(type: string, bytes = 10): File {
  return new File([new Uint8Array(bytes)], 'pic', { type });
}

describe('validateImageFile', () => {
  it.each(['image/jpeg', 'image/png', 'image/webp'])('пропускает %s', (mime) => {
    expect(validateImageFile(fileOf(mime))).toBeNull();
  });

  it('отвергает формат вне списка и называет разрешённые', () => {
    expect(validateImageFile(fileOf('image/gif'))).toBe('Только JPEG, PNG или WebP');
    expect(validateImageFile(fileOf('application/pdf'))).toBe('Только JPEG, PNG или WebP');
  });

  it('отвергает файл тяжелее 5 МБ, но пропускает ровно 5 МБ', () => {
    expect(validateImageFile(fileOf('image/webp', IMAGE_MAX_BYTES))).toBeNull();
    expect(validateImageFile(fileOf('image/webp', IMAGE_MAX_BYTES + 1))).toBe('Файл больше 5 МБ');
  });
});

describe('IMAGE_ACCEPT_ATTR', () => {
  it('перечисляет ровно те же типы, что и валидация — иначе файл выберется и упадёт', () => {
    expect(IMAGE_ACCEPT_ATTR.split(',')).toEqual([...IMAGE_ALLOWED_MIMES]);
    expect(IMAGE_ACCEPT_ATTR).toContain('image/webp');
  });
});

describe('fitWithin — размеры после сжатия', () => {
  it('большое фото вписывается длинной стороной', () => {
    expect(fitWithin(4032, 3024, 1600)).toEqual({ width: 1600, height: 1200 });
    expect(fitWithin(1170, 2532, 640)).toEqual({ width: 296, height: 640 });
  });

  it('маленькую картинку не увеличиваем', () => {
    expect(fitWithin(300, 200, 640)).toEqual({ width: 300, height: 200 });
  });
});

describe('shrinkImage — сжатие не ломает загрузку', () => {
  it('картинку не удалось декодировать — уходит оригинал', async () => {
    const file = new File([new Uint8Array([1, 2, 3])], 'broken.jpg', { type: 'image/jpeg' });

    await expect(shrinkImage(file, 'avatar')).resolves.toBe(file);
  });
});
