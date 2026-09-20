import type { Metadata } from "next";
import Link from "next/link";
import {
  CodeInline,
  DocPage,
  Note,
  P,
  UL,
  type DocSection,
} from "@/components/DocPage";

export const metadata: Metadata = {
  title: "服务条款 | 柚柚相册",
  description:
    "柚柚相册的服务说明与使用约定：AGPL-3.0-only 的许可范围、自部署形态下由谁负责什么、责任边界在哪里。",
};

const repo = "https://github.com/mqhe2007/youyou";
const site = "https://youyou.mengqinghe.com";

const sections: DocSection[] = [
  {
    id: "accept",
    title: "条款的接受",
    body: (
      <>
        <P>下载、安装或使用柚柚相册（以下称「本应用」），即表示你已阅读并同意本条款。如果你不同意，请停止使用并卸载本应用。</P>
        <P>这个同意不改变 <CodeInline>LICENSE</CodeInline>（AGPL-3.0-only）已经给你的权利。你在许可证下本来就能做的事，不需要以同意本条款为前提；本条款也不会替换或限制许可证的条款。</P>
      </>
    ),
  },
  {
    id: "service",
    title: "服务说明",
    body: (
      <UL>
        <li>
          本应用由 <strong>Android 客户端</strong>与<strong>自部署服务端</strong>两部分组成。
        </li>
        <li>
          <strong>项目方不提供云端存储，也不提供托管服务。</strong>没有官方服务器替你存照片。服务端由使用者自己部署、自己运维，服务端的可用性、数据安全与备份都由部署者负责。
        </li>
        <li>
          部署步骤见站内
          <Link href="/quickstart" className="link-quiet">
            快速开始
          </Link>
          。
        </li>
        <li>客户端支持 Android 12（API 31）及以上，通过签名 APK 直接分发，不上架应用商店。</li>
      </UL>
    ),
  },
  {
    id: "license",
    title: "许可与使用范围",
    body: (
      <>
        <P>本应用以 AGPL-3.0-only 授权，条款全文见仓库根目录的 <CodeInline>LICENSE</CodeInline>：</P>
        <UL>
          <li>
            <strong>可以自由使用、修改和分发，包括商用。</strong>个人使用和商业部署都不需要另行取得授权，也不需要付费。
          </li>
          <li>
            <strong>分发修改版本，或把修改后的本应用作为网络服务对外提供时，必须以同一许可证公开完整源码。</strong>这是 AGPL 相对 GPL 的主要差异，用来防止别人拿它做闭源托管服务。
          </li>
          <li>使用本应用不转移软件著作权。除法律允许或授权范围明确许可之外，不得移除或修改版权声明与许可标识。</li>
          <li>项目方保留以其他许可条款单独授权的权利（双许可）。需要不受上面源码公开义务约束的授权，可以通过项目网站联系。这项权利不改变已经按 AGPL-3.0-only 发布的版本对你的授权，那些版本永远按 AGPL 走。</li>
        </UL>
        <Note title="说明">
          上面是便于阅读的摘要，有约束力的是{" "}
          <a href={`${repo}/blob/main/LICENSE`} target="_blank" rel="noreferrer" className="link-quiet">
            LICENSE 全文
          </a>
          。
        </Note>
      </>
    ),
  },
  {
    id: "responsibility",
    title: "你的责任",
    body: (
      <UL>
        <li>自行完成服务端部署、域名与网络配置、存储容量规划和数据备份。</li>
        <li>妥善保管服务端管理凭据、设备配对码与签名材料。</li>
        <li>
          <strong>自行对照片等重要数据保留独立备份。</strong>本应用不构成备份服务，不应该作为你唯一的备份手段。
        </li>
        <li>保证你对所管理、上传、删除的媒体内容拥有合法权利，使用行为符合你所在地区的法律法规。</li>
        <li>不得将本应用用于侵犯他人隐私、传播违法内容或其他违法用途。</li>
      </UL>
    ),
  },
  {
    id: "warranty",
    title: "无担保",
    body: (
      <P>本应用按「现状」提供，不附带任何明示或默示的担保，包括但不限于适销性、特定用途适用性与不侵权的担保。项目方不保证本应用没有缺陷、不会中断，也不保证它能满足你的特定需求。</P>
    ),
  },
  {
    id: "liability",
    title: "责任限制",
    body: (
      <P>在适用法律允许的最大范围内，项目方不对因使用或无法使用本应用而产生的任何间接、附带、特殊或后果性损失负责，包括但不限于数据丢失、存储损坏、业务中断或利润损失。使用之前，请对重要数据保留独立备份。</P>
    ),
  },
  {
    id: "changes",
    title: "服务变更与终止",
    body: (
      <UL>
        <li>项目方可以随时发布新版本、调整或停止维护本应用，并会在项目网站说明。</li>
        <li>你可以随时停止使用并卸载本应用。</li>
        <li>本条款终止后，第三节（许可与使用范围）、第五节（无担保）、第六节（责任限制）继续有效。</li>
      </UL>
    ),
  },
  {
    id: "privacy",
    title: "隐私",
    body: (
      <P>
        个人信息的处理方式见站内
        <Link href="/privacy" className="link-quiet">
          隐私政策
        </Link>
        。
      </P>
    ),
  },
  {
    id: "law",
    title: "适用法律与争议解决",
    body: (
      <>
        <P>本条款的订立、效力、解释与争议解决适用中华人民共和国大陆地区法律。因本条款产生的争议，双方应先友好协商；协商不成的，提交运营者住所地有管辖权的人民法院裁决。</P>
        <P>这一约定不排除适用法律规定的强制性管辖。你所在地区如果就消费者合同等规定了强制管辖法院，以法律规定为准。本条同样不影响 <CodeInline>LICENSE</CodeInline> 所赋予你的权利。</P>
      </>
    ),
  },
  {
    id: "contact",
    title: "联系我们",
    body: (
      <P>
        运营者：孟庆贺（个人开发者）。项目网站{" "}
        <a href={site} target="_blank" rel="noreferrer" className="link-quiet">
          youyou.mengqinghe.com
        </a>
        ，源码与 Issues 在{" "}
        <a href={repo} target="_blank" rel="noreferrer" className="link-quiet">
          GitHub 仓库
        </a>
        。
      </P>
    ),
  },
];

export default function TermsPage() {
  return (
    <DocPage
      current="/terms"
      title="服务条款"
      lead={
        <>
          <p>柚柚相册是开源软件，以 AGPL-3.0-only 授权。项目方只分发软件，不替你存照片。</p>
          <p className="mt-3">下面说清楚两件事：你能拿它做什么，哪些事要由部署的人自己负责。</p>
        </>
      }
      sections={sections}
    />
  );
}
