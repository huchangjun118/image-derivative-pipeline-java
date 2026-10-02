# 图片派生处理流水线（image-derivative-pipeline-java）

服务端图片处理能力：接收上传图片，在本地完成方向纠正与色彩归一，按请求
生成多档尺寸/编码参数的派生图并提供下载。整条链路不依赖任何外部服务。

- 技术栈：Java 17、Spring Boot 3.4、`javax.imageio`（JPEG/PNG 编解码）
- 构建：`mvn package`
- 运行：`mvn spring-boot:run`（默认端口 8080，`application.properties` 中可改）

## 处理语义

1. **接收与解码**：支持 JPEG / PNG。上传后先校验大小，再用 ImageIO 显式
   读取元信息与像素；格式损坏、截断、无法解码的内容在处理开始前被拒绝
  （`DECODE_FAILED`），不会产出半成品。
2. **方向纠正**：从 JPEG 的 APP1/EXIF 段（或 PNG 的 eXIf 块）解析方向标签
  （1–8），在缩放前按标签做整数旋转/翻转（最近邻，无插值误差）。无方向
   信息时原样返回。
3. **色彩归一**：任意色彩空间（灰度、线性 RGB、内嵌 ICC、索引色等）统一
   转换到 sRGB（`ColorConvertOp` 显式转换，特殊色彩模型回退绘制转换）。
   处于不同色彩空间的同一张图归一后像素一致。
4. **派生生成**：支持 `CONTAIN`（完整放入、留白）、`COVER`（填满、居中
   裁剪）、`STRETCH`（拉伸）三种适配模式；目标格式 JPEG（质量参数 0.01–1）
   或 PNG。JPEG 不支持透明，alpha 通道压白底；PNG 保留 alpha。
5. **确定性**：派生图标识 = SHA-256(原图 SHA-256 + 规范化参数串)。编码时
   不写任何元数据、JPEG 质量显式指定，因此相同输入逐字节一致——与处理
   顺序、并发度无关，重启后重新处理也一致。
6. **去重与复用**：同一原图 + 同一参数组合只计算一次（进程内 in-flight
   合并 + 磁盘缓存）。产物落盘为原子移动（临时文件 + fsync + 原子 rename），
   读取时校验 SHA-256 与元数据；损坏或写入不完整的产物不会被当成有效结果，
   自动重新计算。派生结果按最后访问时间与 TTL 过期。
7. **并发与容量**：固定工作线程 + 有界队列。队列满时拒绝并返回
   `CAPACITY_EXCEEDED`（503）；单任务有超时（`PROCESSING_TIMEOUT`），
   超时/取消会中断任务并清理临时文件，不污染后续请求。内存中仅缓存少量
   已解码原图（LRU，容量=工作线程数），单图像素有上限。
8. **一致读取**：新派生图生成期间，读取要么看到旧产物、要么看到新产物
   （原子发布），不会出现尺寸与内容对不上的混合结果。
9. **错误定位**：所有失败携带错误码与 `imageId` / `derivativeId` /
   `requestId`，通过响应体可直接定位到具体请求与原图。

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/images` | 上传原图（multipart `file` 字段），返回 `imageId` 与元信息 |
| GET | `/api/images/{imageId}` | 获取原图元信息 |
| POST | `/api/images/{imageId}/derivatives` | 生成/复用派生图，请求体见下 |
| GET | `/api/derivatives/{derivativeId}` | 下载派生图字节 |
| GET | `/api/stats` | 线程池/队列/缓存统计 |

派生请求体（JSON）：

```json
{
  "width": 200,
  "height": 200,
  "fitMode": "COVER",
  "format": "JPEG",
  "quality": 0.8
}
```

- `fitMode`：`CONTAIN`（默认）/ `COVER` / `STRETCH`
- `format`：`JPEG`（默认）/ `PNG`
- `quality`：仅 JPEG 生效，默认 0.8

错误响应体：

```json
{
  "errorCode": "INVALID_PARAMETER",
  "message": "...",
  "imageId": "...",
  "derivativeId": "...",
  "requestId": "...",
  "timestamp": "..."
}
```

错误码与 HTTP 状态：

| 错误码 | 状态 | 含义 |
| --- | --- | --- |
| `DECODE_FAILED` | 400 | 图片无法解码或已损坏 |
| `UNSUPPORTED_FORMAT` | 400 | 不支持的图片格式 |
| `SOURCE_TOO_LARGE` | 400 | 原图尺寸/上传大小超限 |
| `INVALID_PARAMETER` | 400 | 请求参数非法 |
| `IMAGE_NOT_FOUND` | 404 | 原图不存在 |
| `DERIVATIVE_NOT_FOUND` | 404 | 派生结果不存在或已损坏 |
| `CAPACITY_EXCEEDED` | 503 | 处理容量已达上限 |
| `PROCESSING_TIMEOUT` | 504 | 处理超时 |
| `PROCESSING_CANCELLED` | 500 | 处理被取消 |
| `PROCESSING_FAILED` | 500 | 处理失败 |

## 配置项（`application.properties`）

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `pipeline.storage-dir` | `data` | 产物存储根目录 |
| `pipeline.max-upload-bytes` | 20971520（20MB） | 上传大小上限 |
| `pipeline.max-source-dimension` | 8000 | 原图单边像素上限 |
| `pipeline.max-source-pixels` | 40000000 | 原图总像素上限 |
| `pipeline.max-target-dimension` | 4096 | 派生图单边像素上限 |
| `pipeline.max-target-pixels` | 16000000 | 派生图总像素上限 |
| `pipeline.worker-threads` | CPU 核数 | 处理线程数（并发上界） |
| `pipeline.queue-capacity` | 128 | 等待队列容量，超过即拒绝 |
| `pipeline.task-timeout-millis` | 30000 | 单任务处理超时（毫秒） |
| `pipeline.cache-ttl-millis` | 604800000（7 天） | 派生结果过期时间，按最后访问时间续期 |

## 产物存储布局

```
data/
├── originals/{imageId}.bin              # 原图字节（imageId = 内容 SHA-256）
└── derivatives/
    ├── {derivativeId}.bin                # 派生图字节
    └── {derivativeId}.meta               # 元数据（参数、SHA-256、时间戳）
```

## 测试

`mvn test` 覆盖：EXIF 方向解析（1–8）、方向纠正端到端、色彩归一（灰度/
线性 RGB）、确定性（重复/并发/重启逐字节一致）、缓存复用、缓存损坏重算、
临时文件清理、并发完成、容量拒绝、超时取消、一致性读取、参数校验、TTL
过期，以及 REST 接口端到端（上传、派生、下载、错误映射）。
