import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { Select } from '@/components/ui/Select';

// 인적사항 폼의 모의 Select(profile-form.test)는 네이티브 select 라서 진짜 Radix 래퍼가 오류 상태를 읽어 주는지는
// 여기서 따로 본다. 메뉴를 열지 않는 시험이라 jsdom 에서도 빠르다.
afterEach(cleanup);
const options = [{ value: 'A', label: '가' }, { value: 'B', label: '나' }] as const;
const mount = (extra: { invalid?: boolean; describedBy?: string } = {}) => render(
  <>
    <Select value="A" onChange={() => undefined} options={options} label="학력 1 상태" {...extra} />
    <p id="why">상태를 선택해 주세요</p>
  </>,
);

describe('Select reports an invalid state to assistive technology', () => {
  it('marks the trigger invalid and reads the linked message with it', () => {
    mount({ invalid: true, describedBy: 'why' });
    const trigger = screen.getByRole('combobox', { name: '학력 1 상태' });
    expect(trigger).toHaveAttribute('aria-invalid', 'true');
    expect(trigger).toHaveAccessibleDescription('상태를 선택해 주세요');
  });
  it('adds nothing when the field is fine', () => {
    mount();
    const trigger = screen.getByRole('combobox', { name: '학력 1 상태' });
    expect(trigger).not.toHaveAttribute('aria-invalid');
    expect(trigger).not.toHaveAttribute('aria-describedby');
  });
});
