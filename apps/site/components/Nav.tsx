import { LogoMark } from "./Logo";
import { GitHubStars } from "./GitHubStars";

const links = [
  { href: "#daily", label: "日常使用" },
  { href: "#library", label: "原有照片库" },
  { href: "#start", label: "开始使用" },
  { href: "#faq", label: "常见问题" },
  { href: "/quickstart", label: "文档" },
];

type NavProps = {
  /**
   * 锚点前缀。首页留空；文档子页传 "/"，
   * 否则 `#daily` 这类链接在子页上跳不回首页对应区块。
   */
  anchorPrefix?: string;
};

export function Nav({ anchorPrefix = "" }: NavProps) {
  return (
    <header className="sticky top-0 z-50 border-b border-line bg-surface/90 backdrop-blur">
      <nav className="mx-auto flex h-16 w-full max-w-[1400px] items-center justify-between gap-4 px-4 md:px-8">
        <a href={`${anchorPrefix}#top`} className="flex shrink-0 items-center gap-2.5">
          <LogoMark />
          <span className="text-[17px] font-semibold tracking-tight">
            柚柚相册
          </span>
        </a>
        <div className="flex min-w-0 items-center gap-3 md:gap-6">
          <ul className="hidden items-center gap-8 md:flex">
            {links.map((l) => (
              <li key={l.href}>
                <a
                  href={l.href.startsWith("/") ? l.href : `${anchorPrefix}${l.href}`}
                  className="text-sm text-subtle transition-colors hover:text-ink"
                >
                  {l.label}
                </a>
              </li>
            ))}
          </ul>
          <a
            href="/quickstart"
            className="text-sm text-subtle transition-colors hover:text-ink md:hidden"
          >
            文档
          </a>
          <span className="hidden lg:inline-flex"><GitHubStars /></span>
          <a
            href="/download"
            className="btn btn-primary btn-sm shrink-0"
          >
            下载客户端
          </a>
        </div>
      </nav>
    </header>
  );
}
