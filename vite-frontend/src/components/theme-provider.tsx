import React, { useEffect } from 'react';
import { useTheme } from '@heroui/use-theme';

interface ThemeProviderProps {
  children: React.ReactNode;
}

export const ThemeProvider: React.FC<ThemeProviderProps> = ({ children }) => {
  const { theme, setTheme } = useTheme();

  useEffect(() => {
    // 永久锁定暗黑黑金体系
    document.documentElement.classList.add('dark');
    document.documentElement.style.colorScheme = 'dark';
    if (theme !== 'dark') {
      setTheme('dark');
    }
  }, [theme, setTheme]);

  return <>{children}</>;
};
