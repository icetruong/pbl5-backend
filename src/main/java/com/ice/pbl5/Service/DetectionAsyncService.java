package com.ice.pbl5.Service;

import com.ice.pbl5.DTO.Response.AiTCPResponse;
import com.ice.pbl5.DTO.Response.DetectionResponse;
import com.ice.pbl5.DTO.Response.DeviceCommandResponse;
import com.ice.pbl5.Entity.Detection;
import com.ice.pbl5.Entity.FruitCatalog;
import com.ice.pbl5.Enum.DetectionStatus;
import com.ice.pbl5.Enum.NotificationLevel;
import com.ice.pbl5.Exception.ResourceNotFoundException;
import com.ice.pbl5.Repository.DetectionRepo;
import com.ice.pbl5.Repository.FruitCatalogRepo;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class DetectionAsyncService {
    private static final BigDecimal CONFIDENCE_THRESHOLD = new BigDecimal("0.5");

    private final DetectionRepo detectionRepo;
    private final AiHttpClientService aiHttpClientService;
    private final CommandService commandService;
    private final NotificationService notificationService;
    private final FruitCatalogRepo fruitCatalogRepo;
    private final SSEService sseService;
    private final ImgUrlService imgUrlService;
    private final ImageStorageService imageStorageService;

    public DetectionAsyncService(DetectionRepo detectionRepo, AiHttpClientService aiHttpClientService, CommandService commandService, NotificationService notificationService, FruitCatalogRepo fruitCatalogRepo, SSEService sseService, ImgUrlService imgUrlService, ImageStorageService imageStorageService) {
        this.detectionRepo = detectionRepo;
        this.aiHttpClientService = aiHttpClientService;
        this.commandService = commandService;
        this.notificationService = notificationService;
        this.fruitCatalogRepo = fruitCatalogRepo;
        this.sseService = sseService;
        this.imgUrlService = imgUrlService;
        this.imageStorageService = imageStorageService;
    }

    @Async("ai-worker")
    public void processDetection(UUID detectionId, String requestId) {
        Detection detection = detectionRepo.findById(detectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Detection not found"));
        LocalDateTime startTime = LocalDateTime.now();
        try {
            detection.setStatus(DetectionStatus.PROCESSING);
            detectionRepo.save(detection);

            byte[] imgBytes = imageStorageService.readImage(detection.getImageUrl());
            AiTCPResponse aiTCPResponse = aiHttpClientService.classify(imgBytes);

            int processingTime = (int) Duration.between(startTime, LocalDateTime.now()).toMillis();
            detection.setAiProcessingTimeMs(processingTime);

            if (!aiTCPResponse.isSuccess()) {
                detection.setFruitType("UNKNOWN");
                detection.setConfidence(BigDecimal.ZERO);
                detection.setTargetBin("REJECT_BIN");
                detection.setClassifiedAt(LocalDateTime.now());
                detection.setErrorMessage(aiTCPResponse.getMessage());

                DeviceCommandResponse fallbackResponse = commandService.executeSortCommand(detection, requestId);
                if (!fallbackResponse.isSuccess()) {
                    detection.setStatus(DetectionStatus.FAILED);
                    detection.setCompletedAt(LocalDateTime.now());
                    detection.setErrorMessage("AI failed and cannot send fallback result: " + fallbackResponse.getMessage());
                    detectionRepo.save(detection);

                    notificationService.createNotification(
                            detection,
                            NotificationLevel.ERROR,
                            "AI classification failed",
                            aiTCPResponse.getMessage()
                    );
                    return;
                }

                detection.setStatus(DetectionStatus.COMPLETED);
                detection.setCompletedAt(LocalDateTime.now());
                detectionRepo.save(detection);

                sseService.boardcast(detection.getSystem().getId(), "new-detection", toDetectionResponse(detection));

                notificationService.createNotification(
                        detection,
                        NotificationLevel.ERROR,
                        "AI classification failed",
                        aiTCPResponse.getMessage()
                );
                return;
            }

            BigDecimal confidence = aiTCPResponse.getConfidence();
            detection.setFruitType(aiTCPResponse.getFruitType());
            detection.setClassifiedAt(LocalDateTime.now());
            detection.setTargetBin(mapTargetBin(detection.getFruitType(), confidence));
            detection.setConfidence(confidence == null ? BigDecimal.ZERO : confidence);

            if (confidence == null || confidence.compareTo(CONFIDENCE_THRESHOLD) < 0) {
                notificationService.createNotification(
                        detection,
                        NotificationLevel.WARNING,
                        "Độ tin cậy thấp",
                        "Phân loại trái cây có độ tin cậy thấp: " + confidence
                );
            }

            DeviceCommandResponse response = commandService.executeSortCommand(detection, requestId);
            if (!response.isSuccess()) {
                detection.setStatus(DetectionStatus.FAILED);
                detection.setCompletedAt(LocalDateTime.now());
                detection.setErrorMessage(response.getMessage());
                detectionRepo.save(detection);

                notificationService.createNotification(
                        detection,
                        NotificationLevel.ERROR,
                        "Device command failed",
                        response.getMessage()
                );
                return;
            }

            detection.setStatus(DetectionStatus.COMPLETED);
            detection.setCompletedAt(LocalDateTime.now());
            detectionRepo.save(detection);
        } catch (Exception e) {
            detection.setStatus(DetectionStatus.FAILED);
            detection.setErrorMessage(e.getMessage());
            detection.setCompletedAt(LocalDateTime.now());
            detectionRepo.save(detection);
        }

        sseService.boardcast(detection.getSystem().getId(), "new-detection", toDetectionResponse(detection));
    }

    private DetectionResponse toDetectionResponse(Detection detection) {
        return new DetectionResponse(
                detection.getId(),
                detection.getFruitType(),
                detection.getConfidence(),
                detection.getTargetBin(),
                detection.getClassifiedAt(),
                imgUrlService.buildImgUrl(detection.getImageUrl())
        );
    }

    private String mapTargetBin(String fruitType, BigDecimal confidence) {
        if (fruitType == null || fruitType.isBlank()) {
            return "REJECT_BIN";
        }

        String normalizedFruitType = fruitType.trim();
        // ORDER BY name để thứ tự BIN cố định (findAll() không order → thứ tự tùy ý, có thể đổi)
        List<FruitCatalog> fruitCatalogs = fruitCatalogRepo.findAll(Sort.by(Sort.Direction.ASC, "name"));
        for (int i = 0; i < fruitCatalogs.size(); i++) {
            FruitCatalog fruitCatalog = fruitCatalogs.get(i);
            if (fruitCatalog.getName() != null && fruitCatalog.getName().trim().equalsIgnoreCase(normalizedFruitType)) {
                return "BIN_" + (i + 1);
            }
        }

        return "REJECT_BIN";
    }
}
