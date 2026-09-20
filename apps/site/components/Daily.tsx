"use client";

import Image from "next/image";
import { useRef } from "react";
import { gsap } from "@/lib/gsap";
import { useSectionMotion } from "@/hooks/useSectionMotion";

export function Daily() {
  const root = useRef<HTMLElement>(null);

  useSectionMotion(root, ({ reduce }) => {
    if (reduce) return;

    gsap.from(".daily-heading", {
      y: 36,
      autoAlpha: 0,
      duration: 0.85,
      ease: "power3.out",
      scrollTrigger: {
        trigger: root.current,
        start: "top 78%",
      },
    });

    gsap.from(".daily-figure", {
      y: 48,
      autoAlpha: 0,
      duration: 0.9,
      stagger: 0.12,
      ease: "power3.out",
      scrollTrigger: {
        trigger: ".daily-figures",
        start: "top 80%",
      },
    });
  });

  return (
    <section
      ref={root}
      id="daily"
      className="mx-auto w-full max-w-[1400px] px-4 py-20 md:px-8 md:py-28"
    >
      <div className="daily-heading">
        <h2 className="text-4xl font-semibold tracking-tight md:text-5xl">
          本机和云端，一条时间线
        </h2>
        <p className="mt-5 text-lg leading-relaxed text-subtle">
          拍了新照一眼知道还没备份；在外面也能看到家里的库，需要时再下载回来。
        </p>
      </div>

      <div className="daily-figures mt-14 grid gap-8 md:mt-16 md:grid-cols-12">
        <figure className="daily-figure md:col-span-7">
          <div className="overflow-hidden rounded-2xl">
            <Image
              src="/images/timeline-real.png"
              alt="柚柚相册合并时间线真实界面，缩略图标注本机与服务器"
              width={1280}
              height={2275}
              className="h-[340px] w-full object-cover object-top md:h-[460px]"
            />
          </div>
          <figcaption className="mt-5">
            <h3 className="text-lg font-semibold">合并时间线</h3>
            <p className="mt-1.5 max-w-[46ch] leading-relaxed text-subtle">
              本机索引与服务器投影合在一起浏览，缩略图标注存放位置，年月刻度快速导航。
            </p>
          </figcaption>
        </figure>
        <figure className="daily-figure md:col-span-5">
          <div className="overflow-hidden rounded-2xl">
            <Image
              src="/images/backup-transfer.jpg"
              alt="手机与家用服务器之间的照片备份场景"
              width={864}
              height={1152}
              className="h-[340px] w-full object-cover md:h-[460px]"
            />
          </div>
          <figcaption className="mt-5">
            <h3 className="text-lg font-semibold">双向备份</h3>
            <p className="mt-1.5 max-w-[46ch] leading-relaxed text-subtle">
              选中照片就能传到服务器，也能从服务器下载回手机。
            </p>
          </figcaption>
        </figure>
      </div>
    </section>
  );
}
