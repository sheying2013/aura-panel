import {heroui} from "@heroui/theme"

/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    './src/layouts/**/*.{js,ts,jsx,tsx,mdx}',
    './src/pages/**/*.{js,ts,jsx,tsx,mdx}',
    './src/components/**/*.{js,ts,jsx,tsx,mdx}',
    "./node_modules/@heroui/theme/dist/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        rock: {
          DEFAULT: "#c8b89a",
          50: "#faf8f5",
          100: "#f4f0e9",
          200: "#e9dfd0",
          300: "#d9cbaf",
          400: "#c8b89a",
          500: "#b5a587",
          600: "#9e8e72",
          700: "#82745d",
          800: "#695e4b",
          900: "#554c3e",
        },
      },
    },
  },
  darkMode: "class",
  plugins: [
    heroui({
      themes: {
        dark: {
          colors: {
            background: "#050505",
            foreground: "#e6e6e6",
            focus: "#c8b89a",
            primary: {
              DEFAULT: "#c8b89a",
              foreground: "#050505",
              50: "#faf8f5",
              100: "#f4f0e9",
              200: "#e9dfd0",
              300: "#d9cbaf",
              400: "#c8b89a",
              500: "#b5a587",
              600: "#9e8e72",
              700: "#82745d",
              800: "#695e4b",
              900: "#554c3e",
            },
            success: {
              DEFAULT: "#a3b89a",
              foreground: "#050505",
            },
            danger: {
              DEFAULT: "#c89a9a",
              foreground: "#050505",
            },
            warning: {
              DEFAULT: "#e0b84f",
              foreground: "#050505",
            },
            content1: "rgba(16, 16, 16, 0.65)",
            content2: "rgba(22, 22, 22, 0.75)",
            content3: "rgba(30, 30, 30, 0.8)",
            content4: "rgba(40, 40, 40, 0.85)",
          },
        },
        light: {
          colors: {
            background: "#f5f3f0",
            foreground: "#1a1a1a",
            focus: "#8b6914",
            primary: {
              DEFAULT: "#8b6914",
              foreground: "#ffffff",
            },
            success: {
              DEFAULT: "#4a7541",
              foreground: "#ffffff",
            },
            danger: {
              DEFAULT: "#a84242",
              foreground: "#ffffff",
            },
            warning: {
              DEFAULT: "#b8860b",
              foreground: "#ffffff",
            },
          },
        },
      },
    }),
  ],
}
