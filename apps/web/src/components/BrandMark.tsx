export function BrandMark({ className = "" }: { className?: string }) {
  return (
    <svg
      className={`brand-mark ${className}`.trim()}
      viewBox="0 0 64 64"
      aria-hidden="true"
      focusable="false"
    >
      <rect width="64" height="64" rx="15" fill="#245B49" />
      <path
        d="M17 25V18.5C17 16.6 18.6 15 20.5 15H27M37 15H43.5C45.4 15 47 16.6 47 18.5V25M47 39V45.5C47 47.4 45.4 49 43.5 49H37M27 49H20.5C18.6 49 17 47.4 17 45.5V39"
        fill="none"
        stroke="#F7FAF8"
        strokeWidth="4"
        strokeLinecap="round"
      />
      <path
        d="M20 39.5C25.5 32 30 43 36 34.5C39.2 30 42.2 31.4 46 27.5"
        fill="none"
        stroke="#C9DDD2"
        strokeWidth="3.2"
        strokeLinecap="round"
      />
      <circle cx="41.5" cy="28.5" r="4.5" fill="#D2A05A" stroke="#F7FAF8" strokeWidth="2" />
    </svg>
  );
}
