import Link from "next/link";

const paths = [
  {
    title: "我要准备照片库",
    role: "管理员",
    steps: ["准备一台常开机器与照片目录", "启动服务端并创建管理员", "在管理页建用户、绑定目录", "生成邀请二维码"],
    href: "/quickstart#prepare",
    action: "查看部署步骤",
  },
  {
    title: "我已经收到邀请",
    role: "成员",
    steps: ["下载并安装客户端", "打开客户端扫描邀请二维码", "浏览自己的第一张照片", "按需上传或下载"],
    href: "/download",
    action: "下载客户端",
  },
];

export function Deploy() {
  return (
    <section id="start" className="mx-auto w-full max-w-[1400px] px-4 py-20 md:px-8 md:py-28">
      <p className="text-sm font-semibold tracking-[0.16em] text-subtle">开始使用</p>
      <h2 className="mt-4 text-4xl font-semibold tracking-tight md:text-5xl">从你的角色开始</h2>
      <p className="mt-5 max-w-[62ch] leading-relaxed text-subtle">管理员负责服务器和目录，受邀成员只需安装客户端扫码。无需让家人先读部署文档。</p>
      <div className="mt-12 grid gap-5 md:grid-cols-2">
        {paths.map((path) => (
          <div key={path.role} className="rounded-2xl border border-line bg-surface-alt p-7 md:p-9">
            <p className="text-sm font-semibold text-subtle">{path.role}</p>
            <h3 className="mt-3 text-2xl font-semibold">{path.title}</h3>
            <ol className="mt-7 space-y-4">
              {path.steps.map((step, i) => (
                <li key={step} className="flex gap-4 leading-relaxed">
                  <span className="tabular text-sm font-semibold text-subtle">{String(i + 1).padStart(2, "0")}</span>
                  <span>{step}</span>
                </li>
              ))}
            </ol>
            <Link href={path.href} className="btn btn-primary mt-8">{path.action}</Link>
          </div>
        ))}
      </div>
    </section>
  );
}
