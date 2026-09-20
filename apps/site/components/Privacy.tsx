"use client";

import { useRef } from "react";
import { gsap } from "@/lib/gsap";
import { useSectionMotion } from "@/hooks/useSectionMotion";

const rows = [
  {
    q: "数据放在哪里？",
    a: "在你自己部署的服务器上，按用户目录分开存放。手机里不存原始文件。",
  },
  {
    q: "会上传到第三方吗？",
    a: "不会。客户端只和你的服务器通信，没有别的去处。",
  },
  {
    q: "可以随时带走吗？",
    a: "可以。数据库备份和校验一条命令完成，原始媒体始终在你自己的目录里。",
  },
];

export function Privacy() {
  const root = useRef<HTMLElement>(null);

  useSectionMotion(root, ({ reduce }) => {
    if (reduce) return;

    gsap.from(".privacy-heading", {
      y: 32,
      autoAlpha: 0,
      duration: 0.8,
      ease: "power3.out",
      scrollTrigger: {
        trigger: root.current,
        start: "top 78%",
      },
    });

    gsap.from(".privacy-row", {
      y: 22,
      autoAlpha: 0,
      duration: 0.6,
      stagger: 0.1,
      ease: "power2.out",
      scrollTrigger: {
        trigger: ".privacy-rows",
        start: "top 82%",
      },
    });
  });

  return (
    <section
      ref={root}
      id="privacy"
      className="mx-auto w-full max-w-[1400px] px-4 py-20 md:px-8 md:py-28"
    >
      <div className="privacy-heading">
        <h2 className="text-4xl font-semibold tracking-tight md:text-5xl">
          数据不离开你自己的服务器
        </h2>
        <p className="mt-5 leading-relaxed text-subtle">
          自部署、不追踪、不分析。服务端不可用时，客户端仍可浏览本地缓存。
        </p>
      </div>
      <div className="privacy-rows mt-14 divide-y divide-line border-t border-line">
        {rows.map((r) => (
          <div
            key={r.q}
            className="privacy-row grid gap-2 py-8 md:grid-cols-12 md:gap-8"
          >
            <h3 className="text-xl font-semibold md:col-span-5">{r.q}</h3>
            <p className="max-w-[56ch] leading-relaxed text-subtle md:col-span-7">
              {r.a}
            </p>
          </div>
        ))}
      </div>
    </section>
  );
}
