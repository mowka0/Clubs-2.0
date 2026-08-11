import { describe, expect, it } from 'vitest';
import {
  IMAGE_ACCEPT_ATTR,
  IMAGE_ALLOWED_MIMES,
  IMAGE_MAX_BYTES,
  validateImageFile,
} from '../../utils/imageUpload';

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
