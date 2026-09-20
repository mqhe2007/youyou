/** @type {import('next').NextConfig} */
const nextConfig = {
  // 纯静态导出：产物是可直接由 nginx 托管的 HTML/JS，服务器上不需要 Node 运行时。
  // 代价是没有任何服务端取数，所以 star 数改由浏览器端 fetch（见 GitHubStars.tsx）。
  output: "export",
  images: { unoptimized: true },
};

export default nextConfig;
