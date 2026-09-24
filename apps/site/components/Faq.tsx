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
    a: "服务端对已挂载目录原位索引，不为索引复制原件。换盘或换机器需要保留目录绑定关系、正确挂载与权限，并重新扫描；数据库备份不包含原始媒体文件。",
  },
  {
    q: "会自动备份手机新照片吗？",
    a: "不会。你选中照片后手动上传到服务器，也可以按需下载到手机。请另行安排重要照片的完整备份。",
  },
  {
    q: "在外面也能访问吗？",
    a: "可以在你自行配置了可达网络之后访问。柚柚不会自动提供公网地址、端口映射或内网穿透。",
  },
  {
    q: "手机上的照片和缓存有什么区别？",
    a: "本机原件和已下载的照片是手机里的文件；缓存只辅助浏览。仅服务器上的照片离线时不保证可打开原件。",
  },
  {
    q: "误删照片能恢复吗？",
    a: "服务端删除的原件先进入回收站，固定保留 30 天；手机上的删除与恢复以系统实际能力为准。重要照片仍应另做备份。",
  },
  {
    q: "免费吗？",
    a: "柚柚是开源项目，采用 AGPL-3.0-only 许可证。使用方式与义务请查看仓库中的许可证全文。",
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
