import { Nav } from "@/components/Nav";
import { Hero } from "@/components/Hero";
import { Daily } from "@/components/Daily";
import { Onboard } from "@/components/Onboard";
import { Admin } from "@/components/Admin";
import { Deploy } from "@/components/Deploy";
import { Privacy } from "@/components/Privacy";
import { Faq } from "@/components/Faq";
import { CtaBand } from "@/components/CtaBand";
import { Footer } from "@/components/Footer";

export default function Page() {
  return (
    <>
      <Nav />
      <main>
        <Hero />
        <Daily />
        <Onboard />
        <Admin />
        <Deploy />
        <Privacy />
        <Faq />
        <CtaBand />
      </main>
      <Footer />
    </>
  );
}
