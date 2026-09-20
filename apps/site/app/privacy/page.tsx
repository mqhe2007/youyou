import type { Metadata } from "next";
import Link from "next/link";
import {
  CodeInline,
  DocPage,
  Note,
  P,
  Table,
  UL,
  type DocSection,
} from "@/components/DocPage";

export const metadata: Metadata = {
  title: "隐私政策 | 柚柚相册",
  description:
    "柚柚相册的数据与权限说明：照片存在哪里、每项权限用来做什么、删除之后还能不能找回来。项目方不提供云端存储，也不追踪你。",
};

const site = "https://youyou.mengqinghe.com";
const repo = "https://github.com/mqhe2007/youyou";

const sections: DocSection[] = [
  {
    id: "about",
    title: "关于本政策",
    body: (
      <>
        <P>柚柚相册（英文名 youyou）是一个自部署的多用户照片管理应用，由 Android 客户端和你自己部署的服务端两部分组成。</P>
        <P>
          <strong>项目方不提供云端存储、不提供托管服务、不运营你的数据。</strong>
          你的照片和元数据存放在自己的设备和你自己装的服务器上。所以这一页主要说清客户端的行为边界，以及它会访问哪些数据。
        </P>
        <P>运营者：孟庆贺（个人开发者）。联系方式见本页末尾。</P>
      </>
    ),
  },
  {
    id: "not-collected",
    title: "我们不收集什么",
    body: (
      <UL>
        <li>
          <strong>不接入第三方统计、广告或用户画像 SDK。</strong>
        </li>
        <li>
          <strong>不建立项目官方账号体系。</strong>客户端不需要手机号、邮箱或社交账号注册；设备与服务端的连接关系由你部署的服务端和设备配对码建立。
        </li>
        <li>
          <strong>不把照片上传到项目官方云端。</strong>客户端只会与你填写或扫码得到的服务端地址通信。
        </li>
        <li>
          <strong>不上传崩溃日志、使用行为或设备标识到项目方。</strong>
        </li>
      </UL>
    ),
  },
  {
    id: "where",
    title: "数据存放在哪里",
    body: (
      <>
        <Table
          head={["数据", "存放位置", "说明"]}
          label="数据存放位置"
          rows={[
            [
              "照片与视频原件",
              "手机本机相册、你部署的服务端、或你配置的媒体目录",
              "服务端对挂载目录原位索引，不复制原文件；通过客户端上传的照片，会保存到该用户的媒体库目录中",
            ],
            [
              "服务端地址、设备令牌",
              "客户端本地存储",
              "用于连接你部署的服务端",
            ],
            [
              "媒体索引数据库",
              "客户端本地（SQLite）",
              "本机媒体的元数据与缩略图索引",
            ],
            [
              "缩略图与缓存预览",
              "客户端本地缓存目录",
              "可随时清理，清理后可重新生成",
            ],
            [
              "媒体索引、标签、收藏、设备配对记录、运行日志",
              "你部署的服务端（SQLite）",
              "由你自行运维与备份",
            ],
            [
              "服务端回收站",
              "你部署的服务端，默认在运行时目录的 trash/ 下",
              "删除的原件先移到这里，保留 30 天",
            ],
          ]}
        />
        <P>都在你自己的目录里。换机器、换盘的时候，直接访问和复制就行。</P>
      </>
    ),
  },
  {
    id: "version",
    title: "版本与兼容性",
    body: (
      <>
        <P>这些决定了客户端在你手机上能用到哪些系统能力：</P>
        <UL>
          <li>自 <CodeInline>0.2.0 (8)</CodeInline> 起，客户端 <CodeInline>minSdk</CodeInline> 为 31、<CodeInline>targetSdk</CodeInline> 为 37。</li>
          <li>Android 11（API 30）及以下设备不再支持安装或升级。Android 12（API 31）是支持边界，Android 12L 到 17 是适用范围。</li>
          <li>Android 12 与 12L 用 <CodeInline>READ_EXTERNAL_STORAGE</CodeInline> 读取媒体；Android 13 起改用分开的图片、视频权限；Android 14 起支持只开放部分照片。</li>
          <li>HarmonyOS 4.2 基于 Android 12，在支持范围内。</li>
          <li>支持范围内的本机删除统一走系统回收能力；针对 API 24-30 的永久删除和旧存储权限兼容逻辑已经移除。</li>
        </UL>
      </>
    ),
  },
  {
    id: "permissions",
    title: "权限用途",
    body: (
      <>
        <P>客户端申请的每一项权限都对应一项明确功能。拒绝某个权限只会关闭对应的功能，不影响其他功能：</P>
        <UL>
          <li>
            <strong>照片和视频权限</strong>：扫描、显示和管理手机媒体。
          </li>
          <li>
            <strong>相机权限</strong>：扫描管理端生成的连接二维码。
          </li>
          <li>
            <strong>通知权限</strong>：展示上传、下载、扫描等后台任务进度。
          </li>
          <li>
            <strong>本地网络权限</strong>：连接你部署的柚柚服务端。
          </li>
        </UL>
      </>
    ),
  },
  {
    id: "sharing",
    title: "数据共享与对外提供",
    body: (
      <>
        <P>客户端不向任何第三方共享数据。客户端与外部发生的全部通信，目标都是你自行填写或扫码得到的那个服务端地址。</P>
        <P>如果你用的服务端地址由他人提供（比如别人帮你部署的一套），那个服务端的运营者可以看到你上传到该服务端的媒体与元数据。这是自部署形态本身的属性，请只连接你信任的服务端。</P>
      </>
    ),
  },
  {
    id: "deletion",
    title: "数据删除",
    body: (
      <>
        <P>四类数据的删除结果不一样，动手前看清楚：</P>
        <UL>
          <li>
            <strong>客户端本地数据</strong>：卸载应用或清理应用缓存，会删除对应的本地数据（含设备令牌、媒体索引、缩略图与缓存），<strong>不会删除服务端原件</strong>。
          </li>
          <li>
            <strong>本机媒体</strong>：删除使用系统的回收能力。多数设备能在系统相册的「最近删除」里找回，但我们不保证每台设备都提供可见的恢复入口，这取决于系统和厂商的实现。
          </li>
          <li>
            <strong>服务端媒体</strong>：删除原件会先进入服务端回收站，<strong>保留期固定 30 天，不可配置</strong>。可以配置的只有回收站的目录位置 <CodeInline>YOUYOU_TRASH_DIR</CodeInline>。到期后自动清除；手动彻底删除或清空回收站之后无法恢复。
          </li>
          <li>
            <strong>断开连接</strong>：在客户端断开与服务端的连接，会清除本机上关于远程数据的投影、标签、同步记录与缓存预览，不影响系统相册里的照片。
          </li>
        </UL>
        <Note title="动手之前">
          涉及永久删除、清理缓存、更换服务端的操作，先确认这些内容在别处还有一份。数据库怎么备份和恢复，见
          <Link href="/quickstart" className="link-quiet">
            快速开始
          </Link>
          。
        </Note>
      </>
    ),
  },
  {
    id: "rights",
    title: "你的权利",
    body: (
      <>
        <P>因为是自部署形态，服务端数据完全由你（或你信任的服务端运营者）控制：</P>
        <UL>
          <li>
            <strong>查阅与导出</strong>：服务端数据就在你部署的运行时目录里，数据库和媒体目录可以直接访问，也可以直接备份。
          </li>
          <li>
            <strong>更正与删除</strong>：通过客户端或服务端管理页执行，删除的后果见上一节。
          </li>
          <li>
            <strong>撤回授权</strong>：随时可以在系统设置里关掉相机、通知、照片和视频权限；也可以卸载应用，清掉全部客户端本地数据。
          </li>
        </UL>
      </>
    ),
  },
  {
    id: "release",
    title: "发布边界",
    body: (
      <UL>
        <li>客户端通过项目渠道直接分发签名 APK，不上架应用商店。请只从你信任的渠道下载安装包。</li>
        <li>这一页说的是客户端的数据与权限行为。服务端由使用者自行部署与运维，服务端那一侧的数据处理由部署者负责。</li>
      </UL>
    ),
  },
  {
    id: "minors",
    title: "未成年人",
    body: <P>本应用不是为未成年人设计的，也不会有意收集未成年人的个人信息。</P>,
  },
  {
    id: "changes",
    title: "政策变更",
    body: <P>本政策如有变更，会在本页更新。重大变更会在客户端内提示。</P>,
  },
  {
    id: "contact",
    title: "联系我们",
    body: (
      <P>
        对本政策有疑问，可以通过项目网站{" "}
        <a href={site} target="_blank" rel="noreferrer" className="link-quiet">
          youyou.mengqinghe.com
        </a>{" "}
        或{" "}
        <a href={repo} target="_blank" rel="noreferrer" className="link-quiet">
          GitHub 仓库
        </a>
        联系。使用约定见
        <Link href="/terms" className="link-quiet">
          服务条款
        </Link>
        。
      </P>
    ),
  },
];

export default function PrivacyPage() {
  return (
    <DocPage
      current="/privacy"
      title="隐私政策"
      lead={
        <>
          <p>柚柚相册是自部署的：照片存在你自己的设备和你自己装的服务器上，项目方不经手你的数据，也不追踪你。</p>
          <p className="mt-3">这一页说明客户端会访问什么、每项权限用来做什么、删掉的东西还能不能找回来。</p>
        </>
      }
      sections={sections}
    />
  );
}
