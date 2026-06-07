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
            @Value("${ai.http.api-key:}") String apiKey,
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

    private record AiHttpResponse(boolean success, String fruitType, Double confidence, String message) {
    }
}
