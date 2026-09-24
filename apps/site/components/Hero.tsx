import Image from "next/image";
import Link from "next/link";
import { ArrowRight, HouseLine, ShieldCheck, UsersThree } from "@phosphor-icons/react/dist/ssr";

const points = [
  { icon: HouseLine, label: "连接自己的照片库" },
  { icon: ShieldCheck, label: "原件由自己保管" },
  { icon: UsersThree, label: "家人各有私密空间" },
];

export function Hero() {
  return (
    <section id="top" className="mx-auto grid w-full max-w-[1400px] items-center gap-10 px-4 pb-20 pt-14 md:grid-cols-12 md:gap-8 md:px-8 md:pb-28 md:pt-20">
      <div className="md:col-span-6 lg:col-span-7">
        <p className="mb-5 text-sm font-semibold tracking-[0.16em] text-subtle">自有照片库的手机入口</p>
        <h1 className="text-[clamp(3rem,5.6vw,5.5rem)] font-semibold leading-[1.08] tracking-[-0.055em] md:text-[39px] lg:text-[clamp(3rem,4.5vw,4rem)] lg:whitespace-nowrap">
          轻松管理<span className="block lg:inline">人生影相<span className="text-accent">。</span></span>
        </h1>
        <p className="mt-7 text-xl leading-[1.55] text-subtle md:text-2xl">
          手机里的新照片，家中珍藏的旧照片，一起看。
        </p>
        <p className="mt-4 leading-relaxed text-subtle">
          柚柚连接手机与自建照片库。保留原有目录，统一浏览，按需上传和下载。
        </p>
        <div className="mt-9 flex flex-wrap gap-3">
          <a href="/download" className="btn btn-primary group">
            下载客户端 <ArrowRight size={16} weight="bold" className="cta-icon" />
          </a>
          <Link href="/quickstart" className="btn btn-ghost group">
            了解如何使用 <ArrowRight size={16} weight="bold" className="cta-icon" />
          </Link>
        </div>
        <ul className="mt-12 hidden max-w-[590px] grid-cols-3 gap-4 border-t border-line pt-6 text-sm text-subtle lg:grid">
          {points.map(({ icon: Icon, label }) => (
            <li key={label} className="flex items-center gap-2.5">
              <Icon size={23} weight="regular" className="shrink-0 text-ink" aria-hidden />
              <span>{label}</span>
            </li>
          ))}
        </ul>
      </div>
      <figure className="relative flex min-w-0 flex-col items-center md:col-span-6 lg:col-span-5">
        <div className="relative left-[-40px] hidden aspect-[916/887] w-[min(50vw,680px)] shrink-0 lg:block">
          <Image
            src="/images/hero-reference-stage.png"
            alt=""
            aria-hidden
            width={916}
            height={887}
            priority
            sizes="(max-width: 1360px) 50vw, 680px"
            className="h-full w-full mix-blend-darken"
          />
          <div aria-hidden className="pointer-events-none absolute inset-x-0 bottom-0 h-[6%] bg-gradient-to-b from-transparent to-surface" />
          {/* The reference includes a cropped headline and a concept UI. Keep its
              original decoration, then cover both with the live product capture. */}
          <div aria-hidden className="absolute left-0 top-[18%] h-[11%] w-[6.2%] bg-surface" />
          <div className="absolute left-[28.4%] top-[5.35%] h-[86.7%] w-[41.9%] overflow-hidden rounded-[7.5%] bg-white">
            <Image
              src="/images/app-timeline-filled.webp"
              alt="柚柚相册真实时间线：照片格子中，云朵表示仅服务器、手机表示仅本机；无徽标的照片两端都有"
              width={1280}
              height={2566}
              priority
              fetchPriority="high"
              sizes="344px"
              className="h-full w-full object-cover"
            />
          </div>
          <span aria-hidden className="absolute left-[48.35%] top-[5.95%] aspect-square w-[2.05%] rounded-full border-[2px] border-[#333941] bg-[#0b0e13]" />
        </div>
        <div className="relative flex w-full flex-col items-center lg:hidden">
          <Image
            src="/images/hero-warm-halo.webp"
            alt=""
            aria-hidden
            width={1254}
            height={1254}
            sizes="(max-width: 768px) 100vw, 620px"
            className="pointer-events-none absolute left-0 top-[5%] z-0 w-full max-w-none mix-blend-multiply"
          />
          <div className="relative z-20 w-[min(77vw,344px)] rounded-[42px] border-[8px] border-[#292825] bg-white p-[3px] shadow-[0_30px_70px_rgba(28,27,26,0.18)]">
            <Image
              src="/images/app-timeline-filled.webp"
              alt="柚柚相册真实时间线：照片格子中，云朵表示仅服务器、手机表示仅本机；无徽标的照片两端都有"
              width={1280}
              height={2566}
              priority
              fetchPriority="high"
              sizes="(max-width: 768px) 77vw, 344px"
              className="h-auto w-full rounded-[31px]"
            />
          </div>
        </div>
      </figure>
    </section>
  );
}
