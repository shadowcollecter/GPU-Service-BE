INSERT INTO users(user_id, name, email, role, hashed_password, created_at) VALUES
('admin', 'Admin', 'admin', 'ADMIN', '$2a$10$NiPY3.zocFBdIAtblB8h0u/NxXXBaeXmg3zeb4.tP/WrfHQcM73lK', CURRENT_TIMESTAMP)
ON CONFLICT (user_id) DO NOTHING;