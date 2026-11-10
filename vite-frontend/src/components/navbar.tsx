import { useState, useEffect } from "react";
import { Link } from "@heroui/link";
import {
  Navbar as HeroUINavbar,
  NavbarBrand,
  NavbarContent,
} from "@heroui/navbar";
import { isWebViewFunc } from '@/utils/panel';
import { useNavigate } from "react-router-dom";

import { Logo } from "@/components/icons";

export const Navbar = () => {
  const navigate = useNavigate();
  const [isWebView, setIsWebView] = useState(false);

  useEffect(() => {
    setIsWebView(isWebViewFunc());
  }, []);

  return (
    <HeroUINavbar 
      maxWidth="xl" 
      position="sticky" 
      height="60px" 
      className="shrink-0 bg-[#050505]/90 backdrop-blur-md border-b border-white/[0.06] text-[#e6e6e6]"
    >
      <NavbarContent className="basis-1/5 sm:basis-full" justify="start">
        <NavbarBrand className="gap-2 max-w-fit">
          <Link
            className="flex justify-start items-center gap-2.5"
            color="foreground"
            href="/"
          >
            <div className="w-7 h-7 rounded-lg bg-[#c8b89a]/15 border border-[#c8b89a]/30 flex items-center justify-center text-[#c8b89a] shadow-sm">
              <Logo size={18} />
            </div>
            <span className="text-[15px] font-medium tracking-[0.08em] text-[#c8b89a]">Aura Panel</span>
          </Link>
        </NavbarBrand>
      </NavbarContent>

      <NavbarContent className="basis-1/5 sm:basis-full" justify="end">
        {/* WebView设置图标 */}
        {isWebView && (
          <button
            className="p-2 text-[#8a857c] hover:text-[#c8b89a] transition-colors"
            onClick={() => navigate('/settings')}
            title="面板设置"
          >
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
            </svg>
          </button>
        )}
      </NavbarContent>
    </HeroUINavbar>
  );
};
