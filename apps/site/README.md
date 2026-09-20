# youyou-site

柚柚相册产品落地页，单页 Next.js 应用。

## 运行

```bash
npm install
npm run dev
```

生产构建：

```bash
npm run build
npm run start
```

## 设计约束

落地页遵循产品设计规范锁定的品牌 token（暖象牙底、鲜柚黄强调色、近黑前景），
并按 `design-taste-frontend` 技能（tasteskill v2）执行：

- 整页一个主题（CSS 变量 + `prefers-color-scheme`，暖象牙浅色 / 暖黑深色）
- 单一强调色 `#FFD63A`，圆角体系：按钮全圆角、图片帧 16px、表面积为 12px
- 页面零 em-dash（U+2014）与零 en-dash（U+2013）
- 布局家族：Asymmetric Split Hero、Editorial Manifesto、Bento Grid、
  Steps Grid、Color-Block Gallery、Spec Cards、Q&A Rows、Accordion、Brand Band

图片素材由生成工具产出（`public/images/`），品牌标志取自仓库锁定稿
（`apps/client/assets/icons/logo_mark.png`、`apps/client/assets/brand/`）。
