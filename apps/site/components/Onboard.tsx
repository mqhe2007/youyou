"use client";

import Image from "next/image";
import { useRef } from "react";
import { gsap } from "@/lib/gsap";
import { useSectionMotion } from "@/hooks/useSectionMotion";

const steps = [
  {
    title: "管理员生成二维码",
    body: "在管理页为每个用户签发专属配对码。",
  },
  {
    title: "家人扫码登录",
    body: "Android 扫码等于登录，并绑定自己的媒体库目录。",
  },
  {
    title: "各自隔离",
    body: "一个目录只属于一个用户，媒体、标签、收藏互不可见。",
  },
];

export function Onboard() {
  const root = useRef<HTMLElement>(null);

  useSectionMotion(root, ({ reduce }) => {
    if (reduce) return;

    gsap.from(".onboard-heading", {
      y: 36,
      autoAlpha: 0,
      duration: 0.85,
      ease: "power3.out",
      scrollTrigger: {
        trigger: root.current,
        start: "top 78%",
      },
    });

    gsap.from(".onboard-media-inner", {
      scale: 1.12,
      duration: 1.2,
      ease: "power2.out",
      scrollTrigger: {
        trigger: ".onboard-media",
        start: "top 80%",
      },
    });

    gsap.from(".onboard-step", {
      x: -28,
      autoAlpha: 0,
      duration: 0.7,
      stagger: 0.14,
      ease: "power3.out",
      scrollTrigger: {
        trigger: ".onboard-steps",
        start: "top 82%",
      },
    });
  });

  return (
    <section
      ref={root}
      id="onboard"
      className="mx-auto w-full max-w-[1400px] px-4 py-20 md:px-8 md:py-28"
    >
      <div className="onboard-heading">
        <h2 className="text-4xl font-semibold tracking-tight md:text-5xl">
          多用户，全家共享
        </h2>
        <p className="mt-5 text-lg leading-relaxed text-subtle">
          管理员发一张专属二维码；家人扫码登录，媒体、标签、收藏互不可见。
        </p>
      </div>

      <div className="mt-14 grid items-start gap-10 md:mt-16 md:grid-cols-12 md:gap-14">
        <div className="onboard-media overflow-hidden rounded-2xl md:col-span-5">
          <div className="onboard-media-inner will-change-transform">
            <Image
              src="/images/qr-scan.jpg"
              alt="手机扫描笔记本电脑上的配对二维码"
              width={864}
              height={1152}
              className="h-[360px] w-full object-cover md:h-[480px]"
            />
          </div>
        </div>
        <div className="onboard-steps md:col-span-7">
          {steps.map((s, i) => (
            <div
              key={s.title}
              className="onboard-step grid grid-cols-[2.5rem_1fr] gap-4 border-t border-line py-7 last:border-b"
            >
              <span className="tabular pt-1 text-sm font-semibold text-subtle">
                {String(i + 1).padStart(2, "0")}
              </span>
              <div>
                <h3 className="text-xl font-semibold">{s.title}</h3>
                <p className="mt-2 max-w-[42ch] leading-relaxed text-subtle">
                  {s.body}
                </p>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
