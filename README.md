# PBL5 Backend (Spring Boot)

Backend cho he thong phan loai trai cay bang AI.

## 1) Cong nghe

- Java 21
- Spring Boot 4.0.3
- Spring Web MVC
- Spring Security + JWT
- Spring Data JPA (Hibernate)
- PostgreSQL
- WebSocket
- Maven Wrapper (`mvnw`, `mvnw.cmd`)

## 2) Cau truc thu muc

```text
src/main/java/com/ice/pbl5
|- Config/
|- Controller/
|- DTO/
|- Entity/
|- Enum/
|- Exception/
|- Mapper/
|- Repository/
|- Service/
`- Util/

src/main/resources
`- application.properties
```

## 3) Chuc nang chinh

- Dang ky / dang nhap / lay thong tin user (`/api/auth/**`)
- Quan ly system (`/api/systems/**`)
- Dieu khien conveyor (`/api/system/control`)
- Nhan anh tu hardware qua WebSocket (`/ws/device`)
- Goi AI service qua TCP de phan loai
- Luu lich su detection + thong ke
- Quan ly notification

## 4) Cau hinh

File: `src/main/resources/application.properties`

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/pbl5
spring.datasource.username=postgres
spring.datasource.password=123456

jwt.secret=...
jwt.expiration=86400000

server.address=0.0.0.0
server.port=8080

app.base-url=http://localhost:8080

ai.http.base-url=http://localhost:5000
ai.http.api-key=local-dev-key
ai.http.connect-timeout-ms=3000
ai.http.read-timeout-ms=30000
```

## 5) Chay local

Yeu cau:
- JDK 21
- PostgreSQL dang chay
- Co database `pbl5`

Tao DB:

```sql
CREATE DATABASE pbl5;
```

Chay app (Windows):

```bash
.\mvnw.cmd spring-boot:run
```

App mac dinh:
- HTTP API: `http://localhost:8080`
- WebSocket: `ws://localhost:8080/ws/device`

## 6) Security

- Public endpoints:
  - `POST /api/auth/register`
  - `POST /api/auth/login`
  - `/ws/**`
- Cac endpoint con lai yeu cau JWT Bearer token

Header:

```http
Authorization: Bearer <access_token>
```

## 7) API hien tai

### 7.1 Auth

- `POST /api/auth/register`
- `POST /api/auth/login`
- `GET /api/auth/me`

### 7.2 Systems

- `GET /api/systems`
- `POST /api/systems/register?name=...&description=...&location=...`

### 7.3 Control

- `POST /api/system/control?systemId=<uuid>`
  - body:
  ```json
  { "action": "START" }
  ```
- `GET /api/system/control-state?systemId=<uuid>`

### 7.4 Detections

- `GET /api/detections/latest?systemId=<uuid>`
- `GET /api/detections/recent?systemId=<uuid>&limit=10`
- `GET /api/detections/count-by-fruit?systemId=<uuid>&from=<iso>&to=<iso>`
- `GET /api/detections/statistics-summary?systemId=<uuid>&from=<iso>&to=<iso>`
- `GET /api/detections/statistics-daily?systemId=<uuid>&from=<iso>&to=<iso>`
- `GET /api/detections?systemId=<uuid>&page=0&size=10&fruitType=...&status=COMPLETED&from=<iso>&to=<iso>`
- `GET /api/detections/{id}?systemId=<uuid>`

### 7.5 Notifications

- `GET /api/notifications?systemId=<uuid>&level=WARNING&page=0&size=10`
- `PATCH /api/notifications/{id}/read?systemId=<uuid>`
- `PATCH /api/notifications/read-all?systemId=<uuid>`
- `GET /api/notifications/unread-count?systemId=<uuid>`

## 8) WebSocket protocol (`/ws/device`)

### 8.1 Hardware -> Backend

Register:

```json
{
  "type": "register",
  "systemId": "11111111-1111-1111-1111-111111111111"
}
```

Predict:

```json
{
  "type": "predict",
  "systemId": "11111111-1111-1111-1111-111111111111",
  "requestId": "req-001",
  "imageBase64": "data:image/jpeg;base64,..."
}
```

### 8.2 Backend -> Hardware

Register ACK:

```json
{
  "type": "register",
  "systemId": "11111111-1111-1111-1111-111111111111",
  "status": "success"
}
```

Accepted:

```json
{
  "type": "accepted",
  "systemId": "11111111-1111-1111-1111-111111111111",
  "requestId": "req-001",
  "status": "processing"
}
```

Result (sort command):

```json
{
  "type": "result",
  "systemId": "11111111-1111-1111-1111-111111111111",
  "requestId": "req-001",
  "fruitType": "APPLE",
  "confidence": 0.92,
  "targetBin": "BIN_1"
}
```

Control command:

```json
{
  "type": "command",
  "systemId": "11111111-1111-1111-1111-111111111111",
  "command": "START_CONVEYOR"
}
```

Error:

```json
{
  "type": "error",
  "systemId": "11111111-1111-1111-1111-111111111111",
  "requestId": "req-001",
  "status": "failed",
  "message": "..."
}
```

## 9) AI TCP protocol

Backend gui sang AI:
1. `int` 4 byte: kich thuoc anh
2. `byte[]`: du lieu anh

AI tra ve:
- `boolean success`
- Neu `false`: `UTF errorMessage`
- Neu `true`: `UTF fruitType` + `double confidence`

## 10) Enum chinh

- `DetectionStatus`: `RECEIVED`, `PROCESSING`, `COMPLETED`, `FAILED`
- `SystemStatus`: `RUNNING`, `PAUSED`, `STOPPED`, `ERROR`, `IDLE`
- `SystemAction`: `START`, `PAUSE`, `STOP`
- `NotificationLevel`: `INFO`, `WARNING`, `ERROR`
- `CommandType`: `SORT`, `START_CONVEYOR`, `PAUSE_CONVEYOR`, `STOP_CONVEYOR`
- `CommandStatus`: `SENT`, `ACK_SUCCESS`, `ACK_FAILED`, `ERROR`
- `UserStatus`: `ACTIVE`, `INACTIVE`

## 11) Test

```bash
.\mvnw.cmd test
```

Neu database `pbl5` chua ton tai, test co the fail.
