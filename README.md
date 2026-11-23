# JobBeacon recommendation

Service gợi ý việc làm. Nó ghi tương tác của user với job, dựng graph Neo4j (Candidate, Job, Skill, Company) và projection GDS để chạy Personalized PageRank. `Jobbeacon-be` không còn chứa phần này.

## Luồng dữ liệu

```
Jobbeacon-be ──(cùng transaction)──> graph_sync_outbox (DB job_platform)
                                          │ Debezium, slot jobbeacon_graph
                                          ▼
FE ──POST /api/v1/me/job-interactions──> graph_sync_queue + user_job_interaction (DB jobbeacon_recommendation)
                                          │ mỗi 15 phút: GraphRefreshWorker
                                          ▼
            Feign /jobbeacon-be/internal/v1/** của BE ──> Neo4j ──> projection GDS
```

- BE ghi `graph_sync_outbox` khi job, CV hoặc job đã lưu thay đổi. Service này stream bảng đó bằng Debezium (slot riêng, không chung với search sync) và chép vào `graph_sync_queue`.
- Tương tác (`VIEWED`, `APPLY_CLICKED`) được ghi thẳng vào DB của service này, kèm một dòng `graph_sync_queue` trong cùng transaction.
- Worker lấy hàng đợi, gọi Feign sang BE để lấy trạng thái hiện tại (job, skill CV, job đã lưu), ghi vào Neo4j rồi dựng lại projection.
- Backfill chạy khi graph trống: lấy toàn bộ id job và candidate qua Feign rồi đưa vào hàng đợi.

## Gợi ý việc làm

`GET /api/v1/me/recommended-jobs?size=20` (`size` tối đa 50) trả `content` (job card giống danh sách job của BE), `personalized` và `matched_skills` (id job -> kỹ năng trong CV mà job cần). Không phân trang: mỗi request là một danh sách xáo trộn mới.

1. **Xếp hạng**: chạy Personalized PageRank từ node Candidate của user trên projection đang dùng, lấy top `RECOMMENDATION_POOL_SIZE` job. PageRank đi qua các job user đã lưu, bấm ứng tuyển hoặc xem để tìm job tương tự, nhưng không trả về chính các job đó. Kết quả được cache theo (user, tên projection). Projection mới có tên mới nên cache tự hết hiệu lực, vì vậy PageRank chạy tối đa một lần cho mỗi user giữa hai lần dựng lại.
2. **Bù**: nếu chưa đủ pool (user chưa có CV hay tương tác, chưa có projection, hoặc user không có trong projection) thì bù bằng các job mới nhất.
3. **Điều chỉnh** theo trạng thái hiện tại, không chờ projection: ẩn job đã lưu và job bấm ứng tuyển trong `RECOMMENDATION_APPLIED_HIDDEN_FOR`, đẩy job đã xem xuống cuối, nhân điểm theo sở thích tìm việc (vị trí, địa điểm, loại hình, lương; xem `PreferenceBoost`). Không khớp thì giảm điểm chứ không loại. Thông tin job không có thì không tính. BE so khớp và trả kết quả qua `POST /jobbeacon-be/internal/v1/recommendation/context`.
4. **Xáo trộn**: phần gợi ý cá nhân đứng trước, phần bù đứng sau. Mỗi phần được xáo ngẫu nhiên theo trọng số điểm (Gumbel-max): job có điểm cao vẫn hay đứng đầu, nhưng mỗi lần tải lại sẽ khác. Các job card giống hệt nhau (tiêu đề, công ty, địa điểm) chỉ hiện một lần.

`personalized = false` khi không có job nào đến từ graph hay khớp sở thích.

Job card lấy từ BE qua `POST /jobbeacon-be/internal/v1/jobs/summaries`.

## Xác thực

Service không tự xác minh Firebase token. Gateway (`../Jobbeacon-gateway`) làm việc đó, rồi chuyển nguyên request `/jobbeacon-recommendation/api/**` tới đây (context path của service là `/jobbeacon-recommendation`, ví dụ `/jobbeacon-recommendation/api/v1/me/job-interactions`) và gắn header `X-User-Id`. Không dùng Spring Security: controller đọc `@RequestHeader("X-User-Id") String uid`, đổi sang id user của BE bằng `BackendUserResolver.requireUserId(uid)` rồi truyền id đó xuống service. Thiếu header hoặc BE chưa có user đó (BE tạo user ở `GET /api/v1/users/me`, FE gọi ngay sau khi đăng nhập) thì trả 401. Vì service tin header này, nó **không được** lộ ra ngoài mạng nội bộ. CORS cũng chỉ xử lý ở gateway.

## Chạy local

Cần Postgres với `wal_level=logical`, Neo4j có plugin GDS, discovery server (`../Jobbeacon-discovery`, cổng `8761`) và BE đang chạy (BE phải đăng ký vào discovery). Tạo database riêng một lần:

```sql
CREATE DATABASE jobbeacon_recommendation;
```

Rồi chạy:

```bash
mvn spring-boot:run
```

Mặc định: cổng `8083`, context path `/jobbeacon-recommendation`, DB `jobbeacon_recommendation`, Eureka `http://localhost:8761/eureka/`.

Service không giữ URL của BE: `BackendClient` là `@FeignClient(name = "${app.backend.service-id}")`, Feign hỏi Eureka để lấy instance `jobbeacon-be` đang chạy và load balancer chọn một instance. Timeout cấu hình dưới `spring.cloud.openfeign.client.config.backend` (theo `contextId = "backend"`).

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/jobbeacon_recommendation` | DB của service này |
| `BACKEND_DB_URL` | `jdbc:postgresql://localhost:5432/job_platform` | DB của BE, nơi Debezium đọc outbox |
| `BACKEND_SERVICE_ID` | `jobbeacon-be` | Tên BE trong Eureka, Feign gọi theo tên này |
| `BACKEND_CONTEXT_PATH` | `/jobbeacon-be` | Context path của BE, Feign gắn vào trước mọi lời gọi (`/jobbeacon-be/internal/v1/...`) |
| `EUREKA_URL` | `http://localhost:8761/eureka/` | Địa chỉ discovery server |
| `EUREKA_ENABLED` | `true` | Tắt đăng ký/tra cứu Eureka (khi tắt thì không gọi được BE) |
| `GRAPH_CDC_ENABLED` | `true` | Tắt nếu không muốn stream outbox |
| `GRAPH_REFRESH_INTERVAL` | `PT15M` | Chu kỳ sync và dựng lại projection |
| `GRAPH_INTERACTION_WINDOW` | `P180D` | Tương tác cũ hơn không còn ảnh hưởng |
| `GRAPH_BACKFILL_ON_STARTUP` | `true` | Chỉ chạy khi graph chưa có node nào |
| `RECOMMENDATION_POOL_SIZE` | `100` | Số job tối đa trong danh sách gợi ý của một user |
| `RECOMMENDATION_APPLIED_HIDDEN_FOR` | `P14D` | Ẩn job bao lâu sau khi user bấm ứng tuyển |
| `RECOMMENDATION_SAMPLING_TEMPERATURE` | `0.5` | Mức xáo trộn mỗi lần tải: `0` = luôn đúng thứ hạng, `1` = chọn theo tỉ lệ điểm, lớn hơn thì gần ngẫu nhiên đều |

## Test

```bash
mvn test -Dtest='*Test,*IT'
```

Các test `*IT` dùng Testcontainers (Postgres, Neo4j có GDS), cần Docker.

## Lưu ý vận hành

- `GET /api/v1/health` trả 503 khi không nhận được gì từ CDC trong 2 phút (kể cả heartbeat), hoặc chưa nhận được lần nào từ lúc khởi động. Healthcheck của compose dùng endpoint này.
- Slot `jobbeacon_graph` giữ WAL khi service tắt. Tắt lâu thì đĩa Postgres tăng; bỏ hẳn service thì xóa slot: `SELECT pg_drop_replication_slot('jobbeacon_graph');`.
- Lần đầu chạy, Debezium snapshot toàn bộ các dòng outbox đã có, nên mọi entity từng thay đổi sẽ được sync lại một lần (không hỏng gì, chỉ tốn thời gian).
- Chỉ chạy một instance: worker và Debezium không có cơ chế chia việc.
