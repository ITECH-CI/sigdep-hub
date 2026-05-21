/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        // Match the SIGDEP-3 hub legacy: Roboto for body, system stack as fallback.
        sans: ['Roboto', 'system-ui', '-apple-system', 'Segoe UI', 'Helvetica', 'Arial', 'sans-serif'],
      },
      colors: {
        // SIGDEP brand teal (matches the green/teal logos already used on the
        // legacy hub) plus standard "PNLS official" blue for headers/links.
        sigdep: {
          50:  '#e7f5f3',
          100: '#c4e6e1',
          200: '#9ed6cd',
          300: '#76c5b8',
          400: '#56b9aa',
          500: '#009d8e', // primary
          600: '#008a7c',
          700: '#007568',
          800: '#005f54',
          900: '#003e36',
        },
        // Secondary accent (indigo) for admin-side surfaces; contrasts
        // cleanly with the sigdep teal so the two roles never blur.
        accent: {
          50:  '#eef2ff',
          100: '#e0e7ff',
          200: '#c7d2fe',
          500: '#6366f1',
          600: '#4f46e5',
          700: '#4338ca',
          800: '#3730a3',
        },
        ink: {
          DEFAULT: '#1f2937', // slate-800
          muted:   '#6b7280', // slate-500
          subtle:  '#9ca3af', // slate-400
        },
      },
      boxShadow: {
        card: '0 1px 2px 0 rgb(0 0 0 / 0.05), 0 1px 3px 0 rgb(0 0 0 / 0.05)',
        elevated: '0 4px 12px -2px rgb(15 23 42 / 0.08), 0 2px 4px -2px rgb(15 23 42 / 0.06)',
      },
      keyframes: {
        // GlobalLoader: a thin teal bar that grows from left to right,
        // pauses briefly near the end and restarts. Conveys "something is
        // happening" without claiming any actual % progress.
        progress: {
          '0%':   { transform: 'scaleX(0)' },
          '50%':  { transform: 'scaleX(0.7)' },
          '100%': { transform: 'scaleX(1)' },
        },
        // Skeleton shimmer: a soft horizontal sweep across placeholder blocks.
        shimmer: {
          '0%':   { backgroundPosition: '-200% 0' },
          '100%': { backgroundPosition: '200% 0' },
        },
      },
      animation: {
        progress: 'progress 1.4s ease-in-out infinite',
        shimmer:  'shimmer 1.6s linear infinite',
      },
    },
  },
  plugins: [],
};
