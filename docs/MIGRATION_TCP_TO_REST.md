# Chuyển giao tiếp App ↔ AI Worker: TCP → REST API

> Mục tiêu: bỏ raw TCP socket, thay bằng HTTP REST để cả Spring App và AI Worker deploy lên cloud
> vẫn giao tiếp được. Dùng **chung một giao thức cho cả local lẫn deploy**.

---

## 0. Hợp đồng chung (cả 2 bên phải khớp đúng cái này)

**Endpoint:** `POST {base-url}/classify`

| Mục | Giá trị |
|---|---|
| Method | `POST` |
| Path | `/classify` |
| Header | `X-Api-Key: <secret>` (key bí mật dùng chung) |
| Content-Type | `application/octet-stream` |
| Body | **bytes ảnh thô** (không base64, không JSON bọc ngoài) |

**Response `200 OK` (JSON):**

```json
// thành công
{ "success": true, "fruitType": "STRAWBERRY", "confidence": 0.95 }

// thất bại (vẫn trả 200, báo lỗi trong body)
{ "success": false, "message": "lý do lỗi" }
```

**Health check:** `GET /health` → `200 OK` (cho nền tảng cloud kiểm tra sống/chết).

> `confidence` là số thực 0..1. `fruitType` là chuỗi in hoa giống bản TCP cũ.

---

## PHẦN A — Việc của tôi (Spring App)

### A1. Thêm config

`src/main/resources/application.properties`

- **Nhánh `local-ice` (chạy local — hardcode):**
  ```properties
  ai.http.base-url=http://localhost:5000
  ai.http.api-key=local-dev-key
  ai.http.connect-timeout-ms=3000
  ai.http.read-timeout-ms=30000
  ```
- **Nhánh `main` (deploy — dùng env var):**
  ```properties
  ai.http.base-url=${AI_HTTP_BASE_URL:http://localhost:5000}
  ai.http.api-key=${AI_API_KEY:}
  ai.http.connect-timeout-ms=${AI_HTTP_CONNECT_TIMEOUT_MS:3000}
  ai.http.read-timeout-ms=${AI_HTTP_READ_TIMEOUT_MS:30000}
  ```

> Xóa toàn bộ các dòng `ai.tcp.*` cũ.
> `read-timeout` để 30s vì gói cloud free có **cold start** (worker ngủ, request đầu lâu).

### A2. Thay `AiTCPClientService` bằng HTTP client

Giữ nguyên chữ ký `classify(byte[]) -> AiTCPResponse` để **`DetectionAsyncService` không phải đổi gì**
(nó vẫn gọi `aiTCPClientService.classify(imgBytes)` ở dòng 56).

```java
package com.ice.pbl5.Service;

import com.ice.pbl5.DTO.Response.AiTCPResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;

@Service
public class AiHttpClientService {
    private final RestClient restClient;
    private final String apiKey;

    public AiHttpClientService(
            @Value("${ai.http.base-url}") String baseUrl,
            @Value("${ai.http.api-key}") String apiKey,
            @Value("${ai.http.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${ai.http.read-timeout-ms:30000}") int readTimeoutMs) {
        this.apiKey = apiKey;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    public AiTCPResponse classify(byte[] imgBytes) {
        if (imgBytes == null || imgBytes.length == 0) {
            return new AiTCPResponse(false, null, null, "AI error: empty image");
        }
        try {
            AiHttpResponse body = restClient.post()
                    .uri("/classify")
                    .header("X-Api-Key", apiKey)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(imgBytes)
                    .retrieve()
                    .body(AiHttpResponse.class);

            if (body == null) {
                return new AiTCPResponse(false, null, null, "AI error: empty response");
            }
            if (!body.success()) {
                return new AiTCPResponse(false, null, null, body.message());
            }
            BigDecimal confidence = body.confidence() == null
                    ? null
                    : BigDecimal.valueOf(body.confidence());
            return new AiTCPResponse(true, body.fruitType(), confidence, null);
        } catch (Exception e) {
            // timeout / không kết nối được / lỗi parse → fallback REJECT_BIN giữ nguyên như cũ
            return new AiTCPResponse(false, null, null, "AI HTTP error: " + e.getMessage());
        }
    }

    // JSON worker trả về
    private record AiHttpResponse(boolean success, String fruitType, Double confidence, String message) {
    }
}
```

### A3. Cập nhật chỗ tiêm dependency
- `DetectionAsyncService` đang nhận `AiTCPClientService` → đổi sang `AiHttpClientService`
  (constructor + field). Không đổi logic gì khác.
- Xóa file `AiTCPClientService.java` cũ.

### A4. Dọn dẹp
- Xóa file cũ liên quan TCP (nếu còn): `AiTCPClientService.java`.
- DTO `AiTCPResponse` **giữ nguyên** (để khỏi sửa lan man). Có thể đổi tên `AiResponse` sau nếu muốn.

### A5. Test
- Unit test `AiHttpClientService` bằng mock HTTP server (OkHttp `MockWebServer` hoặc WireMock):
  case success / `success:false` / timeout / body sai → đều phải trả `AiTCPResponse` đúng.
- Test tay end-to-end: Pi → App → Worker → kết quả về SSE.

---

## PHẦN B — Việc của bạn làm Worker AI (Python)

> Hiện worker đang là **TCP server**: đọc `int length` rồi đọc `length` bytes ảnh, xử lý model,
> ghi lại `boolean success` + `fruitType` + `confidence`.
> Việc cần làm: **bọc đúng hàm model đó vào một HTTP server**. Phần load model / inference **giữ nguyên**,
> chỉ thay tầng nhận–trả dữ liệu từ TCP sang HTTP.

### B1. Cài thư viện

`requirements.txt` (thêm vào, ngoài các dep model sẵn có):
```
fastapi
uvicorn[standard]
```

### B2. File HTTP server — `server.py`

```python
import os
from fastapi import FastAPI, Request, Header, HTTPException

app = FastAPI()
API_KEY = os.getenv("AI_API_KEY", "")


def run_model(img_bytes: bytes):
    """
    TODO: gắn đúng hàm phân loại hiện có của worker vào đây.
    Input : bytes ảnh thô (giống cục bytes đang đọc từ TCP).
    Output: (fruit_type: str, confidence: float 0..1)
    """
    # ví dụ:
    # image = decode_image(img_bytes)
    # fruit_type, confidence = model.predict(image)
    # return fruit_type, float(confidence)
    raise NotImplementedError


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/classify")
async def classify(request: Request, x_api_key: str = Header(default="")):
    # kiểm tra api key (nếu có cấu hình)
    if API_KEY and x_api_key != API_KEY:
        raise HTTPException(status_code=401, detail="invalid api key")

    img_bytes = await request.body()
    if not img_bytes:
        return {"success": False, "message": "empty image"}

    try:
        fruit_type, confidence = run_model(img_bytes)
        return {
            "success": True,
            "fruitType": fruit_type,
            "confidence": float(confidence),
        }
    except Exception as e:
        return {"success": False, "message": str(e)}


if __name__ == "__main__":
    import uvicorn
    port = int(os.getenv("PORT", "5000"))
    uvicorn.run(app, host="0.0.0.0", port=port)
```

### B3. Chạy local để test
```bash
pip install -r requirements.txt
AI_API_KEY=local-dev-key python server.py
# test:
curl -X POST http://localhost:5000/classify \
     -H "X-Api-Key: local-dev-key" \
     --data-binary "@sample.jpg"
```
Kết quả mong đợi: `{"success": true, "fruitType": "...", "confidence": 0.9...}`

### B4. Dockerfile (để deploy lên cloud)

```dockerfile
FROM python:3.11-slim
WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 5000
CMD ["sh", "-c", "uvicorn server:app --host 0.0.0.0 --port ${PORT:-5000}"]
```

> Lưu ý RAM: model thị giác có thể nặng. Kiểm tra gói cloud đủ RAM/CPU (gói free thường 512MB — dễ thiếu).

### B5. Điểm quan trọng cần khớp với App
- [ ] Body request là **bytes ảnh thô** — đọc bằng `await request.body()`, KHÔNG decode base64.
- [ ] Trả JSON đúng key: `success`, `fruitType`, `confidence`, `message` (đúng tên, đúng chữ hoa/thường).
- [ ] `confidence` là float 0..1.
- [ ] Có `GET /health`.
- [ ] Đọc `AI_API_KEY` từ env và so với header `X-Api-Key`.

---

## C. Deploy (làm sau khi cả 2 phần xong)

1. Deploy Worker thành 1 service riêng trên cloud (Docker). Đặt env `AI_API_KEY=<secret>`.
2. Trên Spring App đặt env:
   - `AI_HTTP_BASE_URL` = URL của worker (nội bộ nếu nền tảng hỗ trợ private network, không thì HTTPS public).
   - `AI_API_KEY` = đúng secret như worker.
3. Health check trỏ `/health`.
4. Nếu worker dùng gói free hay ngủ → để `AI_HTTP_READ_TIMEOUT_MS` cao (30s) cho lần gọi đầu.

---

## D. Checklist tổng

**App (tôi):**
- [ ] Thêm config `ai.http.*`, xóa `ai.tcp.*`
- [ ] Thêm `AiHttpClientService`, xóa `AiTCPClientService`
- [ ] Đổi inject trong `DetectionAsyncService`
- [ ] Test (mock server + end-to-end)

**Worker (bạn):**
- [ ] Thêm `fastapi` + `uvicorn`
- [ ] `server.py` với `/classify` + `/health`, gắn `run_model`
- [ ] Kiểm tra `X-Api-Key`
- [ ] `Dockerfile`
- [ ] Test bằng `curl`
