import type { ReactNode } from "react";
import Link from "next/link";
import { ArrowLeft } from "@phosphor-icons/react/dist/ssr";
import { Nav } from "./Nav";
import { Footer } from "./Footer";

const container = "mx-auto w-full max-w-[1400px] px-4 md:px-8";

/** 站内三篇文档：互相引用时走这里，不再指向仓库里的 md */
export const docLinks = [
  { href: "/quickstart", label: "快速开始" },
  { href: "/privacy", label: "隐私政策" },
  { href: "/terms", label: "服务条款" },
];

export type DocSection = {
  id: string;
  title: string;
  body: ReactNode;
};

type DocPageProps = {
  /** 当前页的 href，用于文档标签高亮 */
  current: string;
  title: string;
  lead: ReactNode;
  sections: DocSection[];
};

export function DocPage({ current, title, lead, sections }: DocPageProps) {
  return (
    <>
      <Nav anchorPrefix="/" />
      <main className="bg-surface">
        <div className={container}>
          <div className="pb-10 pt-12 md:pb-12 md:pt-16">
            <Link
              href="/"
              className="inline-flex items-center gap-1.5 text-sm text-subtle transition-colors hover:text-ink"
            >
              <ArrowLeft size={14} weight="bold" />
              返回产品介绍
            </Link>
            <h1 className="mt-6 text-4xl font-semibold tracking-tight md:text-5xl">
              {title}
            </h1>
            <div className="mt-5 max-w-[62ch] text-[17px] leading-relaxed text-subtle">
              {lead}
            </div>
            <ul className="mt-8 flex flex-wrap gap-2" aria-label="站内文档">
              {docLinks.map((d) => {
                const active = d.href === current;
                return (
                  <li key={d.href}>
                    <Link
                      href={d.href}
                      aria-current={active ? "page" : undefined}
                      className={
                        active
                          ? "inline-flex rounded-full border border-line bg-surface-muted px-4 py-1.5 text-sm font-semibold"
                          : "inline-flex rounded-full border border-line px-4 py-1.5 text-sm text-subtle transition-colors hover:border-ink hover:text-ink"
                      }
                    >
                      {d.label}
                    </Link>
                  </li>
                );
              })}
            </ul>
          </div>
        </div>

        <div className={container}>
          <div className="grid gap-10 border-t border-line pb-20 md:grid-cols-12 md:gap-12 md:pb-28">
            <aside className="md:sticky md:top-24 md:col-span-3 md:self-start">
              <h2 className="text-sm font-semibold text-subtle">本页内容</h2>
              <ol className="mt-4 space-y-2.5 border-l border-line pl-4 text-sm">
                {sections.map((s, i) => (
                  <li key={s.id}>
                    <a
                      href={`#${s.id}`}
                      className="text-subtle transition-colors hover:text-ink"
                    >
                      <span className="tabular mr-1.5 text-xs">{i + 1}</span>
                      {s.title}
                    </a>
                  </li>
                ))}
              </ol>
            </aside>
            <div className="md:col-span-9">
              {sections.map((s, i) => (
                <section
                  key={s.id}
                  id={s.id}
                  className={
                    i === 0
                      ? "scroll-mt-24"
                      : "mt-14 scroll-mt-24 border-t border-line pt-14"
                  }
                >
                  <h2 className="text-2xl font-semibold tracking-tight md:text-[28px]">
                    {s.title}
                  </h2>
                  <div className="mt-4 max-w-[68ch]">{s.body}</div>
                </section>
              ))}
            </div>
          </div>
        </div>
      </main>
      <Footer anchorPrefix="/" />
    </>
  );
}

/* —— 正文排版原语：只组合 globals.css 里已有的 token —— */

export function P({ children }: { children: ReactNode }) {
  return <p className="first:mt-0 mt-5 leading-relaxed">{children}</p>;
}

export function UL({ children }: { children: ReactNode }) {
  return (
    <ul className="first:mt-0 mt-5 list-disc space-y-2.5 leading-relaxed pl-5 marker:text-subtle">
      {children}
    </ul>
  );
}

export function H3({ children }: { children: ReactNode }) {
  return (
    <h3 className="first:mt-0 mt-10 text-lg font-semibold tracking-tight">
      {children}
    </h3>
  );
}

export function CodeInline({ children }: { children: ReactNode }) {
  return (
    <code className="rounded-md border border-line bg-surface-alt px-1.5 py-0.5 font-mono text-[0.85em]">
      {children}
    </code>
  );
}

export function Comment({ children }: { children: ReactNode }) {
  return <span className="text-[#9a948c]">{children}</span>;
}

/** 命令行 / 目录树：每行一条，保留缩进 */
export function Code({
  lines,
  label,
}: {
  lines: ReactNode[];
  label: string;
}) {
  return (
    <div className="code-block mt-5" role="group" aria-label={label}>
      <code>
        {lines.map((line, i) => (
          <span key={i} className="block whitespace-pre">
            {line}
          </span>
        ))}
      </code>
    </div>
  );
}

export function Table({
  head,
  rows,
  label,
}: {
  head: string[];
  rows: ReactNode[][];
  label: string;
}) {
  return (
    <div className="mt-5 overflow-x-auto rounded-2xl border border-line">
      <table className="w-full border-collapse text-left text-[15px]">
        <caption className="sr-only">{label}</caption>
        <thead>
          <tr className="border-b border-line bg-surface-muted">
            {head.map((h) => (
              <th
                key={h}
                className="px-4 py-3 text-sm font-semibold text-subtle"
                scope="col"
              >
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {rows.map((row, i) => (
            <tr key={i}>
              {row.map((cell, j) => (
                <td key={j} className="px-4 py-3 align-top leading-relaxed">
                  {cell}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function Note({
  title,
  children,
}: {
  title?: string;
  children: ReactNode;
}) {
  return (
    <div className="mt-5 rounded-xl border border-line bg-surface-muted p-5 leading-relaxed">
      {title ? <p className="font-semibold">{title}</p> : null}
      <div className={title ? "mt-1.5" : undefined}>{children}</div>
    </div>
  );
}
