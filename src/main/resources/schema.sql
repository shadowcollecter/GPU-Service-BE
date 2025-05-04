-- Create tables for the application

-- Users table
CREATE TABLE IF NOT EXISTS users (
    user_id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(2048) NOT NULL UNIQUE,
    role VARCHAR(10) NOT NULL,
    hashed_password VARCHAR(2048),
    created_at TIMESTAMP NOT NULL,
    last_login TIMESTAMP
);

-- User usage summary table
CREATE TABLE IF NOT EXISTS user_usage_summary (
    user_id VARCHAR(50) PRIMARY KEY,
    total_used_time BIGINT NOT NULL DEFAULT 0,
    remaining_time BIGINT NOT NULL DEFAULT 360000,
    time_period_start TIMESTAMP NOT NULL,
    time_period_end TIMESTAMP NOT NULL,
    last_updated TIMESTAMP NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- Task execution record table
CREATE TABLE IF NOT EXISTS task_execution_record (
    record_id BIGSERIAL PRIMARY KEY,
    submission_id VARCHAR(50) NOT NULL UNIQUE,
    user_id VARCHAR(50) NOT NULL,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    duration BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    rejection_reason VARCHAR(11000000),
    result_path VARCHAR(1024),
    original_path VARCHAR(1024),
    resource_type VARCHAR(10),
    vram_size DOUBLE PRECISION,
    gpu_type VARCHAR(100),
    risk_score INTEGER,
    risk_message VARCHAR(20000),
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- Security check log table
CREATE TABLE IF NOT EXISTS security_check_log (
    check_id BIGSERIAL PRIMARY KEY,
    submission_id VARCHAR(50) NOT NULL,
    user_id VARCHAR(50) NOT NULL,
    risk_score INT NOT NULL,
    risk_description TEXT,
    action_taken VARCHAR(20) NOT NULL,
    check_status VARCHAR(20) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    checked_at TIMESTAMP NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- Time adjustment log table
CREATE TABLE IF NOT EXISTS time_adjustment_log (
    adjustment_id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL,
    admin_id VARCHAR(50) NOT NULL,
    adjustment_amount BIGINT NOT NULL,
    adjustment_reason VARCHAR(2048),
    adjusted_at TIMESTAMP NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(user_id),
    FOREIGN KEY (admin_id) REFERENCES users(user_id)
);

-- Announcements table
CREATE TABLE IF NOT EXISTS announcements (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(2048) NOT NULL,
    content TEXT NOT NULL,
    priority VARCHAR(10) NOT NULL DEFAULT 'NORMAL',
    start_date TIMESTAMP NOT NULL,
    end_date TIMESTAMP NOT NULL,
    created_by VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    FOREIGN KEY (created_by) REFERENCES users(user_id)
);

-- Operation log table
CREATE TABLE IF NOT EXISTS operation_log (
    log_id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL,
    action VARCHAR(100) NOT NULL,
    target_type VARCHAR(50),
    target_id VARCHAR(50),
    result VARCHAR(10) NOT NULL,
    detail TEXT,
    ip_address VARCHAR(45),
    operated_at TIMESTAMP NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- Security config table
CREATE TABLE IF NOT EXISTS security_config (
    config_id BIGSERIAL PRIMARY KEY,
    risk_threshold INT NOT NULL,
    prompt_template TEXT NOT NULL,
    fallback_policy VARCHAR(20) NOT NULL,
    updated_by VARCHAR(50) NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- GPU type table (referenced in data.sql but I don't see the entity class)
CREATE TABLE IF NOT EXISTS gpu_type (
    type VARCHAR(50) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT true
);