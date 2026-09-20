"use client";

import { useRef, useState } from "react";
import { CaretDown } from "@phosphor-icons/react/dist/ssr";
import { gsap } from "@/lib/gsap";
import { useSectionMotion } from "@/hooks/useSectionMotion";

const faqs = [
  {
    q: "需要什么硬件？",
    a: "一台能跑 Docker 的机器就够。服务端是单进程加 SQLite，家庭 NAS 或旧电脑都能带得动。",
  },
  {
    q: "支持几个用户？",
    a: "多用户。每个用户绑定各自的媒体库目录，一个目录只属于一个用户，媒体、标签、收藏完全隔离。",
  },
  {
    q: "数据迁移方便吗？",
    a: "方便。服务端对挂载目录原位索引，不复制原文件，换盘换机器后重新挂载目录即可。",
  },
  {
    q: "免费吗？",
    a: "开源项目，以 AGPL-3.0-only 授权。个人使用和商业部署都不需要另行取得授权或付费。只有在你分发修改版本、或把它作为网络服务对外提供时，才必须以同一许可证公开完整源码。",
  },
];

export function Faq() {
  const [open, setOpen] = useState<number | null>(0);
  const root = useRef<HTMLElement>(null);

  useSectionMotion(root, ({ reduce }) => {
    if (reduce) return;

    gsap.from(".faq-heading", {
      y: 32,
      autoAlpha: 0,
      duration: 0.8,
      ease: "power3.out",
      scrollTrigger: {
        trigger: root.current,
        start: "top 78%",
      },
    });

    gsap.from(".faq-item", {
      y: 18,
      autoAlpha: 0,
      duration: 0.55,
      stagger: 0.08,
      ease: "power2.out",
      scrollTrigger: {
        trigger: ".faq-list",
        start: "top 85%",
      },
    });
  });

  return (
    <section
      ref={root}
      id="faq"
      className="mx-auto w-full max-w-[1400px] px-4 py-20 md:px-8 md:py-28"
    >
      <h2 className="faq-heading text-4xl font-semibold tracking-tight md:text-5xl">
        常见问题
      </h2>
      <div className="faq-list mt-12 divide-y divide-line border-t border-line">
        {faqs.map((f, i) => {
          const isOpen = open === i;
          return (
            <div key={f.q} className="faq-item">
              <button
                type="button"
                className="flex w-full items-center justify-between gap-6 py-6 text-left"
                aria-expanded={isOpen}
                aria-controls={`faq-panel-${i}`}
                onClick={() => setOpen(isOpen ? null : i)}
              >
                <span className="text-lg font-semibold">{f.q}</span>
                <CaretDown
                  size={18}
                  weight="bold"
                  className={
                    isOpen
                      ? "shrink-0 rotate-180 transition-transform"
                      : "shrink-0 transition-transform"
                  }
                />
              </button>
              <div id={`faq-panel-${i}`} hidden={!isOpen} className="pb-7">
                <p className="leading-relaxed text-subtle">
                  {f.a}
                </p>
              </div>
            </div>
          );
        })}
      </div>
    </section>
  );
}
