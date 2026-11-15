-- 上传是否需要本目录的 UPLOAD 授权行，独立于 READ/UPDATE/DELETE 与可见性。
-- 存量目录默认启用。仅 qt 公共头像/歌单封面取消授权行校验，原授权行保留。
ALTER TABLE sys_storage_folder
  ADD COLUMN upload_acl_required BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE sys_storage_folder AS leaf
SET upload_acl_required = FALSE,
    target_version = COALESCE(leaf.target_version, 0) + 1
FROM sys_storage_folder AS parent
WHERE leaf.parent_id = parent.id
  AND parent.folder_path = '/qt-media'
  AND parent.owner_type IN ('SYSTEM', 'ADMIN', 'PLUGIN')
  AND parent.folder_name = 'qt-media'
  AND leaf.folder_path IN ('/qt-media/avatar', '/qt-media/playlist-cover')
  AND leaf.folder_name IN ('avatar', 'playlist-cover')
  AND leaf.owner_type IN ('SYSTEM', 'ADMIN', 'PLUGIN')
  AND leaf.upload_acl_required = TRUE;

COMMENT ON COLUMN sys_storage_folder.upload_acl_required IS
'是否需要 UPLOAD 授权行；FALSE 仍校验登录、可用目标/版本、上传策略与配额，READ/UPDATE/DELETE 独立生效，默认 TRUE';
