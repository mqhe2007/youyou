import Image from "next/image";

export function Admin() {
  return (
    <section id="library" className="mx-auto w-full max-w-[1400px] px-4 py-20 md:px-8 md:py-28">
      <div className="grid items-center gap-10 md:grid-cols-12 md:gap-14">
        <div className="md:col-span-5">
          <p className="text-sm font-semibold tracking-[0.16em] text-subtle">接入原有照片库</p>
          <h2 className="mt-4 text-4xl font-semibold tracking-tight md:text-5xl">家里的照片，不必重新搬一遍</h2>
          <p className="mt-6 leading-relaxed text-subtle">
            把已有照片目录挂载给服务端，管理员为每位成员设置各自的媒体库目录。柚柚在原位建立索引，不会为了建索引再复制一整套原件。
          </p>
          <p className="mt-4 leading-relaxed text-subtle">
            目录绑定有权限和隔离规则；已有目录如何接入、换盘后如何重新扫描，都可以照着部署说明操作。
          </p>
          <a href="/quickstart#first-run" className="link-quiet mt-6 inline-block font-medium">查看目录接入步骤</a>
        </div>
        <figure className="md:col-span-7">
          <Image
            src="/images/planned/home-library-20260922.webp"
            alt="家中柜面上的小型服务器与旅行照片"
            width={1536}
            height={1024}
            sizes="(max-width: 768px) 100vw, 58vw"
            className="h-auto w-full rounded-2xl"
          />
        </figure>
      </div>
      <figure className="mt-14 overflow-hidden rounded-2xl border border-line bg-surface-alt">
        <Image
          src="/images/admin-library-demo.webp"
          alt="已索引的 library 目录与三张演示照片"
          width={1440}
          height={900}
          sizes="(max-width: 768px) 100vw, 100vw"
          className="h-auto w-full"
        />
      </figure>
    </section>
  );
}
