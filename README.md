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
            Feign /internal/v1/** của BE ──> Neo4j ──> projection GDS
```

- BE ghi `graph_sync_outbox` khi job, CV hoặc job đã lưu thay đổi. Service này stream bảng đó bằng Debezium (slot riêng, không chung với search sync) và chép vào `graph_sync_queue`.
- Tương tác (`VIEWED`, `APPLY_CLICKED`) được ghi thẳng vào DB của service này, kèm một dòng `graph_sync_queue` trong cùng transaction.
- Worker lấy hàng đợi, gọi Feign sang BE để lấy trạng thái hiện tại (job, skill CV, job đã lưu), ghi vào Neo4j rồi dựng lại projection.
- Backfill chạy khi graph trống: lấy toàn bộ id job và candidate qua Feign rồi đưa vào hàng đợi.

## Chạy local

Cần Postgres với `wal_level=logical`, Neo4j có plugin GDS và BE đang chạy. Tạo database riêng một lần:

```sql
CREATE DATABASE jobbeacon_recommendation;
```

Đặt `firebase-service-account.json` (giống BE) ở thư mục gốc, rồi:

```bash
mvn spring-boot:run
```

Mặc định: cổng `8083`, DB `jobbeacon_recommendation`, BE `http://localhost:8080`, token internal `local-internal-token` (phải trùng `INTERNAL_API_TOKEN` của BE).

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/jobbeacon_recommendation` | DB của service này |
| `BACKEND_DB_URL` | `jdbc:postgresql://localhost:5432/job_platform` | DB của BE, nơi Debezium đọc outbox |
| `BACKEND_URL` | `http://localhost:8080` | BE cho Feign |
| `INTERNAL_API_TOKEN` | `local-internal-token` | Header `X-Internal-Token` khi gọi `/internal/**` |
| `GRAPH_CDC_ENABLED` | `true` | Tắt nếu không muốn stream outbox |
| `GRAPH_REFRESH_INTERVAL` | `PT15M` | Chu kỳ sync và dựng lại projection |
| `GRAPH_INTERACTION_WINDOW` | `P180D` | Tương tác cũ hơn không còn ảnh hưởng |
| `GRAPH_BACKFILL_ON_STARTUP` | `true` | Chỉ chạy khi graph chưa có node nào |

## Test

```bash
mvn test -Dtest='*Test,*IT'
```

Các test `*IT` dùng Testcontainers (Postgres, Neo4j có GDS), cần Docker.

## Lưu ý vận hành

- Slot `jobbeacon_graph` giữ WAL khi service tắt. Tắt lâu thì đĩa Postgres tăng; bỏ hẳn service thì xóa slot: `SELECT pg_drop_replication_slot('jobbeacon_graph');`.
- Lần đầu chạy, Debezium snapshot toàn bộ các dòng outbox đã có, nên mọi entity từng thay đổi sẽ được sync lại một lần (không hỏng gì, chỉ tốn thời gian).
- Chỉ chạy một instance: worker và Debezium không có cơ chế chia việc.
