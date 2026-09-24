import Image from "next/image";

export function Onboard() {
  return (
    <section id="family" className="bg-surface-muted py-20 md:py-28">
      <div className="mx-auto grid w-full max-w-[1400px] items-center gap-10 px-4 md:grid-cols-12 md:gap-14 md:px-8">
        <div className="md:col-span-6">
          <p className="text-sm font-semibold tracking-[0.16em] text-subtle">家人接入</p>
          <h2 className="mt-4 text-4xl font-semibold tracking-tight md:text-5xl">共用一台服务器，各有私密照片库</h2>
          <p className="mt-6 leading-relaxed text-subtle">
            管理员为成员创建账号、绑定各自目录，再从管理页生成邀请二维码。成员在客户端扫码接入，就能浏览自己的照片库。
          </p>
          <p className="mt-4 leading-relaxed text-subtle">
            每个人的媒体、收藏和标签彼此隔离。共用服务器不等于共享照片，也不会自动看见家人的内容。
          </p>
        </div>
        <figure className="md:col-span-6">
          <Image
            src="/images/planned/family-libraries-20260924.webp"
            alt="一台家用服务器，左右各放着一份互不相通的照片盒"
            width={1152}
            height={864}
            sizes="(max-width: 768px) 100vw, 50vw"
            className="h-auto w-full rounded-2xl"
          />
        </figure>
      </div>
    </section>
  );
}
