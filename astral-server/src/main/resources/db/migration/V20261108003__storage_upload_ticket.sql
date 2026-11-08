CREATE TABLE IF NOT EXISTS sys_storage_upload_ticket (
    id BIGINT PRIMARY KEY,
    upload_id VARCHAR(64) NOT NULL UNIQUE,
    folder_id BIGINT NOT NULL,
    actor_id VARCHAR(64),
    payload_json TEXT NOT NULL,
    receipt_json TEXT,
    expires_at TIMESTAMP NOT NULL,
    state VARCHAR(24) NOT NULL,
    file_id BIGINT,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_storage_upload_ticket_pending
    ON sys_storage_upload_ticket(folder_id,state,expires_at);
INSERT INTO sequence_segment(biz_key,min_value,max_value,current_max_value,step)
SELECT 'sys_storage_upload_ticket_id',1,1000000,1000000,1000
WHERE NOT EXISTS (SELECT 1 FROM sequence_segment WHERE biz_key='sys_storage_upload_ticket_id');
