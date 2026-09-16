import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { UserAvatar } from '@/components/ui/UserAvatar';

describe('UserAvatar', () => {
  it('프로필 이미지를 표시하고 로딩 실패 시 같은 크기의 이니셜로 대체한다', () => {
    render(<UserAvatar src="/demo-avatar.svg" login="hong-dev" size={48} />);

    const image = screen.getByRole('img', { name: 'hong-dev GitHub 프로필' });
    expect(image).toHaveAttribute('src', '/demo-avatar.svg');
    expect(image.closest('.user-avatar')).toHaveStyle({ width: '48px', height: '48px' });

    fireEvent.error(image);
    expect(screen.getByRole('img', { name: 'hong-dev 프로필 기본 이미지' })).toHaveTextContent('H');
    expect(screen.queryByRole('img', { name: 'hong-dev GitHub 프로필' })).not.toBeInTheDocument();
  });
});
