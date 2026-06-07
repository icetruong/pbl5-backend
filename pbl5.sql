CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- =========================
-- 1. USERS
-- =========================
CREATE TABLE users (
                       id BIGSERIAL PRIMARY KEY,
                       username VARCHAR(50) UNIQUE NOT NULL,
                       email VARCHAR(100) UNIQUE NOT NULL,
                       password_hash VARCHAR(255) NOT NULL,
                       full_name VARCHAR(100),
                       status VARCHAR(20) NOT NULL,
                       created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                       updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- =========================
-- 2. SYSTEMS
-- =========================
CREATE TABLE systems (
                         id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                         system_name VARCHAR(100) NOT NULL,
                         description VARCHAR(255),
                         location VARCHAR(100),
                         status VARCHAR(20) NOT NULL,
                         created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         user_id BIGINT,
                         CONSTRAINT fk_system_user
                             FOREIGN KEY (user_id)
                                 REFERENCES users(id)
                                 ON DELETE CASCADE
);

-- =========================
-- 3. DETECTIONS
-- =========================
CREATE TABLE detections (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            device_id VARCHAR(50) NOT NULL,
                            system_id UUID NOT NULL,
                            image_url VARCHAR(255),
                            fruit_type VARCHAR(50),
                            confidence DECIMAL(5,4),
                            target_bin VARCHAR(20),
                            status VARCHAR(30) NOT NULL,
                            ai_processing_time_ms INT,
                            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            classified_at TIMESTAMP,
                            completed_at TIMESTAMP,
                            error_message VARCHAR(500),
                            CONSTRAINT fk_detection_system
                                FOREIGN KEY (system_id)
                                    REFERENCES systems(id)
                                    ON DELETE CASCADE
);

-- =========================
-- 4. COMMAND HISTORY
-- =========================
CREATE TABLE command_history (
                                 id BIGSERIAL PRIMARY KEY,
                                 detection_id UUID,
                                 system_id UUID NOT NULL,
                                 command_type VARCHAR(50) NOT NULL,
                                 target_bin VARCHAR(20),
                                 command_payload TEXT,
                                 sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                 response_status VARCHAR(30),
                                 response_message VARCHAR(255),
                                 acknowledged_at TIMESTAMP,
                                 CONSTRAINT fk_command_detection
                                     FOREIGN KEY (detection_id)
                                         REFERENCES detections(id)
                                         ON DELETE SET NULL,
                                 CONSTRAINT fk_command_system
                                     FOREIGN KEY (system_id)
                                         REFERENCES systems(id)
                                         ON DELETE CASCADE
);

-- =========================
-- 5. NOTIFICATIONS
-- =========================
CREATE TABLE notifications (
                               id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                               system_id UUID,
                               detection_id UUID,
                               level VARCHAR(20) NOT NULL,
                               title VARCHAR(150) NOT NULL,
                               message VARCHAR(500) NOT NULL,
                               is_read BOOLEAN NOT NULL DEFAULT FALSE,
                               created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                               read_at TIMESTAMP,
                               CONSTRAINT fk_notification_system
                                   FOREIGN KEY (system_id)
                                       REFERENCES systems(id)
                                       ON DELETE CASCADE,
                               CONSTRAINT fk_notification_detection
                                   FOREIGN KEY (detection_id)
                                       REFERENCES detections(id)
                                       ON DELETE SET NULL
);

-- =========================
-- 6. FRUIT CATALOG
-- =========================
CREATE TABLE fruit_catalog (
                               id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                               name VARCHAR(50) UNIQUE NOT NULL,
                               vietnam_name VARCHAR(50)
);

-- =========================
-- 7. SYSTEM FRUIT CONFIG
-- =========================
CREATE TABLE system_fruit_config (
                                     id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                     system_id UUID NOT NULL,
                                     fruit_name VARCHAR(50) NOT NULL,
                                     target_bin VARCHAR(20) NOT NULL,
                                     updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                     CONSTRAINT fk_system_fruit_config_system
                                         FOREIGN KEY (system_id)
                                             REFERENCES systems(id)
                                             ON DELETE CASCADE,
                                     CONSTRAINT fk_system_fruit_config_fruit
                                         FOREIGN KEY (fruit_name)
                                             REFERENCES fruit_catalog(name)
                                             ON DELETE CASCADE,
                                     CONSTRAINT uq_system_fruit_config_system_fruit
                                         UNIQUE (system_id, fruit_name)
);

-- =========================
-- 8. INDEXES
-- =========================
CREATE INDEX idx_systems_user_id ON systems(user_id);
CREATE INDEX idx_detections_system_id ON detections(system_id);
CREATE INDEX idx_command_history_detection_id ON command_history(detection_id);
CREATE INDEX idx_command_history_system_id ON command_history(system_id);
CREATE INDEX idx_notifications_system_id ON notifications(system_id);
CREATE INDEX idx_notifications_detection_id ON notifications(detection_id);
CREATE INDEX idx_system_fruit_config_system_id ON system_fruit_config(system_id);
CREATE INDEX idx_system_fruit_config_fruit_name ON system_fruit_config(fruit_name);

-- =========================
-- 9. SEED FRUIT CATALOG
-- Tên (name) phải khớp đúng giá trị AI worker trả về (FRUIT_TYPE_MAP):
-- CHERRY_TOMATO / STRAWBERRY / GRAPE / BLUEBERRY. Không khớp -> REJECT_BIN.
-- =========================
INSERT INTO fruit_catalog (name, vietnam_name) VALUES
    ('BLUEBERRY',     'Việt quất'),
    ('CHERRY_TOMATO', 'Cà chua bi'),
    ('GRAPE',         'Nho'),
    ('STRAWBERRY',    'Dâu tây')
ON CONFLICT (name) DO NOTHING;