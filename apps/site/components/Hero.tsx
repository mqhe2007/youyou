"use client";

import Image from "next/image";
import { useRef } from "react";
import { ArrowRight } from "@phosphor-icons/react/dist/ssr";
import { gsap } from "@/lib/gsap";
import { useSectionMotion } from "@/hooks/useSectionMotion";

export function Hero() {
  const root = useRef<HTMLElement>(null);

  useSectionMotion(root, ({ reduce }) => {
    if (reduce) return;

    const tl = gsap.timeline({
      defaults: { ease: "power3.out" },
    });

    tl.from(".hero-title", { y: 48, autoAlpha: 0, duration: 1 })
      .from(".hero-lede", { y: 28, autoAlpha: 0, duration: 0.7 }, "-=0.55")
      .from(
        ".hero-actions .btn",
        { y: 20, autoAlpha: 0, duration: 0.55, stagger: 0.08 },
        "-=0.4",
      )
      .from(
        ".hero-media-inner",
        { scale: 1.14, duration: 1.35, ease: "power2.out" },
        0,
      )
      .from(
        ".hero-media",
        { clipPath: "inset(12% 8% 12% 8% round 16px)", duration: 1.2 },
        0,
      );

    gsap.to(".hero-media-inner", {
      yPercent: 8,
      ease: "none",
      scrollTrigger: {
        trigger: root.current,
        start: "top top",
        end: "bottom top",
        scrub: true,
      },
    });
  });

  return (
    <section
      ref={root}
      id="top"
      className="mx-auto w-full max-w-[1400px] px-4 pt-16 md:px-8 md:pt-20"
    >
      <div className="grid items-center gap-10 md:grid-cols-12 md:gap-12">
        <div className="md:col-span-6">
          <h1 className="hero-title text-[44px] font-semibold leading-[1.06] tracking-tight md:text-[64px]">
            轻松管理人生影相
          </h1>
          <p className="hero-lede mt-6 text-lg leading-relaxed text-subtle">
            照片存在你自己部署的服务器上，手机端只留索引和缓存。不经过任何第三方。
          </p>
          <div className="hero-actions mt-9 flex flex-wrap items-center gap-4">
            <a href="#deploy" className="btn btn-primary group">
              开始部署
              <ArrowRight size={16} weight="bold" className="cta-icon" />
            </a>
            <a href="#daily" className="btn btn-ghost group">
              看看怎么用
              <ArrowRight size={16} weight="bold" className="cta-icon" />
            </a>
          </div>
        </div>
        <div className="md:col-span-6">
          <div className="hero-media overflow-hidden rounded-2xl">
            <div className="hero-media-inner will-change-transform">
              <Image
                src="/images/hero-photo.jpg"
                alt="暖象牙台面上的新鲜柚子与一叠打印照片"
                width={864}
                height={1152}
                priority
                fetchPriority="high"
                className="h-[420px] w-full object-cover md:h-[540px]"
              />
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
