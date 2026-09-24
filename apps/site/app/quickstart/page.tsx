import type { Metadata } from "next";
import Link from "next/link";
import {
  Code,
  CodeInline,
  Comment,
  DocPage,
  H3,
  Note,
  P,
  Table,
  UL,
  type DocSection,
} from "@/components/DocPage";

export const metadata: Metadata = {
  title: "快速开始 | 柚柚相册",
  description:
    "用 Docker 把柚柚相册服务端装到自己的机器上：拉现成镜像、设置运行用户、初始化管理员、备份与恢复。不需要源码，也不需要构建工具链。",
};

const repo = "https://github.com/mqhe2007/youyou";

const sections: DocSection[] = [
  {
    id: "choose",
    title: "先选自己的路径",
    body: (
      <>
        <P><strong>管理员：</strong>准备常开机器和照片目录 → 启动服务端 → 创建管理员 → 在管理页创建成员并绑定目录 → 生成邀请二维码。下方是完整部署步骤。</P>
        <P><strong>受邀成员：</strong><Link href="/download" className="link-quiet">下载客户端</Link> → 安装 → 扫描管理员提供的邀请二维码 → 浏览自己的第一张照片。已有邀请时，不需要自己部署服务端。</P>
        <Note title="先确认可达性">手机必须能访问管理员的服务器。同一局域网最简单；外出访问需要管理员自行配置网络，柚柚不会自动提供公网连接。</Note>
      </>
    ),
  },
  {
    id: "prepare",
    title: "准备条件",
    body: (
      <>
        <P>服务端是一个单独的进程，用 Docker 起最省事。开始前面确认这几样：</P>
        <UL>
          <li>一台能一直开着的机器：家庭 NAS、旧电脑、小云主机都行。</li>
          <li>装好 Docker 与 Docker Compose（用 v2 的 <CodeInline>docker compose</CodeInline> 命令）。</li>
          <li>CPU 架构 amd64 或 arm64 都支持。</li>
          <li>一个放照片的目录，磁盘按你的照片库容量估。</li>
          <li>一个空闲端口，默认 <CodeInline>8989</CodeInline>。</li>
        </UL>
        <P>当前客户端支持 Android 12（API 31）或更高版本。</P>
      </>
    ),
  },
  {
    id: "install",
    title: "拉现成镜像启动",
    body: (
      <>
        <P>这是推荐路径：不用下载源码，也不用装 Rust 或 Node 工具链。在你准备放项目的目录里执行下面三条命令。</P>
        <Code
          label="拉取镜像并启动服务端"
          lines={[
            <Comment key="c1"># 下载官方 compose 文件，不需要 clone 仓库</Comment>,
            "curl -O https://raw.githubusercontent.com/mqhe2007/youyou/main/deploy/docker-compose.yml",
            <Comment key="c2"># 先建好运行时目录，再启动</Comment>,
            "mkdir -p ./youyou-server/data ./youyou-server/media",
            "docker compose up -d",
          ]}
        />
        <P>这份 compose 从 <CodeInline>ghcr.io/mqhe2007/youyou-server</CodeInline> 拉镜像，把宿主机的 <CodeInline>./youyou-server</CodeInline> 挂到容器内 <CodeInline>/srv/youyou</CodeInline>，并按 <CodeInline>YOUYOU_SERVER_PORT</CodeInline>（缺省 <CodeInline>8989</CodeInline>）发布端口。想把版本固定住，把镜像的 <CodeInline>:latest</CodeInline> 换成具体版本号。</P>
        <P>起来之后确认一下服务是否健康：</P>
        <Code
          label="检查服务端健康状态"
          lines={[
            "curl http://127.0.0.1:8989/api/v1/health",
            <Comment key="c3"># 或者看日志</Comment>,
            "docker compose logs -f youyou-server",
          ]}
        />
        <Note title="端口没起来？">
          先确认 <CodeInline>8989</CodeInline> 没被别的程序占用，再看宿主机防火墙有没有放行。只在家里用，不用做内网穿透。
        </Note>
      </>
    ),
  },
  {
    id: "first-run",
    title: "第一次启动：创建管理员",
    body: (
      <>
        <P>服务端首次启动会在数据目录里写一个一次性初始化令牌，它只用来创建第一个管理员，完成初始化后立即失效。</P>
        <Code
          label="读取初始化令牌"
          lines={["cat ./youyou-server/data/bootstrap/setup-token"]}
        />
        <P>然后打开 <CodeInline>http://&lt;服务端地址&gt;:8989/admin</CodeInline>，用这个令牌创建管理员账号并登录。管理页里可以做这些事：</P>
        <UL>
          <li>登录与注销会话。</li>
          <li>创建成员，为每位成员绑定各自的媒体库目录；先确认目录已挂载且权限可读。</li>
          <li>生成邀请二维码，把对应成员的手机接进来。</li>
          <li>按目录刷新媒体索引，确认第一张照片可见。</li>
          <li>查看运行日志。</li>
        </UL>
        <H3>只用 HTTP</H3>
        <P>当前版本统一走 HTTP，服务端不做 HTTPS 终止。要暴露到公网，请自己在前面加反向代理。管理页仍然使用 HttpOnly、SameSite=Strict 的会话与 CSRF Cookie。</P>
        <H3>存储根可以改</H3>
        <P><CodeInline>&lt;server-dir&gt;/media</CodeInline> 只是首次初始化时的默认目录。在管理页改过的存储根会持久化到服务端数据库，重启后继续用。切换根目录后，已有索引会先隐藏，完成一次扫描后才重新可见。</P>
      </>
    ),
  },
  {
    id: "pair",
    title: "成员下载并扫码",
    body: (
      <>
        <P><Link href="/download" className="link-quiet">下载当前客户端</Link>并安装，在客户端扫描管理员从管理页生成的邀请二维码；也可以按界面提示手填服务端信息。接入后查看自己的第一张照片，或选一张本机照片手动上传。</P>
        <P>手机要能访问到服务端：同一个局域网最直接；跨网使用需要你自己处理端口映射或内网穿透。</P>
        <Note title="没看到照片？">先在管理页确认该成员的目录已绑定、挂载可读且扫描完成。扫码失败时确认二维码仍有效、手机已授予必要权限，并检查服务器地址是否可达；不连接服务器也可以先浏览本机照片。</Note>
      </>
    ),
  },
  {
    id: "puid",
    title: "进阶：PUID 与 PGID",
    body: (
      <>
        <P>镜像内置一个非 root 用户（uid <span className="tabular">10001</span>）。但 bind mount 进来的宿主目录属主是你自己，那个 uid 对它没有写权限，所以直接跑会失败，报 <CodeInline>create data directory /srv/youyou/data</CodeInline>。</P>
        <P>发布的 compose 因此默认按 <CodeInline>PUID</CodeInline>/<CodeInline>PGID</CodeInline> 运行，缺省 <CodeInline>1000:1000</CodeInline>。绝大多数 Linux 机器上，第一个普通用户的 uid 和 gid 就是 1000，什么都不用改。</P>
        <P>宿主用户 uid 不是 1000 时（群晖常见），显式指定成你自己的：</P>
        <Code
          label="按当前用户身份启动"
          lines={["PUID=$(id -u) PGID=$(id -g) docker compose up -d"]}
        />
        <P>如果你改用命名卷而不是宿主目录来存数据，卷会从镜像继承属主，这时候反而要设回镜像内置用户：<CodeInline>PUID=10001 PGID=10001</CodeInline>。</P>
      </>
    ),
  },
  {
    id: "config",
    title: "进阶：环境变量与运行时目录",
    body: (
      <>
        <P>服务端优先从环境变量读配置。本地开发时会从仓库根目录的 <CodeInline>.env</CodeInline> 加载，不覆盖已有的进程环境变量。也可以用命令行参数 <CodeInline>--port</CodeInline>、<CodeInline>--server-dir</CodeInline> 覆盖。</P>
        <Table
          head={["变量", "含义", "缺省值"]}
          label="服务端环境变量"
          rows={[
            [
              <CodeInline key="e1">YOUYOU_SERVER_PORT</CodeInline>,
              "监听端口；API 与嵌入的管理页 /admin 共用",
              <span className="tabular" key="e1d">
                8989
              </span>,
            ],
            [
              <CodeInline key="e2">YOUYOU_SERVER_DIR</CodeInline>,
              "运行时目录",
              <CodeInline key="e2d">$HOME/youyou-server</CodeInline>,
            ],
            [
              <CodeInline key="e3">YOUYOU_TRASH_DIR</CodeInline>,
              "回收站目录；填相对路径时按运行时目录解析",
              <CodeInline key="e3d">&lt;server-dir&gt;/trash</CodeInline>,
            ],
          ]}
        />
        <P>运行时目录长这样：</P>
        <Code
          label="运行时目录布局"
          lines={[
            "$YOUYOU_SERVER_DIR/",
            "  data/          # SQLite、锁文件、bootstrap、备份等",
            "  media/         # 首次初始化时的默认媒体根（可在管理页修改并持久化）",
            "  trash/         # 回收站暂存：删除的原件先移到这里",
          ]}
        />
        <P>备份和初始化令牌都在 <CodeInline>data/</CodeInline> 里，媒体原件在 <CodeInline>media/</CodeInline> 或你配置的存储根里。换机器时把这几个目录一起搬走、权限保持不变就行。</P>
        <Note title="回收站保留期固定 30 天">
          服务端删除的原件先进回收站，满 30 天后自动清除。这个保留期是程序里的固定值，没有配置项可以改；能配置的只有回收站的目录位置 <CodeInline>YOUYOU_TRASH_DIR</CodeInline>。
        </Note>
      </>
    ),
  },
  {
    id: "backup",
    title: "进阶：备份与恢复",
    body: (
      <>
        <P>数据库有三个备份子命令：<CodeInline>backup create</CodeInline> 生成一致快照和清单，<CodeInline>backup verify</CodeInline> 校验目录、清单与 SQLite 完整性，<CodeInline>backup restore</CodeInline> 把验证过的备份恢复回去。</P>
        <Code
          label="Docker 部署下的备份与恢复"
          lines={[
            <Comment key="b1"># create 和 restore 要独占运行时目录，先停服务</Comment>,
            "docker compose stop",
            "docker compose run --rm youyou-server backup create",
            "docker compose run --rm youyou-server backup restore \\",
            "    /srv/youyou/data/backups/<backup-id>",
            "docker compose up -d",
            <Comment key="b2"># verify 只读，服务运行中就能查（备份目录名由 create 打印）</Comment>,
            "docker compose exec youyou-server backup verify \\",
            "    /srv/youyou/data/backups/<backup-id>",
          ]}
        />
        <P><CodeInline>backup create</CodeInline> 默认写到 <CodeInline>&lt;server-dir&gt;/data/backups</CodeInline>，要换地方用 <CodeInline>--output-dir</CodeInline> 指定。</P>
        <P>恢复之前看清两点：</P>
        <UL>
          <li>恢复会先把当前数据库存进 <CodeInline>data/backups</CodeInline> 再覆盖，不是直接丢弃。</li>
          <li>备份里只有数据库，<strong>不包含原始媒体文件</strong>。完整恢复还需要单独把媒体目录和挂载权限恢复回去。</li>
        </UL>
        <P>所以别把本应用当成唯一的备份手段。重要照片另外留一份；媒体目录本身直接复制就是备份。</P>
      </>
    ),
  },
  {
    id: "source",
    title: "进阶：从源码构建",
    body: (
      <>
        <P>次要路径。只有你要改代码，或者想把它接进自己的构建流程时才需要。clone 仓库后用根目录的 <CodeInline>docker-compose.yml</CodeInline>，端口和运行时目录都在那个文件里改：</P>
        <Code
          label="源码构建镜像并启动"
          lines={["docker compose up --build"]}
        />
        <P>装好 Rust 工具链、完全不用 Docker 也可以直接跑：</P>
        <Code
          label="本地直接运行服务端"
          lines={[
            "cargo run --manifest-path apps/server/Cargo.toml -- \\",
            "    --port 8989 \\",
            '    --server-dir "$HOME/youyou-server"',
          ]}
        />
        <P>三个备份子命令在这种方式下写法相同，只要把服务端的命令换成 <CodeInline>backup create</CodeInline> 之类的参数。</P>
        <H3>不起 HTTP 服务，只用命令行建管理员</H3>
        <Code
          label="通过 CLI 初始化管理员"
          lines={[
            "read -r -s ADMIN_PASSWORD",
            "printf '\\n'",
            "printf '%s\\n' \"$ADMIN_PASSWORD\" | \\",
            "    cargo run --manifest-path apps/server/Cargo.toml -- \\",
            "    admin init --password-stdin",
            "unset ADMIN_PASSWORD",
          ]}
        />
        <P>密码只从标准输入读取，不会写进命令行参数或日志。<CodeInline>admin init</CodeInline> 同样读取 <CodeInline>YOUYOU_SERVER_DIR</CodeInline>（或 <CodeInline>--server-dir</CodeInline>）。</P>
        <H3>管理页前端</H3>
        <P>管理页源码在 <CodeInline>apps/server/web</CodeInline>。构建服务端时会先生成固定路径的 <CodeInline>admin.js</CodeInline> 和 <CodeInline>admin.css</CodeInline>，再嵌进 Rust 二进制。想单独构建它：</P>
        <Code
          label="单独构建管理页"
          lines={["cd apps/server/web", "npm ci", "npm run build"]}
        />
        <P>改管理页的样式和交互时可以跑 <CodeInline>npm run dev</CodeInline>，但完整联调仍应通过服务端的 <CodeInline>/admin</CodeInline> 入口验证认证、CSRF 和管理 API。</P>
      </>
    ),
  },
  {
    id: "next",
    title: "装好之后",
    body: (
      <>
        <P>建议第一件事：在管理页确认存储根指向你真正的照片目录，然后跑一次索引。第二件事：把 <CodeInline>backup create</CodeInline> 放进定时任务。</P>
        <UL>
          <li>
            数据怎么放、权限要什么、删掉还能不能找回来，见
            <Link href="/privacy" className="link-quiet">
              隐私政策
            </Link>
            。
          </li>
          <li>
            许可范围和你需要自己负责的部分，见
            <Link href="/terms" className="link-quiet">
              服务条款
            </Link>
            。
          </li>
          <li>
            源码与 Issues 在{" "}
            <a href={repo} target="_blank" rel="noreferrer" className="link-quiet">
              GitHub 仓库
            </a>
            。
          </li>
        </UL>
      </>
    ),
  },
];

export default function QuickstartPage() {
  return (
    <DocPage
      current="/quickstart"
      title="快速开始"
      lead={
        <>
          <p>管理员按步骤接入自己的照片目录；受邀成员下载客户端扫码即可开始浏览。</p>
          <p className="mt-3">部署细节与进阶运维说明在下方，成员可以直接跳到“成员下载并扫码”。</p>
        </>
      }
      sections={sections}
    />
  );
}
