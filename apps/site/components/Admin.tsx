"use client";

import Image from "next/image";
import { useRef } from "react";
import {
  FolderSimple,
  Trash,
  UsersThree,
  ListBullets,
} from "@phosphor-icons/react/dist/ssr";
import { gsap } from "@/lib/gsap";
import { useSectionMotion } from "@/hooks/useSectionMotion";

const caps = [
  {
    icon: UsersThree,
    title: "用户与媒体库",
    body: "一人一目录，终身绑定；给家人发专属二维码即可接入。",
  },
  {
    icon: FolderSimple,
    title: "媒体库刷新",
    body: "对挂载目录原位索引，原文件不复制，加盘换盘后重新扫描即可。",
  },
  {
    icon: ListBullets,
    title: "运行日志",
    body: "扫描、同步、任务进度留在管理页，出问题能直接看见。",
  },
  {
    icon: Trash,
    title: "回收站",
    body: "服务端删除进回收站，保留可恢复窗口，误删有退路。",
  },
];

export function Admin() {
  const root = useRef<HTMLElement>(null);

  useSectionMotion(root, ({ reduce }) => {
    if (reduce) return;

    gsap.from(".admin-heading", {
      y: 36,
      autoAlpha: 0,
      duration: 0.85,
      ease: "power3.out",
      scrollTrigger: {
        trigger: root.current,
        start: "top 78%",
      },
    });

    gsap.from(".admin-media-inner", {
      scale: 1.08,
      duration: 1.15,
      ease: "power2.out",
      scrollTrigger: {
        trigger: ".admin-media",
        start: "top 80%",
      },
    });

    gsap.from(".admin-cap", {
      y: 24,
      autoAlpha: 0,
      duration: 0.6,
      stagger: 0.08,
      ease: "power2.out",
      scrollTrigger: {
        trigger: ".admin-caps",
        start: "top 85%",
      },
    });
  });

  return (
    <section
      ref={root}
      id="admin"
      className="mx-auto w-full max-w-[1400px] px-4 py-20 md:px-8 md:py-28"
    >
      <div className="admin-heading">
        <h2 className="text-4xl font-semibold tracking-tight md:text-5xl">
          管理页跟着服务端一起走
        </h2>
        <p className="mt-5 text-lg leading-relaxed text-subtle">
          用户、媒体库、日志、回收站都在同一个管理页里，不用另装控制台。
        </p>
      </div>

      <div className="admin-media mt-14 overflow-hidden rounded-2xl border border-line bg-surface-muted md:mt-16">
        <div className="admin-media-inner will-change-transform">
          <Image
            src="/images/admin-real.png"
            alt="柚柚相册管理页真实界面"
            width={1440}
            height={1000}
            className="h-auto w-full"
          />
        </div>
      </div>

      <div className="admin-caps mt-12 grid gap-8 sm:grid-cols-2 lg:grid-cols-4">
        {caps.map((c) => (
          <div key={c.title} className="admin-cap border-t border-line pt-6">
            <c.icon size={22} weight="regular" className="text-subtle" />
            <h3 className="mt-4 text-lg font-semibold">{c.title}</h3>
            <p className="mt-1.5 max-w-[36ch] leading-relaxed text-subtle">
              {c.body}
            </p>
          </div>
        ))}
      </div>
    </section>
  );
}
