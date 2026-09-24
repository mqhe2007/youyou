"use client";

import Link from "next/link";
import { useRef } from "react";
import { ArrowRight } from "@phosphor-icons/react/dist/ssr";
import { gsap } from "@/lib/gsap";
import { useSectionMotion } from "@/hooks/useSectionMotion";

const quickstart = "/quickstart";

export function CtaBand() {
  const root = useRef<HTMLElement>(null);

  useSectionMotion(root, ({ reduce }) => {
    if (reduce) return;

    gsap.from(".cta-copy", {
      y: 36,
      autoAlpha: 0,
      duration: 0.85,
      ease: "power3.out",
      scrollTrigger: {
        trigger: root.current,
        start: "top 80%",
      },
    });

    gsap.from(".cta-actions .btn", {
      y: 18,
      autoAlpha: 0,
      duration: 0.55,
      stagger: 0.08,
      ease: "power2.out",
      scrollTrigger: {
        trigger: root.current,
        start: "top 80%",
      },
    });
  });

  return (
    <section ref={root} className="bg-accent">
      <div className="mx-auto w-full max-w-[1400px] px-4 py-24 md:px-8 md:py-32">
        <div className="cta-copy">
          <h2 className="text-4xl font-semibold leading-[1.12] tracking-tight text-accent-fg md:text-5xl">
            让新旧照片，在同一条时间线上相遇
          </h2>
          <p className="mt-5 text-lg leading-relaxed text-accent-fg/80">
            已有邀请，下载客户端扫码接入；想管理自己的照片库，从部署说明开始。
          </p>
          <div className="cta-actions mt-9 flex flex-wrap items-center gap-3">
            <a className="btn btn-invert group" href="/download">
              下载客户端
              <ArrowRight size={16} weight="bold" className="cta-icon" />
            </a>
            <Link className="btn btn-on-accent" href={quickstart}>部署服务端</Link>
          </div>
        </div>
      </div>
    </section>
  );
}
