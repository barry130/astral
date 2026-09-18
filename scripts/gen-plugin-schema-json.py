# -*- coding: utf-8 -*-
"""一次性脚本：从 DDL 转录 feedback/storage 共 9 份 schema JSON（与 qt_*.json 同款格式）。"""
import json
import os

OUT = os.path.join('astral-plugin', 'src', 'main', 'resources', 'schema')


def F(col, field, ftype, jdbc, comment, **kw):
    d = {'columnName': col, 'fieldName': field, 'fieldType': ftype, 'jdbcType': jdbc, 'comment': comment}
    d.update(kw)
    return d


def PK():
    return F('id', 'id', 'Long', 'BIGINT', '主键ID',
             isPrimaryKey=True, isAutoIncrement=False, mybatisPlusIdType='INPUT')


def TS(col, field, comment, fill=None, req=False):
    d = {'columnName': col, 'fieldName': field, 'fieldType': 'LocalDateTime',
         'jdbcType': 'TIMESTAMP', 'comment': comment}
    if fill:
        d['isAutoFill'] = fill
    if req:
        d['isRequired'] = True
    return d


def V(n):
    return {'length': n}


tables = {}

tables['sys_feedback'] = {
    'tableName': 'sys_feedback', 'tableComment': '问题反馈主表', 'moduleName': 'feedback', 'className': 'Feedback',
    'fields': [
        PK(),
        F('user_id', 'userId', 'Long', 'BIGINT', '提交用户ID(sys_user.id)', isRequired=True),
        F('type', 'type', 'String', 'VARCHAR', '类型(issue问题/request需求)', isRequired=True, **V(16)),
        F('title', 'title', 'String', 'VARCHAR', '标题', isRequired=True, **V(128)),
        F('content', 'content', 'String', 'VARCHAR', '内容', isRequired=True, **V(5000)),
        F('contact', 'contact', 'String', 'VARCHAR', '联系方式', **V(64)),
        F('status', 'status', 'String', 'VARCHAR',
          '状态(pending提出/received已接收/resolved已解决/published已发布/deprecated已废弃)',
          isRequired=True, defaultValue='pending', **V(16)),
        F('is_public', 'isPublic', 'Boolean', 'BOOLEAN', '是否公开(发布后App才展示;私有仅本人可见)',
          isRequired=True, defaultValue='false'),
        F('device', 'device', 'String', 'VARCHAR', '设备型号', **V(128)),
        F('os', 'os', 'String', 'VARCHAR', '系统版本', **V(64)),
        F('app_version', 'appVersion', 'String', 'VARCHAR', 'App版本(如3.0.0)', **V(32)),
        F('platform', 'platform', 'String', 'VARCHAR', '平台(android/ios)', **V(16)),
        F('ip', 'ip', 'String', 'VARCHAR', '提交IP(服务端取)', **V(64)),
        TS('create_time', 'createTime', '创建时间', fill='INSERT'),
        TS('update_time', 'updateTime', '更新时间', fill='INSERT_UPDATE'),
        TS('delete_time', 'deleteTime', '软删除时间'),
    ],
    'indexes': [
        {'indexName': 'idx_feedback_user', 'columns': ['user_id']},
        {'indexName': 'idx_feedback_status', 'columns': ['status']},
        {'indexName': 'idx_feedback_type', 'columns': ['type']},
        {'indexName': 'idx_feedback_create_time', 'columns': ['create_time']},
    ],
}

tables['sys_feedback_reply'] = {
    'tableName': 'sys_feedback_reply', 'tableComment': '反馈回复表(双向、扁平)', 'moduleName': 'feedback', 'className': 'FeedbackReply',
    'fields': [
        PK(),
        F('feedback_id', 'feedbackId', 'Long', 'BIGINT', '反馈ID(sys_feedback.id)', isRequired=True),
        F('user_id', 'userId', 'Long', 'BIGINT', '发送者ID(用户/管理员均为sys_user.id)', isRequired=True),
        F('content', 'content', 'String', 'VARCHAR', '回复内容', isRequired=True, **V(2000)),
        TS('reply_time', 'replyTime', '回复时间(用于日期筛选)', req=True),
    ],
    'indexes': [
        {'indexName': 'idx_reply_feedback', 'columns': ['feedback_id']},
        {'indexName': 'idx_reply_time', 'columns': ['reply_time']},
        {'indexName': 'idx_reply_user', 'columns': ['user_id']},
    ],
}

tables['sys_notice'] = {
    'tableName': 'sys_notice', 'tableComment': '统一通知表(qt_app_notice超集)', 'moduleName': 'feedback', 'className': 'SysNotice',
    'fields': [
        PK(),
        F('channel', 'channel', 'String', 'VARCHAR', '渠道(app/web/all)', isRequired=True, defaultValue='app', **V(16)),
        F('notice_type', 'noticeType', 'String', 'VARCHAR', '类型(announce公告/feedback反馈/request需求)',
          isRequired=True, defaultValue='announce', **V(16)),
        F('user_id', 'userId', 'Long', 'BIGINT', '点对点目标用户ID(NULL=广播)'),
        F('feedback_id', 'feedbackId', 'Long', 'BIGINT', '关联反馈ID(sys_feedback.id)'),
        F('display', 'display', 'Long', 'BIGINT', '展示位掩码(1开屏/2通告栏/4消息中心,可叠加)',
          isRequired=True, defaultValue='4'),
        F('title', 'title', 'String', 'VARCHAR', '标题', isRequired=True, **V(128)),
        F('content', 'content', 'String', 'VARCHAR', '内容', **V(65535)),
        F('url', 'url', 'String', 'VARCHAR', '点击跳转链接', **V(512)),
        F('is_show', 'isShow', 'Long', 'BIGINT', '是否启用(0隐藏 1展示)', isRequired=True, defaultValue='1'),
        F('is_top', 'isTop', 'Long', 'BIGINT', '是否置顶(0否 1是)', isRequired=True, defaultValue='0'),
        F('dialog_closable', 'dialogClosable', 'Long', 'BIGINT', '开屏弹窗是否可关闭', isRequired=True, defaultValue='1'),
        F('first_login_only', 'firstLoginOnly', 'Long', 'BIGINT', '仅首次登录弹出', isRequired=True, defaultValue='0'),
        F('marquee', 'marquee', 'Long', 'BIGINT', '通告栏是否跑马灯', isRequired=True, defaultValue='0'),
        TS('effective_start', 'effectiveStart', '生效时间(空不限)'),
        TS('effective_end', 'effectiveEnd', '失效时间(空不限)'),
        F('version_min', 'versionMin', 'Long', 'BIGINT', '生效版本码下限(如300)'),
        F('version_max', 'versionMax', 'Long', 'BIGINT', '生效版本码上限'),
        F('audience', 'audience', 'String', 'VARCHAR', '可见人群(ALL/LOGGED_IN/NOT_LOGGED_IN)',
          isRequired=True, defaultValue='ALL', **V(16)),
        TS('create_time', 'createTime', '创建时间', fill='INSERT'),
        TS('update_time', 'updateTime', '更新时间', fill='INSERT_UPDATE'),
    ],
    'indexes': [
        {'indexName': 'idx_notice_channel', 'columns': ['channel']},
        {'indexName': 'idx_notice_type', 'columns': ['notice_type']},
        {'indexName': 'idx_notice_user', 'columns': ['user_id']},
        {'indexName': 'idx_notice_show', 'columns': ['is_show', 'is_top']},
        {'indexName': 'idx_notice_create_time', 'columns': ['create_time']},
    ],
}

tables['sys_storage_config'] = {
    'tableName': 'sys_storage_config', 'tableComment': '存储配置表', 'moduleName': 'storage', 'className': 'StorageConfigEntity',
    'fields': [
        PK(),
        F('name', 'name', 'String', 'VARCHAR', '配置名称', isRequired=True, **V(128)),
        F('provider_type', 'providerType', 'String', 'VARCHAR',
          '存储类型(TELEGRAM/R2/S3_COMPATIBLE/QINIU/COS/OSS/UPYUN)', isRequired=True, defaultValue='TELEGRAM', **V(32)),
        F('chat_id', 'chatId', 'String', 'VARCHAR', 'Telegram 接收会话ID', **V(64)),
        F('worker_base_url', 'workerBaseUrl', 'String', 'VARCHAR', 'Worker 直传服务地址', **V(256)),
        F('provider_options', 'providerOptions', 'String', 'TEXT', 'Provider 专属连接信息(JSON,非敏感)'),
        F('max_file_size', 'maxFileSize', 'Long', 'BIGINT', '单文件大小上限(字节,空=全局默认)'),
        F('status', 'status', 'String', 'VARCHAR', '状态(ENABLED启用/DISABLED停用)',
          isRequired=True, defaultValue='ENABLED', **V(16)),
        F('is_default', 'isDefault', 'Integer', 'SMALLINT', '是否默认配置(0否 1是)', isRequired=True, defaultValue='0'),
        F('health_status', 'healthStatus', 'String', 'VARCHAR', '健康状态(UNKNOWN/OK/UNREACHABLE)',
          isRequired=True, defaultValue='UNKNOWN', **V(16)),
        TS('last_test_time', 'lastTestTime', '最近连通性测试时间'),
        F('last_test_message', 'lastTestMessage', 'String', 'VARCHAR', '最近测试结果信息', **V(512)),
        F('remark', 'remark', 'String', 'VARCHAR', '备注', **V(256)),
        F('create_by', 'createBy', 'String', 'VARCHAR', '创建人', **V(64)),
        TS('create_time', 'createTime', '创建时间', fill='INSERT'),
        F('update_by', 'updateBy', 'String', 'VARCHAR', '更新人', **V(64)),
        TS('update_time', 'updateTime', '更新时间', fill='INSERT_UPDATE'),
    ],
    'indexes': [
        {'indexName': 'uk_storage_config_name', 'columns': ['name'], 'isUnique': True},
        {'indexName': 'uk_storage_config_default', 'columns': ['is_default'], 'isUnique': True},
    ],
}

tables['sys_storage_folder'] = {
    'tableName': 'sys_storage_folder', 'tableComment': '存储文件夹表(授权与组织边界)', 'moduleName': 'storage', 'className': 'StorageFolderEntity',
    'fields': [
        PK(),
        F('parent_id', 'parentId', 'Long', 'BIGINT', '父文件夹ID(空=根)'),
        F('folder_name', 'folderName', 'String', 'VARCHAR', '文件夹名称', isRequired=True, **V(128)),
        F('folder_path', 'folderPath', 'String', 'VARCHAR', '文件夹全路径', isRequired=True, defaultValue='', **V(512)),
        F('storage_config_id', 'storageConfigId', 'Long', 'BIGINT', '绑定存储配置ID', isRequired=True),
        F('owner_type', 'ownerType', 'String', 'VARCHAR', '属主类型(ADMIN/USER)', isRequired=True, defaultValue='ADMIN', **V(16)),
        F('owner_id', 'ownerId', 'String', 'VARCHAR', '属主ID', **V(64)),
        F('visibility', 'visibility', 'String', 'VARCHAR', '可见性(PUBLIC/PRIVATE)',
          isRequired=True, defaultValue='PRIVATE', **V(16)),
        F('status', 'status', 'String', 'VARCHAR', '状态(ENABLED启用)', isRequired=True, defaultValue='ENABLED', **V(16)),
        F('create_by', 'createBy', 'String', 'VARCHAR', '创建人', **V(64)),
        TS('create_time', 'createTime', '创建时间', fill='INSERT'),
        F('update_by', 'updateBy', 'String', 'VARCHAR', '更新人', **V(64)),
        TS('update_time', 'updateTime', '更新时间', fill='INSERT_UPDATE'),
    ],
    'indexes': [
        {'indexName': 'idx_storage_folder_parent', 'columns': ['parent_id']},
        {'indexName': 'idx_storage_folder_config', 'columns': ['storage_config_id']},
    ],
}

tables['sys_storage_folder_permission'] = {
    'tableName': 'sys_storage_folder_permission', 'tableComment': '文件夹授权表(扁平权限集合)', 'moduleName': 'storage', 'className': 'StorageFolderPermissionEntity',
    'fields': [
        PK(),
        F('folder_id', 'folderId', 'Long', 'BIGINT', '文件夹ID', isRequired=True),
        F('subject_type', 'subjectType', 'String', 'VARCHAR', '授权主体类型(USER/PLUGIN)', isRequired=True, **V(16)),
        F('subject_id', 'subjectId', 'String', 'VARCHAR', '授权主体ID(用户ID或插件ID)', isRequired=True, **V(64)),
        F('permissions', 'permissions', 'String', 'VARCHAR',
          '权限集合(逗号分隔:READ,UPLOAD,UPDATE,DELETE,MANAGE)', isRequired=True, **V(256)),
        TS('expires_time', 'expiresTime', '授权过期时间(空=永久)'),
        F('create_by', 'createBy', 'String', 'VARCHAR', '授权操作人', **V(64)),
        TS('create_time', 'createTime', '授权时间', fill='INSERT'),
        TS('revoked_time', 'revokedTime', '撤销时间(空=有效)'),
    ],
    'indexes': [
        {'indexName': 'uk_storage_folder_perm', 'columns': ['folder_id', 'subject_type', 'subject_id'], 'isUnique': True},
    ],
}

tables['sys_storage_file'] = {
    'tableName': 'sys_storage_file', 'tableComment': '存储文件表', 'moduleName': 'storage', 'className': 'StorageFileEntity',
    'fields': [
        PK(),
        F('public_id', 'publicId', 'String', 'VARCHAR', '对外稳定标识(不可预测)', isRequired=True, **V(48)),
        F('upload_id', 'uploadId', 'String', 'VARCHAR', '上传凭证ID(幂等键)', **V(48)),
        F('folder_id', 'folderId', 'Long', 'BIGINT', '所属文件夹ID', isRequired=True),
        F('storage_config_id', 'storageConfigId', 'Long', 'BIGINT', '存储配置ID', isRequired=True),
        F('provider_type', 'providerType', 'String', 'VARCHAR',
          '存储类型(TELEGRAM/R2/S3_COMPATIBLE/QINIU/COS/OSS/UPYUN)', isRequired=True, defaultValue='TELEGRAM', **V(32)),
        F('provider_locator_json', 'providerLocatorJson', 'String', 'TEXT',
          'Provider 专属定位信息(仅服务端与Worker链路使用,普通接口不回显)'),
        F('original_name', 'originalName', 'String', 'VARCHAR', '原始文件名', isRequired=True, defaultValue='', **V(255)),
        F('content_type', 'contentType', 'String', 'VARCHAR', 'MIME类型',
          isRequired=True, defaultValue='application/octet-stream', **V(128)),
        F('size_bytes', 'sizeBytes', 'Long', 'BIGINT', '文件大小(字节)', isRequired=True, defaultValue='0'),
        F('checksum', 'checksum', 'String', 'VARCHAR', '校验和', **V(128)),
        F('content_version', 'contentVersion', 'Long', 'BIGINT', '内容版本(缓存穿透参数)', isRequired=True, defaultValue='1'),
        F('visibility', 'visibility', 'String', 'VARCHAR', '可见性(PUBLIC/PRIVATE)',
          isRequired=True, defaultValue='PRIVATE', **V(16)),
        F('status', 'status', 'String', 'VARCHAR',
          '状态(AVAILABLE/DELETING/DELETE_FAILED/DELETED/ORPHAN_POSSIBLE/FAILED)',
          isRequired=True, defaultValue='AVAILABLE', **V(24)),
        F('uploader_type', 'uploaderType', 'String', 'VARCHAR', '上传方类型(USER/ADMIN/PLUGIN/WORKER/BROWSER)',
          isRequired=True, defaultValue='USER', **V(16)),
        F('uploader_id', 'uploaderId', 'String', 'VARCHAR', '上传者ID', **V(64)),
        TS('create_time', 'createTime', '创建时间', fill='INSERT'),
        TS('update_time', 'updateTime', '更新时间', fill='INSERT_UPDATE'),
        TS('deleted_time', 'deletedTime', '删除时间'),
    ],
    'indexes': [
        {'indexName': 'idx_storage_file_folder', 'columns': ['folder_id']},
        {'indexName': 'idx_storage_file_upload', 'columns': ['upload_id']},
        {'indexName': 'idx_storage_file_status', 'columns': ['status']},
        {'indexName': 'uk_storage_file_public_id', 'columns': ['public_id'], 'isUnique': True},
    ],
}

tables['sys_storage_task'] = {
    'tableName': 'sys_storage_task', 'tableComment': '存储任务表(远端删除补偿等)', 'moduleName': 'storage', 'className': 'StorageTaskEntity',
    'fields': [
        PK(),
        F('task_type', 'taskType', 'String', 'VARCHAR', '任务类型(DELETE_REMOTE)', isRequired=True, **V(48)),
        F('file_id', 'fileId', 'Long', 'BIGINT', '关联文件ID'),
        F('payload_json', 'payloadJson', 'String', 'TEXT', '任务负载(JSON)'),
        F('retry_count', 'retryCount', 'Integer', 'INT', '重试次数', isRequired=True, defaultValue='0'),
        TS('next_retry_time', 'nextRetryTime', '下次重试时间'),
        F('status', 'status', 'String', 'VARCHAR', '状态(PENDING/RUNNING/DONE/FAILED)',
          isRequired=True, defaultValue='PENDING', **V(16)),
        F('error_message', 'errorMessage', 'String', 'VARCHAR', '最近错误信息', **V(512)),
        TS('create_time', 'createTime', '创建时间', fill='INSERT'),
        TS('update_time', 'updateTime', '更新时间', fill='INSERT_UPDATE'),
    ],
    'indexes': [
        {'indexName': 'idx_storage_task_pickup', 'columns': ['status', 'next_retry_time']},
    ],
}

tables['sys_storage_audit'] = {
    'tableName': 'sys_storage_audit', 'tableComment': '存储审计表', 'moduleName': 'storage', 'className': 'StorageAuditEntity',
    'fields': [
        PK(),
        F('action', 'action', 'String', 'VARCHAR', '动作(FILE_UPLOAD/FILE_DELETE/FOLDER_GRANT等)',
          isRequired=True, **V(64)),
        F('subject_type', 'subjectType', 'String', 'VARCHAR', '操作主体类型', **V(16)),
        F('subject_id', 'subjectId', 'String', 'VARCHAR', '操作主体ID', **V(64)),
        F('target_type', 'targetType', 'String', 'VARCHAR', '目标类型(FILE/FOLDER)', **V(32)),
        F('target_id', 'targetId', 'String', 'VARCHAR', '目标ID', **V(64)),
        F('detail', 'detail', 'String', 'VARCHAR', '详情', **V(512)),
        F('result', 'result', 'String', 'VARCHAR', '结果(OK/FAIL)', isRequired=True, defaultValue='OK', **V(16)),
        TS('create_time', 'createTime', '时间', fill='INSERT'),
    ],
    'indexes': [
        {'indexName': 'idx_storage_audit_time', 'columns': ['create_time']},
        {'indexName': 'idx_storage_audit_action', 'columns': ['action']},
    ],
}

for name, data in tables.items():
    path = os.path.join(OUT, name + '.json')
    assert not os.path.exists(path), 'already exists: ' + path
    with open(path, 'w', encoding='utf-8', newline='\n') as fh:
        json.dump(data, fh, ensure_ascii=False, indent=2)
        fh.write('\n')
    print('written', path)
print('done:', len(tables), 'files')
