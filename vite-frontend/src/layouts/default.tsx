import { Navbar } from "@/components/navbar";

export default function DefaultLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div className="relative flex flex-col min-h-screen bg-[#050505] text-[#e6e6e6]">
      {/* 氛围渐变光晕 */}
      <div className="fixed inset-0 pointer-events-none z-0 overflow-hidden">
        <div className="absolute -top-[20%] left-1/2 -translate-x-1/2 w-[1000px] h-[500px] bg-[radial-gradient(ellipse_at_center,rgba(200,184,154,0.06),transparent_70%)] blur-3xl" />
        <div className="absolute -bottom-[20%] right-[-10%] w-[600px] h-[600px] bg-[radial-gradient(ellipse_at_center,rgba(163,184,154,0.04),transparent_70%)] blur-3xl" />
      </div>
      <Navbar />
      <main className="container mx-auto max-w-7xl px-4 sm:px-6 flex-grow pt-4 sm:pt-16 relative z-10">
        {children}
      </main>
    </div>
  );
}
