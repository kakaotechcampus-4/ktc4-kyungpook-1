import { describe, expect, it } from 'vitest';
import { createSiteMetadata } from '@/lib/siteMetadata';

describe('deployment-specific public metadata', () => {
  it('uses the configured origin consistently instead of mixing demo and server domains', () => {
    const site = createSiteMetadata('https://gitory.example.org/');
    expect(site).toEqual({ origin: 'https://gitory.example.org', landing: 'https://gitory.example.org/login', image: 'https://gitory.example.org/og/gitory-social-light-v2.png' });
  });
  it.each(['http://gitory.example.org', 'https://user:password@gitory.example.org', 'https://gitory.example.org/cards/private', 'https://gitory.example.org/?token=private', 'https://gitory.example.org/#private'])('rejects noncanonical or sensitive URL configuration: %s', value => {
    expect(() => createSiteMetadata(value)).toThrow('VITE_SITE_URL');
  });
});
