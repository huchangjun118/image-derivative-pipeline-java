# image-derivative-pipeline-java

服务端图片派生管线：接收上传的图片，按请求参数生成多档尺寸的派生图并对外提供下载。
整条链路在本地完成，不依赖任何外部服务。

## 功能与处理语义

### 上传与归一化

- 支持 JPEG / PNG / GIF / BMP / WebP 等常见格式上传（multipart 或原始字节）。
- 解码前只做头部探测，**所有校验在处理开始前完成**：格式不识别、无法解码、
  尺寸超限的请求直接被拒绝，不会产出任何中间结果。
- 解码后统一归一化：
  - **方向纠正**：读取 EXIF Orientation（1-8），带方向元信息的原图按元信息摆正后再缩放；
  - **色彩归一**：非 sRGB 色彩空间（如 CMYK、线性 RGB、带 ICC profile）的图像
    使用其内嵌 profile 转换到 sRGB，处于不同色彩空间的同一张图归一后得到一致像素。

### 派生参数

| 参数      | 取值            | 说明                                       |
| --------- | --------------- | ------------------------------------------ |
| `width`   | 1..maxDimension | 目标宽                                     |
| `height`  | 1..maxDimension | 目标高                                     |
| `fit`     | `fit` / `fill`  | `fit` 等比缩放进目标框；`fill` 等比覆盖并居中裁剪 |
| `format`  | `jpeg` / `png`  | 输出编码格式                               |
| `quality` | 1..100          | JPEG 编码质量（PNG 无损，忽略该参数）      |

### 确定性输出

派生结果**只由原图内容与请求参数决定**：原图按内容哈希（SHA-256）寻址，
缓存键为 `v{cacheVersion}_{内容哈希}_{参数串}`。缩放使用固定的多步双线性算法，
编码使用固定 writer 与显式参数，重复处理、重启后重新处理、任意处理顺序与并发度下
输出均逐字节一致。

### 去重与复用

- 同一原图 + 同一参数组合只计算一次：并发请求通过单飞（single-flight）合并，
  串行请求命中磁盘缓存。
- 缓存文件格式为 `magic + 长度 + SHA-256 + payload`，写入采用临时文件 + 原子移动；
  读取时校验长度与哈希，**损坏或写入不完整的产物不会被当成有效结果**，自动重新计算。
- 升级处理逻辑时递增 `pipeline.cache-version` 即可整体失效旧缓存。

### 并发与资源上界

- 处理线程数（`pipeline.worker-threads`）与排队长度（`pipeline.queue-capacity`）均有上界；
- 上传字节数、解码像素数、解码后内存占用均有上限，单个超大图在处理开始前即被拒绝；
- 队列满时按策略处理：`block-when-full=false` 立即拒绝（429 `QUEUE_FULL`），
  `true` 时在 `offer-timeout-millis` 内有限等待，超时仍拒绝——拒绝原因可区分。

### 失败、取消与超时

- 单个派生任务超过 `processing-timeout-millis` 返回 504 `PROCESSING_TIMEOUT` 并取消底层任务；
- 处理失败返回 422 `PROCESSING_FAILED`，错误信息携带 imageId，可定位到具体请求与原图；
- 失败或取消不会写入缓存、不留下污染后续请求的中间状态，后续请求可正常重试。

### 一致读取

- 缓存写入是原子的：生成期间到达的读取请求要么拿到旧版本（未生成时 404），
  要么拿到完整的新版本，不会出现尺寸与内容对不上的混合结果；
- 历史派生结果在缓存被清理或 `cache-version` 升级之前，始终可按原参数取回。

## HTTP 接口

| 方法 | 路径                              | 说明                                       |
| ---- | --------------------------------- | ------------------------------------------ |
| POST | `/api/images`                     | 上传原图（multipart `file` 或原始字节），返回 `{id, contentHash, sizeBytes, width, height}` |
| POST | `/api/images/{id}/derivatives`    | 按参数生成派生图（幂等），响应头 `X-Cache: MISS/HIT` |
| GET  | `/api/images/{id}/derivatives`    | 读取已生成的派生图；未生成返回 404 `DERIVATIVE_NOT_FOUND` |

错误响应统一为 `{code, message, requestId, timestamp}`，主要错误码：

| HTTP | code                 | 含义                       |
| ---- | -------------------- | -------------------------- |
| 400  | `EMPTY_UPLOAD` / `INVALID_DIMENSION` / `INVALID_FORMAT` / `INVALID_QUALITY` / `INVALID_FIT` | 请求非法 |
| 404  | `IMAGE_NOT_FOUND` / `DERIVATIVE_NOT_FOUND` | 原图或派生图不存在 |
| 413  | `UPLOAD_TOO_LARGE` / `IMAGE_TOO_LARGE` | 超过字节/像素/内存上限 |
| 415  | `UNSUPPORTED_FORMAT` | 格式不识别或不支持       |
| 422  | `IMAGE_UNDECODABLE` / `PROCESSING_FAILED` | 无法解码 / 处理失败 |
| 429  | `QUEUE_FULL` / `QUEUE_INTERRUPTED` | 处理队列已满 / 排队被中断 |
| 504  | `PROCESSING_TIMEOUT` | 处理超时                 |

## 配置项（`pipeline.*`）

| 配置                              | 默认值                | 说明                             |
| --------------------------------- | --------------------- | -------------------------------- |
| `max-upload-bytes`                | 20971520 (20MB)       | 上传原图最大字节数               |
| `max-pixels`                      | 40000000              | 解码允许的最大像素数             |
| `max-dimension`                   | 8192                  | 派生图允许的最大边长             |
| `worker-threads`                  | CPU 核数一半（至少 2）| 派生处理并发线程数               |
| `queue-capacity`                  | 64                    | 排队任务上限                     |
| `block-when-full`                 | false                 | 队列满时是否有限等待             |
| `offer-timeout-millis`            | 2000                  | 有限等待的时长                   |
| `processing-timeout-millis`       | 60000                 | 单任务处理超时                   |
| `max-decoded-bytes`               | 536870912 (512MB)     | 单图解码后内存占用上限           |
| `cache-dir`                       | build/derivative-cache| 派生缓存目录                     |
| `original-dir`                    | build/originals       | 原图存储目录                     |
| `cache-version`                   | 1                     | 缓存版本号，升级处理逻辑时递增   |

## 构建与测试

```bash
mvn compile     # 编译
mvn test        # 运行全部测试（方向纠正、色彩归一、确定性、缓存复用、并发、失败取消、中断恢复、HTTP 接口）
mvn spring-boot:run
```
