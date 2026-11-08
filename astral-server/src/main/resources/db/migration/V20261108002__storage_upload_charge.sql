-- Legacy direct uploads need a consumption fact independent of the removable file row.
CREATE TABLE IF NOT EXISTS sys_storage_upload_charge (
    id BIGINT PRIMARY KEY,
    upload_id VARCHAR(64) NOT NULL UNIQUE,
    file_id BIGINT NOT NULL,
    folder_id BIGINT NOT NULL,
    actor_id VARCHAR(64),
    quota_day DATE NOT NULL,
    charge BOOLEAN NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_storage_upload_charge_quota
    ON sys_storage_upload_charge(folder_id, actor_id, quota_day) WHERE charge;
INSERT INTO sys_storage_upload_charge(id,upload_id,file_id,folder_id,actor_id,quota_day,charge,create_time)
SELECT id, COALESCE(upload_id, 'legacy-file-' || id), id, folder_id, uploader_id,
       CAST(COALESCE(create_time,CURRENT_TIMESTAMP) AS DATE), status <> 'FAILED', COALESCE(create_time,CURRENT_TIMESTAMP)
FROM sys_storage_file WHERE upload_operation_id IS NULL
ON CONFLICT (upload_id) DO NOTHING;
INSERT INTO sequence_segment(biz_key,min_value,max_value,current_max_value,step)
SELECT 'sys_storage_upload_charge_id', 1, GREATEST(COALESCE(MAX(id),0),1000000), GREATEST(COALESCE(MAX(id),0),1000000), 1000
FROM sys_storage_upload_charge ON CONFLICT (biz_key) DO NOTHING;
