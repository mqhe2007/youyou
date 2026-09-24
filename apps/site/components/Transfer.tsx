import Image from "next/image";

const states = [
  {
    title: "选择本机照片",
    detail: "这张照片还只在手机上，详情页提供上传入口。",
    src: "/images/app-local-detail-demo.webp",
    alt: "真实客户端的仅本机照片详情，底部显示上传操作",
  },
  {
    title: "手动上传",
    detail: "点击上传后，界面显示正在传输到服务器。",
    src: "/images/app-upload-progress-demo.webp",
    alt: "同一张演示照片正在上传到服务器的真实界面",
  },
  {
    title: "查看结果",
    detail: "后台活动记录上传 1 项，成功 1 项、失败 0 项。",
    src: "/images/transfer-result-demo.webp",
    alt: "真实客户端后台活动显示上传一项到服务器，成功一项",
  },
];

export function Transfer() {
  return (
    <section id="transfer" className="mx-auto w-full max-w-[1400px] px-4 py-20 md:px-8 md:py-28">
      <p className="text-sm font-semibold tracking-[0.16em] text-subtle">按需传输</p>
      <h2 className="mt-4 text-4xl font-semibold tracking-tight md:text-5xl">想留在哪边，由你决定</h2>
      <p className="mt-5 max-w-[65ch] leading-relaxed text-subtle">
        选中本机照片，手动上传到服务器；选中服务器照片，手动下载到手机。每批任务会显示进度与最近结果，失败项可以按提示重试。柚柚不会自动备份新照片。
      </p>
      <div className="mt-12 grid gap-8 md:grid-cols-3">
        {states.map((state, index) => (
          <figure key={state.title}>
            <div className="mx-auto max-w-[300px] overflow-hidden rounded-[28px] border border-line shadow-lg">
              <Image src={state.src} alt={state.alt} width={1280} height={2856} sizes="(max-width: 768px) 80vw, 300px" className="h-auto w-full" />
            </div>
            <figcaption className="mt-5">
              <span className="tabular text-sm font-semibold text-subtle">{String(index + 1).padStart(2, "0")}</span>
              <h3 className="mt-1 text-xl font-semibold">{state.title}</h3>
              <p className="mt-2 leading-relaxed text-subtle">{state.detail}</p>
            </figcaption>
          </figure>
        ))}
      </div>
    </section>
  );
}
