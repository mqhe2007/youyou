"use client";

import Link from "next/link";
import { useRef } from "react";
import { ArrowRight } from "@phosphor-icons/react/dist/ssr";
import { gsap } from "@/lib/gsap";
import { useSectionMotion } from "@/hooks/useSectionMotion";

const repo = "https://github.com/mqhe2007/youyou";
/** 站内路由：快速开始已不再是仓库里的 md */
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
            现在就装一套自己的相册
          </h2>
          <p className="mt-5 text-lg leading-relaxed text-accent-fg/80">
            不用编译，拉现成镜像就能起服务端，Android 扫码即可使用。
          </p>
          <div className="cta-actions mt-9 flex flex-wrap items-center gap-3">
            <Link className="btn btn-invert group" href={quickstart}>
              开始部署
              <ArrowRight size={16} weight="bold" className="cta-icon" />
            </Link>
            <a
              className="btn btn-on-accent"
              href={repo}
              target="_blank"
              rel="noreferrer"
            >
              查看仓库
            </a>
          </div>
        </div>
      </div>
    </section>
  );
}
