"use client";

import { useRef } from "react";
import { gsap } from "@/lib/gsap";
import { useSectionMotion } from "@/hooks/useSectionMotion";

const rows = [
  {
    q: "数据放在哪里？",
    a: "服务器原件留在你自己的照片目录里；本机原件和下载到手机的照片也会保留在手机。缩略图缓存只是为了浏览，不能当作原件备份。",
  },
  {
    q: "会上传到第三方吗？",
    a: "柚柚不提供第三方照片云。客户端连接你指定的服务器；你自行决定服务器所在网络和传输路径。",
  },
  {
    q: "会修改我的照片吗？",
    a: "不会。索引只读取原件，上传按原文件保存。标签、收藏和拍摄信息记在数据库里，缩略图单独缓存，都不写回照片。",
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
          照片放在你掌控的位置
        </h2>
        <p className="mt-5 leading-relaxed text-subtle">
          自部署的服务端管理自己的照片目录；离线时仍可浏览手机上的原件，服务器照片是否可见取决于已有缓存。
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
