# `<include>` 跨文件片段解析——现状与修复方向

> 分析日期：2026-08-17　来源：dongdal-server `feat_mcp_dev_ref` 分支调研

## 现状

`MyBatisXmlExtractor.collectFragments`（`MyBatisXmlExtractor.java:117`）只扫**当前 mapper 文件自身**的 `<sql>` 节点，构建出一个文件内局部的 `Map<String, Element> fragments`，然后传给 `MyBatisSqlScriptBuilder.buildChildren`。

当 `<include refid="...">` 引用的 `<sql id="...">` 定义在**另一个文件**时，`includeBuild`（`MyBatisSqlScriptBuilder.java:104`）做 `fragments.get(refid)` 拿到 `null`，直接返回 `" __MISSING_INCLUDE__ "`，最终被 server 端判 `UNRESOLVED_INCLUDE`（未评测）。

跨仓合计约 39 条受影响，本仓库当前 0 条（vrs-server 单应用数据）。

## 根因

`extract(Path path, ExtractionContext context)` 入口只拿到单个文件路径，没有「同项目所有 mapper 文件」的上下文。`ExtractionContext` 目前只暴露 normalizer，不携带全局 fragment registry。

MyBatis 跨文件 refid 的命名规范：通常是 `namespace.fragmentId`（如 `com.foo.BaseMapper.columns`）或裸 `fragmentId`（同 namespace 内引用）。`collectFragments` 已同时注册裸 id 和 `namespace.id` 两个 key（`MyBatisXmlExtractor.java:122-126`），跨文件时缺的是**其他文件的 namespace + id**。

## 修复方向（尚未实现）

改动集中在三处，`MyBatisSqlScriptBuilder` 不需要动：

### 1. 全局预扫描（`ScanEngine` 或调用层）

在正式 extract 前，先遍历所有 mapper XML 文件，把全部 `<sql id>` 元素收集进一个全局 `Map<String, Element> globalFragments`。key 同时注册裸 id 和 `namespace.id` 两种形式，与 `collectFragments` 保持一致。

### 2. `ExtractionContext` 增加全局 fragments

```java
// ExtractionContext 新增方法
Map<String, Element> globalFragments();
```

在 `ScanEngine` 初始化 `ExtractionContext` 时注入预扫描结果。

### 3. `MyBatisXmlExtractor.collectFragments` 合并两层

```java
private Map<String, Element> collectFragments(Element mapper, String namespace, ExtractionContext context) {
    Map<String, Element> fragments = new HashMap<>(context.globalFragments()); // 全局兜底
    // 本文件优先——同名 key 覆盖全局
    for (Element child : MyBatisSqlScriptBuilder.childElements(mapper)) {
        if ("sql".equals(child.getTagName())) {
            String id = child.getAttribute("id");
            fragments.put(id, child);
            if (namespace != null && !Strings.isBlank(namespace)) {
                fragments.put(namespace + "." + id, child);
            }
        }
    }
    return fragments;
}
```

本文件 fragment 优先于全局（同名覆盖），避免跨文件同 id 污染。

## 注意事项

- 预扫描需要解析所有 XML，有一定启动开销，但只跑一次。
- 动态 refid（`<include refid="${prefix}_columns">`）静态无法解析，仍会落 `__MISSING_INCLUDE__`，属合理终态。
- server 侧现状正确：`MISSING_INCLUDE_MARKER` → `UNRESOLVED_INCLUDE`「未评测」，不假通过，无需改动。

## 已落地的改动（2026-08-17）

### 层一：`ExtractionContext`（新增全局 fragments 存储）

新增了一个带 `globalFragments` 参数的构造器，以及 `globalFragments()` accessor。原有的单参数构造器（只传 normalizer）保留为兜底，返回空 map，保持向后兼容。

目的：让 fragments 可以从外部注入到每次 `extract()` 调用中，而不是只靠单文件自扫。

### 层二：`ScanEngine`（新增全局预扫描）

在 `scan()` 方法里，`discover` 拿到文件列表后、构造 `ExtractionContext` 前，新增了一行：

```java
Map<String, Element> globalFragments = MyBatisXmlExtractor.collectGlobalFragments(files);
```

然后把它注入到 context 里。预扫描只跑一次，遍历所有 XML 文件，把每个 mapper 里的 `<sql id>` 节点都收集进全局 map，key 同时注册裸 id 和 `namespace.id` 两种形式（`putIfAbsent`，先出现的优先）。

目的：在正式 extract 开始前，把整个项目范围内所有可用的 `<sql>` 片段备齐，后续每个文件的 extract 都能查到跨文件 refid。

### 层三：`MyBatisXmlExtractor`（collectFragments 合并两层）

`collectFragments` 方法签名增加了 `ExtractionContext context` 参数。逻辑改为：先把全局 fragments 作为底层（`new HashMap<>(context.globalFragments())`），再把本文件的 `<sql>` 节点覆盖写入（`put`，本文件优先于全局）。

同时把预扫描逻辑抽成了 `public static collectGlobalFragments(List<Path> files)` 方法，供 `ScanEngine` 调用。

目的：跨文件 refid 命中全局 map 兜底，同文件 refid 优先用本文件定义，避免不同文件同名 id 互相污染。

### 不需要动的层

`MyBatisSqlScriptBuilder.includeBuild` 逻辑不变——它只做 `fragments.get(refid)`，查不到就返回 `__MISSING_INCLUDE__`。动态 refid（如 `${prefix}_columns`）静态无法解析，仍然走这条路径，属合理终态。

---

## 预期收益

跨仓约 39 条 `UnresolvedInclude` 可回收，对「已评测准确率」影响约 +0.1~0.15%。优先级 P2（量小但属采集完整性缺口）。
