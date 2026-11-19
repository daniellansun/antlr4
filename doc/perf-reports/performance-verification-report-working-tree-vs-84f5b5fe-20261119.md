# ANTLR4 Runtime 与 Tool 深度性能优化分析及验证报告

- **验证基准（Baseline）**：commit `84f5b5fea6868596157ad8c202c61e9c37e92555`（分支 `tweak-20261119`，版本 `4.13.2.19`）
- **验证目标**：基于 ALL(*) 论文理论与 Arthas CPU 火焰图驱动，完成 3 轮严格单调递增优化，同时提升 `runtime/Java` 运行时性能与 `tool` 模块生成的 Lexer/Parser 代码质量和执行性能
- **验证对象**：真实 Apache Groovy 语法（`GroovyLexer.g4`、`GroovyParser.g4`）及其生成的 `GroovyLexer.java` / `GroovyParser.java`，在 70 个真实 Groovy 源文件语料上的 **11 项串行与并行词法/语法解析场景**
- **验证环境**：Amazon Corretto OpenJDK `1.8.0_514`（JDK 8），Linux `6.12.0` x86_64，6 vCPU（AMD EPYC 7763），`-Xms1g -Xmx2g -XX:+UseG1GC`
- **日期**：2026-11-19

---

## 1. 执行摘要（Executive Summary）

本次优化深入消化 Parr 等人的 ALL(*) 论文（*Adaptive LL(\*) Parsing: The Power of Dynamic Analysis*, OOPSLA 2014），联合 3 位分别深耕 **ATN/DFA/HPPC/并发**、**字符/Token流/JIT/安全** 以及 **Tool 代码生成/ST4/静态分析** 的领域专家开展协同设计与优化后严格复核，严格遵循“**Arthas 火焰图定位 → 精准优化 → 同机交叉验证 → 绝不回退**”的工程方法论，完成 **3 轮迭代优化**。

在最终复核修复后的工作树上执行 **4 版本同机交叉轮转对比**（`Baseline 84f5b5fe` vs `Round 1` vs `Round 2` vs `Round 3 Final`，每场景 3 轮轮转顺序执行），取得以下经严格验证的成果：

1. **三轮 11/11 严格单调递增（33/33 全部胜出）**：
   - **Round 1 vs Baseline**：**11/11 全部提升**，几何平均提速 **1.390×（+39.0%）**
   - **Round 2 vs Round 1**：**11/11 全部提升**，在 Round 1 基础上再提速 **1.281×（+28.1%）**
   - **Round 3 Final vs Round 2**：**11/11 全部提升**，在 Round 2 基础上再提速 **1.069×（+6.9%）**
   - **累计总提升（Round 3 Final vs Baseline）**：11 项串行/并行场景几何平均提速 **1.904×（+90.4%）**；其中**并行词法解析达 2.80×（+180.5%）**、**串行词法解析达 2.62×（+161.9%）**、**并行语法树构建达 1.89×（+89.1%）**、**串行全上下文 LL(\*) 达 1.83×（+83.3%）**、**并行冷启动 DFA 构建达 1.64×（+64.2%）**、**串行/并行 SLL 解析达 1.57× / 1.55×**。
2. **生成的 `GroovyLexer` / `GroovyParser` 代码质量与字节码体量大幅优化**：
   - `GroovyParser` 方法总字节码从 **65,081 B 降至 49,882 B（-23.4%）**；超过 HotSpot C2 内联阈值（`FreqInlineSize = 325 B`）的巨型方法从 **48 个骤降至 25 个（-47.9%）**；`_adaptivePredict` 调用点从 **326 处降至 186 处（-42.9%）**；`switch` 语句从 **166 处降至 67 处（-59.6%）**。
   - `GroovyLexer` 方法总数从 **84 个精简至 49 个（-41.7%）**，方法总字节码从 **7,388 B 降至 6,246 B（-15.5%）**，完全消除 35 个二级分发辅助方法并转为 O(1) 扁平 `tableswitch`。
3. **100% 语义等价与 API 零破坏**：
   - 70 个真实 Apache Groovy 源文件（1,618,957 字节 / 1.544 MiB、177,906 Tokens、650,437 ParseTree 节点、0 语法错误）的完整词法流与语法树 SHA-256 校验值与基线 **100% 比特级一致**：`6ecb3e18b07718d986809c4895fac718baa4746c5c4e527e26cbe789e31c3c17`。
   - **HPPC 零暴露**：`TestHppcApiBoundary` 反射扫描与 Maven `verify-shaded-hppc` 校验均确认 **0 处** HPPC 类型出现在任何 `public` 或 `protected` API 签名中。
   - **高覆盖率质量保障**：新增 3 套深度单元测试，修改与新增代码的 JaCoCo 行覆盖率合计达到 **96.68%**（`runtime/Java` **96.96%**、`tool` **94.01%**），且 `runtime/Java`（1,220/1,220）、`tool`（全部 1,616 项非 AWT 测试）、`antlr4-annotations`、`antlr4-maven-plugin`、`perf-testsuite` 及 Javadoc 构建全部通过。

---

## 2. 测试环境、语料与验证方法论

### 2.1 硬件与 JVM 环境

| 项目 | 配置 |
| --- | --- |
| CPU | AMD EPYC 7763（6 核 / 6 vCPU，单核 384 KiB L1i + 384 KiB L1d，3 MiB L2，96 MiB L3） |
| 操作系统 | Linux `6.12.0-264.16.1.el10_3.x86_64` |
| JDK | Amazon Corretto OpenJDK `1.8.0_514`（64-Bit Server VM `25.514-b01`，mixed mode） |
| JVM 参数 | `-Xms1g -Xmx2g -XX:+UseG1GC` |
| 采样与诊断工具 | Arthas（`~/as.sh` 内置 async-profiler CPU 火焰图）+ HotSpot `-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation` |

### 2.2 真实 Apache Groovy 语料与 11 项串行/并行场景

语料选取自 Apache Groovy 源码仓库中 70 个真实 `.groovy` 源文件（总计 **1,618,957 字节 / 1.544 MiB**，覆盖完整 Groovy 词法/语法特性：GString/SlashyString/DollarSlashyString、闭包、左递归表达式、上下文相关语义谓词、注解、泛型、换行敏感分号推断等），经 `RealGroovyBenchmark verify` 验证共产生 **177,906 个 Token**、**650,437 个 ParseTree 节点**、**0 错误**。

| 编号 | 场景名 | 模式 | 并发 | 预热/测量窗口 | 度量核心瓶颈 |
| ---: | --- | --- | --- | --- | --- |
| 1 | `serial_lex_warm` | 串行词法（每次新建 Lexer） | 1T | 5s / 5s | 热 DFA 字符扫描、谓词边缓存、`skip` 融合、`CharStreams.fromString` |
| 2 | `serial_lex_reuse_warm` | 串行词法（复用 Lexer 实例） | 1T | 5s / 5s | 纯词法 DFA 吞吐、Token 构造与动作执行、输入流切换 |
| 3 | `parallel_lex_warm` | 并行词法（共享 ATN/DFA） | 4T | 4s / 5s | 多线程无锁读 `asciiEdges` / `LexerPredEdgeTable` 与缓存行扩展性 |
| 4 | `serial_parse_sll_warm` | 串行 SLL 解析（`buildParseTree=false`） | 1T | 5s / 5s | `CommonTokenStream`、`TokenList`、`execDFASllWarm`、生成代码指令密度与 C2 内联 |
| 5 | `parallel_parse_sll_warm` | 并行 SLL 解析（`buildParseTree=false`） | 4T | 4s / 5s | 共享 SLL DFA 并发无锁吞吐与零共享写屏障（消除 `this.dfa` 写屏障） |
| 6 | `serial_parse_tree_warm` | 串行完整解析 + 语法树构建（两阶段 SLL→LL） | 1T | 3s / 6s | 节点分配（Tier 2a/2b）、`ParserRuleContext`、`exitRule`、冲突检测、全上下文回退 |
| 7 | `parallel_parse_tree_warm` | 并行完整解析 + 语法树构建（两阶段 SLL→LL） | 4T | 3s / 6s | 多线程语法树分配与并发 SLL/LL 混合负载下的 GC 压力与扩展性 |
| 8 | `serial_parse_ll_warm` | 串行纯全上下文 LL(\*) 解析 | 1T | 1s / 6s | `EpsilonClosure`、`PredictionContextCache`、HPPC 合并索引、`isConflicted` 冲突检测 |
| 9 | `parallel_parse_ll_warm` | 并行纯全上下文 LL(\*) 解析 | 4T | 1s / 6s | 多线程全上下文 ATN 模拟扩展性 |
| 10 | `serial_parse_cold_build` | 串行冷启动（每轮清空 DFA 重建） | 1T | 1s / 9s | ATN 闭包、DFA 状态构建、开放寻址 `PredictionContextCache`、`DFAState` 边表写入 |
| 11 | `parallel_parse_cold_build` | 并行冷启动（4 线程并发构建共享 DFA） | 4T | 1s / 9s | 高并发 DFA 状态/边写入、COW 发布与 G1GC 吞吐 |

### 2.3 严谨防漂移实验设计

- **严格独立进程**：每个版本每个场景在独立 JVM 进程中启动，彻底消除类加载、JIT 污染及堆碎片交叉影响。
- **4 版本同轮交叉与轮转顺序**：3 个 Trial 分别采用 `(Base, R1, R2, R3)`、`(R3, R2, R1, Base)`、`(R1, R3, Base, R2)` 的轮转顺序连续执行，消除温度/频率漂移偏差，且执行期间整机无其他竞争进程。
- **四版本构建配置**：
  - `Baseline`：commit `84f5b5fe` 的 `antlr4-runtime` + `antlr4-tool` 生成的 Groovy 代码
  - `Round 1`：Round 1 优化后的 `antlr4-runtime` + Baseline 生成的 Groovy 代码
  - `Round 2`：Round 2 优化后的 `antlr4-runtime` + Baseline 生成的 Groovy 代码
  - `Round 3 Final`：复核修复后的最终 `antlr4-runtime` + 最终 `antlr4-tool` 生成的 Groovy 代码

---

## 3. 四版本交叉验证量化结果（Baseline → R1 → R2 → R3 Final）

### 3.1 吞吐量（`loops/sec`，越高越好）与三轮单调性验证

| 场景（Scenario） | Baseline (`84f5b5fe`) | Round 1 | R1 vs Base | Round 2 | R2 vs R1 | Round 3 Final | R3 vs R2 | **累计总提速 (R3 / Base)** | 单调递增 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | :---: |
| `serial_lex_warm` | 18.3813 | 41.7632 | +127.2% | 46.1227 | +10.4% | **48.1461** | +4.4% | **2.62× (+161.9%)** | PASS |
| `serial_lex_reuse_warm` | 18.6069 | 32.4218 | +74.2% | 44.3770 | +36.9% | **46.1438** | +4.0% | **2.48× (+148.0%)** | PASS |
| `parallel_lex_warm` (4T) | 58.8863 | 113.5135 | +92.8% | 151.4866 | +33.5% | **165.1680** | +9.0% | **2.80× (+180.5%)** | PASS |
| `serial_parse_sll_warm` | 6.5118 | 9.3287 | +43.3% | 10.2017 | +9.4% | **10.2412** | +0.4% | **1.57× (+57.3%)** | PASS |
| `parallel_parse_sll_warm` (4T) | 25.7016 | 36.0219 | +40.2% | 39.2238 | +8.9% | **39.8696** | +1.6% | **1.55× (+55.1%)** | PASS |
| `serial_parse_tree_warm` | 0.5583 | 0.6554 | +17.4% | 0.9088 | +38.7% | **0.9449** | +4.0% | **1.69× (+69.2%)** | PASS |
| `parallel_parse_tree_warm` (4T) | 0.7707 | 0.8028 | +4.2% | 1.3202 | +64.4% | **1.4577** | +10.4% | **1.89× (+89.1%)** | PASS |
| `serial_parse_ll_warm` | 0.0683 | 0.0850 | +24.4% | 0.1164 | +37.0% | **0.1252** | +7.5% | **1.83× (+83.3%)** | PASS |
| `parallel_parse_ll_warm` (4T) | 0.2501 | 0.2940 | +17.6% | 0.4164 | +41.6% | **0.4367** | +4.9% | **1.75× (+74.6%)** | PASS |
| `serial_parse_cold_build` | 0.1508 | 0.1693 | +12.2% | 0.2269 | +34.0% | **0.2401** | +5.8% | **1.59× (+59.2%)** | PASS |
| `parallel_parse_cold_build` (4T) | 0.3187 | 0.3866 | +21.3% | 0.4139 | +7.1% | **0.5233** | +26.4% | **1.64× (+64.2%)** | PASS |
| **11 项几何平均（Geomean）** | **1.000×** | **1.390×** | **+39.0%** | **1.780×** | **+28.1%** | **1.904×** | **+6.9%** | **1.904× (+90.4%)** | **33/33 PASS** |

> **说明**：`serial_parse_sll_warm` 在 Round 3 Final 的峰值吞吐达 **10.97 loops/s**（`parallel_parse_sll_warm` 峰值达 **41.62 loops/s**）；`serial_lex_warm` 峰值达 **51.69 loops/s**（**83.67 MB/s**），`parallel_lex_warm` 峰值达 **167.97 loops/s**（**271.88 MB/s**）。

### 3.2 批次耗时（`ms/batch`，越低越好）与源码处理速率（`MB/s`）

| 场景（Scenario） | Baseline 耗时 (ms) | Round 3 Final 耗时 (ms) | **延迟降低** | Baseline 速率 (MB/s) | Round 3 Final 速率 (MB/s) | Round 3 峰值 (`loops/s`) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `serial_lex_warm` | 55.19 | **20.89** | **-62.2%** | 29.75 | **77.93** | 51.6919 |
| `serial_lex_reuse_warm` | 54.34 | **21.81** | **-59.9%** | 30.12 | **74.69** | 49.9138 |
| `parallel_lex_warm` (4T) | 17.00 | **6.06** | **-64.4%** | 95.31 | **267.34** | 167.9701 |
| `serial_parse_sll_warm` | 153.92 | **98.14** | **-36.2%** | 10.06 | **15.82** | 10.9688 |
| `parallel_parse_sll_warm` (4T) | 39.06 | **25.13** | **-35.7%** | 39.70 | **61.59** | 41.6166 |
| `serial_parse_tree_warm` | 1,791.08 | **1,058.33** | **-40.9%** | 0.90 | **1.53** | 0.9510 |
| `parallel_parse_tree_warm` (4T) | 1,299.35 | **687.80** | **-47.1%** | 1.25 | **2.36** | 1.5623 |
| `serial_parse_ll_warm` | 14,671.63 | **8,013.58** | **-45.4%** | 0.11 | **0.20** | 0.1322 |
| `parallel_parse_ll_warm` (4T) | 4,018.45 | **2,290.81** | **-43.0%** | 0.40 | **0.71** | 0.4456 |
| `serial_parse_cold_build` | 6,637.42 | **4,183.01** | **-37.0%** | 0.24 | **0.39** | 0.2572 |
| `parallel_parse_cold_build` (4T) | 3,152.52 | **1,911.78** | **-39.4%** | 0.51 | **0.85** | 0.5346 |

---

## 4. ALL(*) 理论与 Arthas CPU 火焰图驱动的三轮优化剖析

### 4.1 Arthas CPU 火焰图热点演进（Baseline → R1 → R2 → R3）

每轮优化前后均通过 Arthas async-profiler 采集真实 Groovy 负载的 CPU 采样栈（每场景 8,000–32,000 样本），清晰印证了“消除第一瓶颈 → 暴露下一瓶颈 → 逐层击穿”的完整轨迹：

| 负载场景 | Baseline 关键热点（Self CPU %） | Round 1 演进 | Round 2 演进 | Round 3 Final 演进 |
| --- | --- | --- | --- | --- |
| **`serial_lex_warm`** | `LexerActionExecutor.execute`: **24.1%**、`LexerATNSimulator.closure`: **10.3%**、`getReachableTarget`: **9.5%**、`execATN`: **5.6%**（25.4% 耗在谓词边重复 ATN 闭包！） | `closure`/`getReachableTarget` **彻底归零（0%）**！热点转移至 `execATNFastCodePoint` (**43.9%**) 与 `LA` (**11.2%**) | 8-bit 直接字节数组扫描将 `execATNFastCodePoint` 压至 **4.2%**，热点转移至 `executeBuiltIn` (**42.7%**) | `-> skip` 词法融合 + 扁平 `GroovyLexer.action()` 将 `executeBuiltIn` 压降至 **30.5%** |
| **`serial_parse_sll_warm`** | `vtable stub`: **11.0%**、`BufferedTokenStream.fetch`: **7.3%**、`adaptivePredict`: **7.2%**、`ArrayList.grow`: **6.2%**、`HashEdgeMap.get`: **3.6%** | 词法与单跳优化后，热点集中于 `execDFA` (**9.9%**)、`vtable stub` (**10.0%**)、`ArrayList.grow` (**8.2%**) | `TokenList` + `execDFASllWarm` 将 `ArrayList.grow` 与 `vtable stub` **彻底清零**，热点汇聚至 `execDFASllWarm` (**27.5%**) 与 `ltCommonSlow` (**6.6%**) | MDO 分离 + `_adaptivePredict` 削减 42.9% + `_matchStar` / Tier 2 将 `execDFASllWarm` 再砍半至 **11.2%** |
| **`serial_parse_tree_warm`** | `EpsilonClosure.closeOne`: **8.0%**、`vtable stub`: **5.8%**、`isConflicted` lambda `compare`: **4.7%**、`HashMap.getNode`: **4.3%**、`ATNConfig.transform`: **3.8%** | 热点集中在 `closeOne` (**8.5%**)、`LongObjectHashMap.indexOf` (**4.7%**)、`compare` (**4.6%**) | 开放寻址 `PredictionContextCache` + packed `long[]` 冲突排序彻底消除 `HashMap.getNode` 与 `compare` lambda | `EpsilonClosure.closeOneDirect` (**19.1%**)、`ChildContextMap` (**6.3%**) 成为纯核心计算热点 |
| **`parallel_parse_cold_build`** | `G1ParScan::trim_queue`: **8.0%**、`closeOne`: **5.8%**、`copy_to_survivor`: **5.3%**、`vtable stub`: **4.3%**、`compare`: **3.1%** | 分配与虚调用显著下降，`vtable stub` 保持 **5.9%**、`ObjectObjectHashMap` **4.1%** | 开放寻址表与紧凑 `ATNConfig` 大幅压缩对象分配与引用图深度 | G1GC 与闭包热点高度收敛，4 线程冷启动吞吐从 `0.319` 跃升至 **`0.523 loops/s`（+64.2%）** |

### 4.2 三轮核心优化清单与设计原理

#### Round 1：消除词法谓词重复 ATN 闭包 & 基础设施去虚化（Geomean +39.0%）
1. **无锁词法谓词边决策树（`LexerPredEdgeTable`）**：Groovy 词法中 `/`（除号 vs 正则字面量）与 `'`/`"`（引号 vs GString 嵌套）带有语义谓词，基线每次遇到谓词边都会放弃 DFA 并退回 `LexerATNSimulator.execATN` 重做完整 ATN 闭包（占 25.4% CPU）。Round 1 引入每 mode 关联的 `LexerPredEdgeTable`（按 `stateNumber × ASCII[0..127]` 缓存轻量 `PredNode` 决策树，COW + `AtomicReferenceArray` 无锁读），在热路径上直接求值谓词并跳转目标 `DFAState`，彻底消除热态 ATN 回退。
2. **专用 `CodePointCharStream` 词法快速循环（`execATNFastCodePoint`）与 `ClassValue` 子类准入检测（`FAST_EXEC_ELIGIBLE`）**：在未覆写 `LexerATNSimulator` 关键钩子方法时，直接读取输入流底层数组与缓存的 `asciiEdges`（COW 发布保障跨平台 JMM 可见性），省去每字符虚调用与 `mark`/`release`。
3. **热路径流、Token 与解析器去虚化**：`CodePointCharStream` 直接 `fromString` 构造与 64 位防溢出 `getText(Interval)`；`LexerActionExecutor` 预计算 `hasPositionDependentAction` 与单内置动作执行路径 `executeBuiltIn`；`Recognizer` 错误监听器改用无锁 CAS 数组替换 `synchronized (this)`；`IntervalSet` 只读集合构建 `long[] fastBits` 实现 O(1) `contains(int)`；`ATN.precedenceLL1` 致密数组缓存左递归优先级决策。

#### Round 2：消除堆分配、G1 写屏障与 `HashMap`/排序瓶颈（相对 R1 Geomean +28.1%）
1. **分块 `TokenList` + 融合前沿拉取（`BufferedTokenStream` / `CommonTokenStream`）**：以 1024 槽固定分块数组取代 `ArrayList<Token>`，彻底消除大文件词法缓冲中占比 6–8% CPU 的 `ArrayList.grow` 反复拷贝；在 `CommonTokenStream.consume()` 与 `ltForwardFast()` 中将前沿拉取与隐藏通道跳过融合到 `TokenList.appendFast`，同时保持 `clear()` 释放超大分块防止内存滞留。
2. **零分配开放寻址 `PredictionContextCache`**：以三个单态开放寻址哈希表（`ContextInternSet`、`ChildContextMap`、`JoinContextMap`，0.5 负载因子 + O(size) 稀疏清空 + 超过 `MAX_RETAINED_CAPACITY` 自动缩容）替代原 `HashMap` + `PredictionContextAndInt` / `IdentityCommutativePredictionContextOperands` 包装对象，彻底消除 `HashMap.Node` 与临时键分配，驱动 `serial_parse_ll_warm` / `parallel_parse_tree_warm` 暴涨 **+37%～+64%**。
3. **无分配 SLL 热路径 `execDFASllWarm` 与零分配谓词求值**：热态 SLL 跳过 `this.dfa` 字段写入（消除高频 G1 写屏障）、跳过 `SimulatorState` 分配，对单 Token 无谓词接受直接返回；谓词接受通过 `evalSemanticContextSingle` / `evalSemanticContextUnique` 零 `BitSet` 分配求值。
4. **128-bit Bloom 快速排斥 + Packed `long[]` 冲突检测（`ParserATNSimulator.isConflicted`）**：绝大多数非冲突配置集经 128-bit Bloom 位掩码 O(n) 直接排斥；其余将 `(nonStopState, alt, index)` 打包进复用 `long[]` 排序（`stateNumber >= 1<<20` 自动退回双数组归并排序），彻底消灭 `ATNConfig[]` 分配与 lambda 比较器开销。
5. **8-bit 字节流专用扫描 `execATNFast8Bit` + 32 字节紧凑 `ATNConfig` + `EpsilonClosure.closeOneDirect`**：将 `passedThroughNonGreedyDecision` 放入 `ATNConfig` 基类对齐填充位（64-bit CompressedOops 下维持 32 字节对象大小，消除多态分派），手写内联 MurmurHash3 + `equals` 缓存哈希快速拒绝，并将 `EpsilonClosure` 内部展开为无虚调用的 `closeOneDirect`。

#### Round 3：Tool 代码生成深度优化 + HotSpot C2 MDO 剖面隔离 + `-> skip` 词法融合（相对 R2 Geomean +6.9%，累计 +90.4%）
1. **ALL(*) 严谨的 EBNF 出口 LL(1) 恢复（`AnalysisPipeline.recoverSingleAltEbnfExitLookahead`）**：Groovy 语法中大量 `nls*`（换行循环）后紧跟左递归表达式或带上下文谓词子规则，导致原 `LL1Analyzer` 遇谓词即放弃并回退到 `_adaptivePredict`。根据 ALL(*) 论文及 `EpsilonClosure` 语义，**仅穿透** ALL(*) 在 `depth > 0` 时必然丢弃的被调规则谓词（`PrecedencePredicateTransition` 与上下文相关 `PredicateTransition`），对上下文无关谓词保持不透明。此举 100% 保持 ALL(*) 理论完备性，同时将 `GroovyParser` 中的 `_adaptivePredict` 调用点从 **326 骤降至 186（-42.9%）**！
2. **单 Token 星号循环紧缩（`_matchStar` / `_matchStarPredict`）与紧凑 LL(1) 位掩码测试**：由 `Target.supportsCompactSingleTokenStarLoop()` / `Target.supportsCompactLL1AltTests()` 控制（仅 `JavaTarget` 开启，第三方目标默认 `false` 保持完全兼容），将 176 处重复的 `setState`/`_sync`/`while`/`_match` 内联循环与 99 处冗余 `switch` 紧缩为紧凑调用与 64-bit 位掩码测试，使 `GroovyParser` 总字节码下降 **23.4%**，超过 C2 `FreqInlineSize`（325 B）的方法减少 **47.9%**！
3. **带标签备选规则两级零拷贝优化（Tier 2a 直接子类构造 / Tier 2b `_enterDeferred` 延迟入栈）**：在 `_parseListeners == null` 且无用户 `catch`/`finally` 的热路径上，避免先分配基类 `RuleContext` 再 `copyFrom()` 到具体 `AltContext` 子类；有监听器时严格退回标准 ANTLR4 回调顺序（先 `enterEveryRule(BaseContext)`，再替换子类并触发 `exitAlt`）。
4. **扁平 O(1) `action()` / `sempred()` 分发**：消除 `GroovyLexer` 中 35 个二级分发子方法，改为单层 `tableswitch` 直接按 `actionIndex`/`predIndex` 分派，形参更名为 `_ruleIndex` 避免与用户成员/局部变量冲突，`_localctx` 相关 Parser 谓词自动抽离为强类型辅助方法（引用 `predIndex` 时自动回退）。
5. **HotSpot C2 MDO 剖面隔离（`CommonTokenStream.LT` / `BufferedTokenStream.LA`）**：经 `-XX:+LogCompilation` 实证，原 `LT(int)` 将冷的 `k==1` 缓存未命中与热的 `k!=1` 混在同一分支，导致 C2 将冷路径 `ltCommonSlow` 内联进 206 个生成的 `_match()` / `LA(1)` 调用点、挤爆 `NodeCountInliningCutoff`！拆分独立超过 35 B 的 `ltOneSlow()` / `laOneSlow()` 后，冷路径内联次数从 **206 + 507 骤降至 13 + 1（-99.8%）**！
6. **`-> skip` 原地融合（`LexerATNSimulator.execATNFast8Bit` / `execATNFastCodePoint`）**：由 `SKIP_FUSION_ELIGIBLE` 校验 `Lexer` 未覆写 `nextToken()`/`skip()` 且处于正常 `nextToken()` 入口（独立调用 `sim.match()` 保持单 Token 语义），在 DFA 热循环内部直接原地跳过内置 `-> skip` 规则（如占 Groovy 词法约 40% 匹配次数的空白符 `WS -> skip`），省去整个 `nextToken()` 往返与动作分发开销。

### 4.3 深度排错与反面经验总结（基于 `LogCompilation` 实证）

在 Round 3 攻坚过程中，我们曾遇到若干导致局部指标不升反降的微妙 JIT 陷阱，均通过 Arthas 火焰图与 `-XX:+LogCompilation` 精准定位根因并彻底攻克：
- **反例 1（已放弃）**：将 DFA 字符扫描内联进 `Lexer.scan8Bit`/`scan16Bit` —— 导致 11% 的谓词边回退触发双倍扫描，且将 `nextTokenCodePoint` 膨胀至 25.8 KB 机器码，使 `serial_lex_warm` 反降 -9%。**教训**：保留 `execATNFast8Bit` 在 `LexerATNSimulator` 内并原地执行 `resolveTargetFast` 与 `-> skip` 融合，既无回退重扫又紧凑贴合 L1i 缓存。
- **反例 2（已放弃）**：在 `execDFASllWarm` 中预读 `input.LA(2)` —— 增加字节码体积把 `DFAState.getTarget` 挤出 C2 内联预算。
- **反例 3（已放弃）**：在 LL(\*) 规则上生成 `_tryPredict`（Tier 2c）—— 将 `GroovyParser.expression(int)` 膨胀至 141 KB C2 代码并触发谓词双重预测。
- **反例 4（已攻克）**：将 `GroovyLexer.action()` 压缩至 `< 325 B` 或在 `LexerActionExecutor.execute` 中重复内联 `lexer.action()` —— 导致 C2 激进内联触发 `NodeCountInliningCutoff` 造成 `parallel_lex_warm` 双峰抖动。保持 `GroovyLexer.action()` 为 **343 B**（略高于 325 B 阈值）与单调用点后，彻底消除双峰并稳定提速 +9.0%。

---

## 5. 生成的 `GroovyLexer.java` 与 `GroovyParser.java` 代码质量与字节码对比

| 指标 | Baseline (`84f5b5fe`) | Round 3 Final | 变化幅度 | 工程收益 |
| --- | ---: | ---: | ---: | --- |
| `GroovyParser.java` 源码行数 | 18,944 行 | **17,249 行** | **-8.9%** | 代码更精炼可读 |
| `GroovyParser.class` 字节大小 | 228,335 B | **205,481 B** | **-10.0%** | 更小类加载与元数据占用 |
| `GroovyParser*.class` 全部类总大小 | 837,347 B | **755,491 B** | **-9.8%** | 显著降低 Metaspace 压力 |
| `GroovyParser` 方法总字节码大小 | 65,081 B | **49,882 B** | **-23.4%** | 大幅降低 L1i 指令缓存压力 |
| `GroovyParser` 方法体 `> 325 B` 数量 | 48 个 | **25 个** | **-47.9%** | 近半数原本不可 C2 内联的规则恢复内联能力 |
| `GroovyParser` 方法体 `<= 325 B` 数量 | 166 个 | **184 个** | **+10.8%** | 热解析规则更容易内联融合 |
| `GroovyParser` `_adaptivePredict` 调用点 | 326 处 | **186 处** | **-42.9%** | 大量 EBNF 决策升级为 O(1) 静态 LL(1) |
| `GroovyParser` `switch` 语句数量 | 166 处 | **67 处** | **-59.6%** | 2–4 分支改为紧凑位掩码与条件测试 |
| `GroovyParser` `_matchStar` / `_matchStarPredict` | 0 / 0 | **105 / 71** | +176 处紧凑化 | 消除海量重复循环样板代码 |
| `GroovyParser` `_enterDeferred` / `_matchEOF` | 0 / 0 | **7 / 1** | 零冗余分配 | 消除 LL(1) 标签规则多余 Context 分配与拷贝 |
| `GroovyLexer.java` 源码行数 | 1,730 行 | **1,504 行** | **-13.1%** | 消除 35 个冗长二级分发辅助方法 |
| `GroovyLexer.class` 字节大小 | 49,459 B | **45,090 B** | **-8.8%** | 更紧凑词法器类文件 |
| `GroovyLexer` 方法总数 | 84 个 | **49 个** | **-41.7%** | 动作与谓词扁平化为 2 个 O(1) `tableswitch` |
| `GroovyLexer` 方法总字节码大小 | 7,388 B | **6,246 B** | **-15.5%** | 降低分发栈帧与字节码开销 |

---

## 6. HPPC 封装规范与 API 兼容性审计

- **严格封装红线**：HPPC（`com.carrotsearch:hppc:0.9.1`，打包期经 Maven Shade 重定位至 `org.antlr.v4.runtime.shaded.com.carrotsearch.hppc`）**仅**用于包级私有内部结构（`ClearableLongObjectHashMap`、`ClearableIntObjectHashMap`、`OpenAddressedHashSet`、`ConcurrentIntIntMap`），外部统一面向 JDK 标准接口（`Set<ATNConfig>`、`ConcurrentMap<Integer, Integer>` 等）。
- **自动化验证结果**：
  1. `org.antlr.v4.runtime.atn.TestHppcApiBoundary`（反射遍历运行时全部类的 `public`/`protected` 类、字段、方法、构造器、泛型与注解签名）：**3/3 PASS，0 违规**。
  2. Maven 打包期 `verify-shaded-hppc` 校验：**PASS**（确认最终 JAR 中无未重定位 HPPC 类且无公共导出泄漏）。
  3. Tool 模板生成代码审计：`GroovyLexer.java`、`GroovyParser.java` 及全部 `Java.stg` 模板均 **0 处** 引用任何 HPPC 类型。
  4. 多目标扩展兼容性：新增 `Target.supportsCompactSingleTokenStarLoop()` 与 `Target.supportsCompactLL1AltTests()` 默认返回 `false`，仅 `JavaTarget` 覆写返回 `true`，保证第三方插件目标（Go/C++/Python/C#/JS 等）零破坏。

---

## 7. 三位领域专家严格复核与问题清零记录

完成 3 轮性能优化后，联合 3 位领域专家对全部 58 个改动文件进行逐行深度审计，共发现并 **100% 修复闭环** 以下关键问题（所有修复均已纳入上述最终 4 版本交叉性能验证并补充回归单测）：

| 审查领域 | 严重级别 | 发现的问题 | 修复措施 |
| --- | --- | --- | --- |
| ATN/DFA/并发（专家 #1） | **CRITICAL** | `ParserATNSimulator.execDFASllWarm` 在非接受 EOF 边调用 `repeatedEof(states, null, ...)` 时未判空 `contexts` 导致 NPE | 在 `repeatedEof` 中对 `contexts` 加空保护并补充回归单测 |
| ATN/DFA/并发（专家 #1） | **CRITICAL** | `DFAState.asciiEdges` 非 COW 直接原地写元素，弱内存模型架构（ARM64/POWER）存在读到未完全初始化 `DFAState` 的 JMM 竞态；`contextSymbols` 缺 `volatile` | `asciiEdges` 改为 `prev.clone()` COW + 单次 `volatile` 发布；`contextSymbols` 加 `volatile`；`minEdgeIndex` 缓存为 `final` |
| ATN/DFA/并发（专家 #1） | **CRITICAL** | `LexerPredEdgeTable` 原始数组行与 `ATN.modeToPredEdges` / `clearDFA()` 存在并发可见性隐患 | 行改为 `AtomicReferenceArray<PredNode>` + `byState` COW 发布 + `(asciiChar & ~0x7F) != 0` 边界校验；`clearDFA()` 置于锁保护下 |
| ATN/DFA/并发（专家 #1） | **CRITICAL** | `LexerATNSimulator` `FAST_EXEC_ELIGIBLE` 漏检 `addDFAEdge`/`addDFAState`/`getReachableTarget` 覆写；`-> skip` 融合需校验 `Lexer` 未覆写 `nextToken`/`skip` 且仅在 `nextToken()` 入口触发 | 补齐方法准入白名单并新增 `SKIP_FUSION_ELIGIBLE` + `recog._tokenStartCharIndex == startIndex` 精确门控 |
| ATN/DFA/并发（专家 #1） | **MAJOR** | `ATNConfigSet.mergeConfigContext` 翻转 `reachesIntoOuterContext` 未清缓存哈希；`PredictionContextCache.clear()` 缩容判断顺序落后于稀疏清空导致大表不缩容；`ATNConfig.computeHashCode` 未通过多态 getter 读取 `hasPassedThroughNonGreedyDecision()` | 补齐哈希失效、将大表缩容判断提前至稀疏清空之前、改调 `hasPassedThroughNonGreedyDecision()`，并规范 `null` `contextCache` 归一化为 `UNCACHED` |
| 流/词法/安全（专家 #2） | **MAJOR** | `CodePointCharStream.getText(Interval)` 极端大整型区间存在 32 位整数溢出风险；`Lexer` 动作中动态切换 `_input`（含 `more()`）边界路径需完整处理；`CommonTokenStream` 在外部截断 `fastTokens` 或子类覆写 `LB` 时可能命中过期缓存；`RuleContext.getText()` 单子节点文本为 `null` 时与 `StringBuilder` 行为不一致 | 提取 64 位防溢出 `computeTextLength(...)`；补全 `nextTokenCodePoint` / `finishMoreGeneric` 换流与 `emitEOF()` 逻辑；为 `cachedLTMinus1` / `seek` / `ltForwardFast` 加精确类与边界校验；单子节点 `null` 返回 `"null"`；`TokenList.clear()` 释放超大分块；`IntervalSet.buildFastBits()` 增加乱序防御校验；`Recognizer` 监听器改为无锁 CAS |
| Tool 代码生成/分析（专家 #3） | **CRITICAL** | `Choice.extractSingleTokenMatch` 仅判断 `instanceof MatchToken` 会误将 `.*?`/`.*`/`(~A)*`（`Wildcard`/`MatchSet` 子类）折叠导致编译失败 | 改为精确类判断 `cur.getClass() == MatchToken.class && mt.name != null && mt.ttype > 0` |
| Tool 代码生成/分析（专家 #3） | **CRITICAL** | `AnalysisPipeline.recoverSingleAltEbnfExitLookahead` 穿透上下文无关谓词会破坏 ALL(*) 谓词提升语义 | 严格限定仅穿透 ALL(*) 在 `depth > 0` 丢弃的 `PrecedencePredicateTransition` 与上下文相关 `PredicateTransition`，并编写 ALL(*) 语义健全性单测验证 |
| Tool 代码生成/分析（专家 #3） | **CRITICAL** | `Java.stg` 中 `_matchEOF()` 写 `Token.EOF` 会被名为 `Token` 的词法符号遮蔽；扁平 `action()`/`sempred()` 形参 `ruleIndex` 可能被用户成员/局部变量遮蔽；Tier 2b 需排除含 `catch`/`finally`/非空 `preamble` 的规则 | 改为 `EOF`、形参更名 `_ruleIndex`、Tier 2b 完整排除并保护 `catch` 中 `_localctx == null`、Tier 2a 保护 `if (!(_localctx instanceof ...))`、`_localctx` 谓词引用 `predIndex` 时自动回退 |

---

## 8. 单元测试、JaCoCo 执行覆盖率与 Javadoc 验证

### 8.1 全模块构建与测试汇总

| 模块 | 测试结果 | 备注 |
| --- | --- | --- |
| `runtime/JavaAnnotations` | **PASS（0 失败，0 错误）** | 完整通过 |
| `runtime/Java` | **1,220 / 1,220 PASS（0 失败，0 错误）** | 含新增 `TestTokenListAndStreamsOptimization`、`TestRuntimeOptDeepCoverage` 及 `TestHppcApiBoundary`（3/3 PASS） |
| `tool` | **1,616 / 1,616 非 AWT 测试 PASS（0 失败）** | 含新增 `TestToolCodegenAndAnalysisOptimizations`（7/7 PASS）。注：环境无 `fontconfig` 时 JDK 8 headless AWT 存在 10 项与本次改动完全无关的既有 `sun.awt.FontConfiguration.getVersion()` NPE（`TreeViewer` / `PostScriptDocument`） |
| `antlr4-maven-plugin` | **PASS（0 失败，0 错误）** | 完整通过 |
| `perf-testsuite` | **编译打包 PASS** | 与新版 runtime/tool 完全兼容 |
| Maven `verify-shaded-hppc` | **PASS** | 零未重定位/泄漏 HPPC 类 |
| `mvn javadoc:javadoc`（`runtime/Java` + `tool`） | **BUILD SUCCESS（0 新增警告）** | 所有新增与修改的 Javadoc 均无语法或断链警告 |

### 8.2 本次修改/新增代码 JaCoCo Diff-Line 执行覆盖率

基于基准 commit `84f5b5fea6868596157ad8c202c61e9c37e92555` 对全部修改与新增的 Java 源码行进行精确统计：

| 模块 | 覆盖行数 / 可执行修改行数 | **Diff-Line 行覆盖率** | 达 100% 覆盖文件数 | 典型 100% 覆盖核心文件 |
| --- | ---: | ---: | ---: | --- |
| `runtime/Java/src` | 2,587 / 2,668 | **96.96%** | **23 / 38** | `TokenList`、`Lexer`、`Parser`、`Recognizer`、`LexerActionExecutor`、`ConcurrentIntIntMap`、`HashEdgeMap`、`IntervalSet`、`ParserRuleContext`、`RuleContext`、`AbstractParseTreeVisitor` 等；且 `PredictionContextCache` **99.2%**、`CodePointCharStream` **99.1%**、`ATNConfig` **98.8%**、`LexerPredEdgeTable` **98.6%**、`CommonTokenStream` **98.3%**、`LexerATNSimulator` **97.2%**、`DFAState` **97.0%**、`ParserATNSimulator` **95.7%** |
| `tool/src` | 267 / 284 | **94.01%** | **10 / 14** | `Target`、`JavaTarget`、`FlatActionCase`、`FlatSempredCase`、`FlatSempredHelper`、`LL1StarBlockSingleAlt`、`StarBlock`、`MatchToken`、`TestSetInline`、`LL1OptionalBlock`；且 `OutputModelController` **95.1%**、`LL1AltBlock` **93.1%**、`AnalysisPipeline` **91.7%** |
| **合计（Runtime + Tool）** | **2,854 / 2,952** | **96.68%** | **33 / 52** | 剩余 3.32% 主要为 `catch (SecurityException)` 与不可达防御性空值/索引越界保护分支 |

---

## 9. 结论

本次针对 ANTLR4 优化分支的 `runtime/Java` 与 `tool` 双模块深度性能优化工作：
1. **性能全面跃升且严格单调**：在涵盖串行与并行的 11 项真实 Apache Groovy 词法/语法解析基准中实现 **3 轮 11/11（总计 33/33）严格单调递增**，综合几何平均提速 **1.904×（+90.4%）**，核心词法与并行解析场景最高达 **2.80×**；
2. **生成代码质效双优**：`GroovyParser` 字节码体量削减 **23.4%**、超阈值难内联方法削减 **47.9%**、`_adaptivePredict` 调用点削减 **42.9%**，`GroovyLexer` 方法数精简 **41.7%**；
3. **工程质量达到顶级生产标准**：100% 比特级解析等价（SHA-256 一致）、0 HPPC API 泄漏、经过 3 位领域专家严格复核并清零全部隐患、Javadoc 与架构文档同步更新、修改代码 JaCoCo 行覆盖率达 **96.68%**。
