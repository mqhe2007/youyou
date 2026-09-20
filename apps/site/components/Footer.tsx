import type { ReactNode } from "react";
import Link from "next/link";
import { GithubLogo, Globe } from "@phosphor-icons/react/dist/ssr";
import { LogoMark } from "./Logo";

const repo = "https://github.com/mqhe2007/youyou";
const site = "https://youyou.mengqinghe.com";

const productLinks = [
  { label: "日常使用", href: "#daily" },
  { label: "管理", href: "#admin" },
  { label: "部署", href: "#deploy" },
  { label: "隐私", href: "#privacy" },
];

const docLinks = [
  { label: "快速开始", href: "/quickstart" },
  { label: "客户端下载", href: `${repo}/releases` },
];

const legalLinks = [
  { label: "隐私政策", href: "/privacy" },
  { label: "服务条款", href: "/terms" },
  { label: "许可证", href: `${repo}/blob/main/LICENSE` },
];

const socialLinks = [
  { label: "GitHub", href: repo, icon: GithubLogo },
  { label: "官网", href: site, icon: Globe },
];

/** 站内路由用 Link，仓库文档与外链新窗口打开，首页锚点带前缀 */
function FooterLink({
  href,
  anchorPrefix,
  children,
}: {
  href: string;
  anchorPrefix: string;
  children: ReactNode;
}) {
  const className = "text-sm text-ink transition-colors hover:text-subtle";
  if (href.startsWith("http")) {
    return (
      <a href={href} target="_blank" rel="noreferrer" className={className}>
        {children}
      </a>
    );
  }
  if (href.startsWith("#")) {
    return (
      <a href={`${anchorPrefix}${href}`} className={className}>
        {children}
      </a>
    );
  }
  return (
    <Link href={href} className={className}>
      {children}
    </Link>
  );
}

function LinkColumn({
  title,
  links,
  anchorPrefix,
}: {
  title: string;
  links: { label: string; href: string }[];
  anchorPrefix: string;
}) {
  return (
    <div>
      <h3 className="text-sm font-semibold text-subtle">{title}</h3>
      <ul className="mt-4 space-y-3">
        {links.map((l) => (
          <li key={l.href}>
            <FooterLink href={l.href} anchorPrefix={anchorPrefix}>
              {l.label}
            </FooterLink>
          </li>
        ))}
      </ul>
    </div>
  );
}

type FooterProps = {
  /** 锚点前缀，与 Nav 同理：文档子页传 "/" */
  anchorPrefix?: string;
};

export function Footer({ anchorPrefix = "" }: FooterProps) {
  return (
    <footer className="border-t border-line">
      <div className="mx-auto w-full max-w-[1400px] px-4 py-16 md:px-8">
        <div className="grid gap-12 md:grid-cols-12">
          <div className="md:col-span-5">
            <a
              href={`${anchorPrefix}#top`}
              className="flex items-center gap-2.5"
            >
              <LogoMark />
              <span className="text-lg font-semibold tracking-tight">
                柚柚相册
              </span>
            </a>
            <p className="mt-3 text-sm text-subtle">轻松管理人生影相</p>
            <p className="mt-6 text-sm leading-relaxed text-subtle">
              自部署多用户照片管理应用。
            </p>
            <div className="mt-7 flex items-center gap-3">
              {socialLinks.map((s) => (
                <a
                  key={s.href}
                  href={s.href}
                  target="_blank"
                  rel="noreferrer"
                  className="social-link"
                  aria-label={s.label}
                >
                  <s.icon size={18} weight="regular" />
                </a>
              ))}
            </div>
          </div>
          <div className="grid grid-cols-2 gap-10 md:col-span-7 md:grid-cols-3">
            <LinkColumn
              title="产品"
              links={productLinks}
              anchorPrefix={anchorPrefix}
            />
            <LinkColumn
              title="文档"
              links={docLinks}
              anchorPrefix={anchorPrefix}
            />
            <LinkColumn
              title="法律"
              links={legalLinks}
              anchorPrefix={anchorPrefix}
            />
          </div>
        </div>
        <div className="mt-14 flex flex-col gap-3 border-t border-line pt-7 text-sm text-subtle md:flex-row md:items-center md:justify-between">
          <p>© 2026 柚柚相册</p>
          <p>Docker · Android 12+</p>
        </div>
      </div>
    </footer>
  );
}
