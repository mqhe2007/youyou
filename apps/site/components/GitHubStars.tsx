"use client";

import { Star } from "@phosphor-icons/react/dist/ssr";
import { useEffect, useState } from "react";

const repo = "https://github.com/mqhe2007/youyou";
const api = "https://api.github.com/repos/mqhe2007/youyou";

function formatStars(n: number) {
  if (n >= 1000) {
    const k = n / 1000;
    return `${k >= 10 ? Math.round(k) : k.toFixed(1).replace(/\.0$/, "")}k`;
  }
  return String(n);
}

export function GitHubStars() {
  const [stars, setStars] = useState<number | null>(null);

  // 静态导出的站点没有服务端可跑定时取数，只能在浏览器里取：
  // 未登录的 GitHub API 限额按访问者 IP 计，不会整站共用一份额度。
  useEffect(() => {
    let cancelled = false;
    fetch(api, { headers: { Accept: "application/vnd.github+json" } })
      .then((res) => (res.ok ? res.json() : null))
      .then((data: { stargazers_count?: number } | null) => {
        if (!cancelled && typeof data?.stargazers_count === "number") {
          setStars(data.stargazers_count);
        }
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, []);

  const label = stars == null ? "Star" : formatStars(stars);

  return (
    <a
      href={repo}
      target="_blank"
      rel="noreferrer"
      className="github-stars group"
      aria-label={
        stars == null
          ? "在 GitHub 上查看柚柚相册"
          : `GitHub ${stars} stars，打开仓库`
      }
    >
      <span className="github-stars-mark" aria-hidden="true">
        <Star size={14} weight="fill" className="github-stars-icon" />
      </span>
      <span className="github-stars-meta">
        <span className="github-stars-caption">GitHub</span>
        <span className="github-stars-count tabular">{label}</span>
      </span>
    </a>
  );
}
