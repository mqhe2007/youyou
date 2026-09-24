import { Cloud, DeviceMobile, ArrowsLeftRight } from "@phosphor-icons/react/dist/ssr";

const locations = [
  { icon: DeviceMobile, name: "仅本机", detail: "照片还在手机里；需要时手动上传到服务器。" },
  { icon: Cloud, name: "仅服务器", detail: "家中照片在服务器上；需要时手动下载到手机。" },
  { icon: ArrowsLeftRight, name: "两端都有", detail: "手机和服务器各有一份，时间线只显示一张。" },
];

export function Daily() {
  return (
    <section id="daily" className="bg-surface-muted py-20 md:py-28">
      <div className="mx-auto w-full max-w-[1400px] px-4 md:px-8">
        <p className="text-sm font-semibold tracking-[0.16em] text-subtle">日常浏览</p>
        <h2 className="mt-4 text-4xl font-semibold tracking-tight md:text-5xl">一条时间线，看见两端照片</h2>
        <p className="mt-5 max-w-[60ch] text-lg leading-relaxed text-subtle">
          新拍的照片和家里的旧照片一起浏览。照片所在位置清楚可见，传输由你自己决定。
        </p>
        <div className="mt-12 grid gap-4 md:grid-cols-3">
          {locations.map(({ icon: Icon, name, detail }) => (
            <div key={name} className="rounded-2xl border border-line bg-surface-alt p-7">
              <Icon size={28} weight="regular" aria-hidden />
              <h3 className="mt-5 text-xl font-semibold">{name}</h3>
              <p className="mt-2 leading-relaxed text-subtle">{detail}</p>
            </div>
          ))}
        </div>
        <p className="mt-5 text-sm leading-relaxed text-subtle">真实客户端中，手机图标表示仅本机，云朵图标表示仅服务器；没有位置徽标表示两端都有。</p>
      </div>
    </section>
  );
}
