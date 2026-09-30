将 procurement-workbench.html 的编辑区+预览区改造为 IDEA Git Diff 风格的对比视图：

1. 布局：两栏无缝拼接（gap:0），统一外框边框/阴影，中间 1px 分隔线；面板标题合并为一条 IDE 风格深色标题带（墨绿主题，左「文档编辑」右「文档预览」）。
2. 可拖拽 splitter：拖动中间分隔线调整左右栏宽度比例（类 IDEA）。
3. 同步滚动：监听 Vditor wysiwyg 滚动容器（.vditor-wysiwyg）与 #preview 的 scroll 事件，按滚动比例互相同步 scrollTop，用守卫标志 + rAF 节流防止回环；小屏堆叠布局下禁用。
4. 细节：两侧高度统一、行高/padding 对齐使同步观感自然；更新响应式单列样式。
5. 不改动任何业务逻辑（版本保存/定稿/导出/轮询）。

仅修改 src/main/resources/static/procurement-workbench.html。