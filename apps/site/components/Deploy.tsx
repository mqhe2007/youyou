"use client";

import Image from "next/image";
import Link from "next/link";
import { useRef } from "react";
import { gsap } from "@/lib/gsap";
import { useSectionMotion } from "@/hooks/useSectionMotion";

const specs = [
  {
    k: "客户端平台",
    v: "Android 12（API 31）及以上",
  },
  {
    k: "服务端部署",
    v: "Docker 自部署，多架构镜像（AMD64 / ARM64）",
  },
  {
    k: "数据存储",
    v: "SQLite 元数据，挂载目录原位索引，不复制文件",
  },
  {
    k: "资源占用",
    v: "Rust 单服务，家庭 NAS 或旧电脑即可运行",
  },
];

const repo = "https://github.com/mqhe2007/youyou";
/** 站内路由：快速开始已不再是仓库里的 md */
const quickstart = "/quickstart";

export function Deploy() {
  const root = useRef<HTMLElement>(null);

  useSectionMotion(root, ({ reduce }) => {
    if (reduce) return;

    gsap.from(".deploy-copy", {
      y: 36,
      autoAlpha: 0,
      duration: 0.85,
      ease: "power3.out",
      scrollTrigger: {
        trigger: root.current,
        start: "top 78%",
      },
    });

    gsap.from(".deploy-spec", {
      y: 24,
      autoAlpha: 0,
      duration: 0.6,
      stagger: 0.08,
      ease: "power2.out",
      scrollTrigger: {
        trigger: ".deploy-specs",
        start: "top 82%",
      },
    });

    gsap.from(".deploy-code", {
      y: 20,
      autoAlpha: 0,
      duration: 0.7,
      ease: "power2.out",
      scrollTrigger: {
        trigger: ".deploy-code",
        start: "top 88%",
      },
    });
  });

  return (
    <section
      ref={root}
      id="deploy"
      className="mx-auto w-full max-w-[1400px] px-4 py-20 md:px-8 md:py-28"
    >
      <div className="grid gap-10 md:grid-cols-12 md:gap-14">
        <div className="deploy-copy md:col-span-5">
          <h2 className="text-4xl font-semibold tracking-tight md:text-5xl">
            部署到自己的服务器
          </h2>
          <p className="mt-5 leading-relaxed text-subtle">
            Rust 单服务加 SQLite，挂载目录原位索引，原文件不复制。家用 NAS
            或旧电脑就能跑。Docker 镜像支持 AMD64 和 ARM64。
          </p>
          <div className="mt-8 overflow-hidden rounded-2xl">
            <Image
              src="/images/home-server.jpg"
              alt="窗边搁板上的家用服务器与网络设备"
              width={1152}
              height={864}
              className="h-56 w-full object-cover md:h-72"
            />
          </div>
        </div>
        <div className="md:col-span-7">
          <div className="deploy-specs grid gap-5 sm:grid-cols-2">
            {specs.map((s) => (
              <div
                key={s.k}
                className="deploy-spec rounded-xl border border-line p-6"
              >
                <h3 className="text-sm font-semibold text-subtle">{s.k}</h3>
                <p className="mt-2 text-[17px] leading-snug">{s.v}</p>
              </div>
            ))}
          </div>
          <div
            className="deploy-code code-block mt-6"
            role="group"
            aria-label="部署命令"
          >
            <code>
              <span className="text-[#9a948c]">
                # 拉现成镜像启动服务端（AMD64 / ARM64）
              </span>
              <br />
              curl -O https://raw.githubusercontent.com/mqhe2007/youyou/main/deploy/docker-compose.yml
              <br />
              docker compose up -d
            </code>
          </div>
          <p className="mt-4 text-sm leading-relaxed text-subtle">
            服务端默认端口{" "}
            <span className="tabular font-medium text-ink">8989</span>
            ，管理页位于{" "}
            <span className="tabular font-medium text-ink">/admin</span>
            ，完整步骤见
            <Link className="link-quiet" href={quickstart}>
              快速开始
            </Link>
            。
          </p>
          <div className="mt-7 flex flex-wrap items-center gap-4">
            <Link className="btn btn-primary" href={quickstart}>
              开始部署
            </Link>
            <a
              className="link-quiet"
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
