"use client";

import { useEffect, useState } from "react";
import { ArrowDown, ArrowSquareOut } from "@phosphor-icons/react/dist/ssr";
import { Nav } from "@/components/Nav";
import { Footer } from "@/components/Footer";

const releases = "https://github.com/mqhe2007/youyou/releases";

type Release = {
  tag_name: string;
  draft: boolean;
  prerelease: boolean;
  assets: { name: string; browser_download_url: string; digest?: string }[];
};

type Download = { url: string; version: string; latest: boolean };

const verifiedRelease: Download = {
  url: `${releases}/download/v0.3.1/youyou-0.3.1-10-d71a3ca.apk`,
  version: "v0.3.1",
  latest: false,
};

export default function DownloadPage() {
  const [download, setDownload] = useState<Download>(verifiedRelease);
  const [error, setError] = useState(false);

  useEffect(() => {
    fetch("https://api.github.com/repos/mqhe2007/youyou/releases/latest", {
      headers: { Accept: "application/vnd.github+json" },
    })
      .then((response) => {
        if (!response.ok) throw new Error("release unavailable");
        return response.json() as Promise<Release>;
      })
      .then((release) => {
        const apk = release.assets.find((asset) => /^youyou-.+\.apk$/.test(asset.name));
        const checksum = release.assets.some((asset) => asset.name === "SHA256SUMS");
        const expectedPrefix = `${releases}/download/${encodeURIComponent(release.tag_name)}/`;
        if (
          release.draft ||
          release.prerelease ||
          !apk ||
          !checksum ||
          !apk.digest?.startsWith("sha256:") ||
          !apk.browser_download_url.startsWith(expectedPrefix)
        ) {
          throw new Error("no verified client asset");
        }
        setDownload({ url: apk.browser_download_url, version: release.tag_name, latest: true });
      })
      .catch(() => setError(true));
  }, []);

  return (
    <>
      <Nav anchorPrefix="/" />
      <main className="mx-auto w-full max-w-[1000px] px-4 pb-24 pt-16 md:px-8 md:pb-32 md:pt-24">
        <p className="text-sm font-semibold tracking-[0.16em] text-subtle">柚柚相册</p>
        <h1 className="mt-4 text-4xl font-semibold tracking-tight md:text-6xl">下载客户端</h1>
        <p className="mt-5 max-w-[50ch] text-lg leading-relaxed text-subtle">
          有管理员发来的邀请二维码？安装客户端后扫码，就能进入自己的照片库。
        </p>
        <div className="mt-12 rounded-2xl border border-line bg-surface-alt p-7 md:p-10">
          <p className="text-sm font-semibold text-subtle">当前可用平台</p>
          <h2 className="mt-2 text-2xl font-semibold">Android 客户端</h2>
          <p className="mt-3 leading-relaxed text-subtle">支持 Android 12 及以上。安装前请确认设备可以访问你的服务器。</p>
          <a href={download.url} className="btn btn-primary mt-7" rel="noopener noreferrer">
            {download.latest ? "下载当前正式版" : "下载已核验正式版"} <ArrowDown size={17} weight="bold" aria-hidden />
          </a>
          <p className="mt-3 text-sm text-subtle">版本 {download.version} · 文件来自项目官方 GitHub Release</p>
          {error && <p className="mt-3 text-sm text-subtle">暂时无法核对是否有更新，可在官方发布页查看其他版本。</p>}
          <a href={releases} target="_blank" rel="noreferrer" className="link-quiet mt-5 inline-flex items-center gap-1.5 text-sm">
            查看官方发布页 <ArrowSquareOut size={15} aria-hidden />
          </a>
        </div>
        <p className="mt-8 text-sm leading-relaxed text-subtle">管理员尚未准备好照片库？可先在客户端浏览本机照片，稍后再扫码接入。</p>
      </main>
      <Footer anchorPrefix="/" />
    </>
  );
}
