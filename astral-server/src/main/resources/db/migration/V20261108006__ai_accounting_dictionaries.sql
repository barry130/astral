WITH seed(code,name) AS (VALUES ('ai_usage_status','AI 用量状态'),('ai_tool_call_status','AI 工具调用状态')),
missing AS (SELECT * FROM seed s WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type t WHERE t.dict_code=s.code))
INSERT INTO sys_dict_type(id,dict_code,dict_name,status)
SELECT (SELECT COALESCE(MAX(id),0) FROM sys_dict_type)+ROW_NUMBER() OVER(ORDER BY code),code,name,1 FROM missing;

WITH seed(code,value,label,sort) AS (VALUES
 ('ai_usage_status','RESERVED','预占',1),('ai_usage_status','SETTLED','已结算',2),
 ('ai_usage_status','UNKNOWN','待核对',3),('ai_usage_status','RELEASED','已释放',4),
 ('ai_tool_call_status','WAITING_APPROVAL','待确认',1),('ai_tool_call_status','RUNNING','执行中',2),
 ('ai_tool_call_status','SUCCEEDED','已完成',3),('ai_tool_call_status','FAILED','失败',4),
 ('ai_tool_call_status','REJECTED','已拒绝',5),('ai_tool_call_status','UNKNOWN','待核对',6),
 ('ai_tool_call_status','NEED_CLIENT','等待客户端',7)),
missing AS (SELECT t.id type_id,s.* FROM seed s JOIN sys_dict_type t ON t.dict_code=s.code
 WHERE NOT EXISTS(SELECT 1 FROM sys_dict_data d WHERE d.dict_type_id=t.id AND d.dict_value=s.value))
INSERT INTO sys_dict_data(id,dict_type_id,dict_value,dict_label,dict_sort,status)
SELECT (SELECT COALESCE(MAX(id),0) FROM sys_dict_data)+ROW_NUMBER() OVER(ORDER BY code,sort),type_id,value,label,sort,1 FROM missing;

UPDATE sequence_segment SET max_value=GREATEST(max_value,(SELECT COALESCE(MAX(id),0) FROM sys_dict_type)+step)
WHERE biz_key='sys_dict_type_id';
UPDATE sequence_segment SET max_value=GREATEST(max_value,(SELECT COALESCE(MAX(id),0) FROM sys_dict_data)+step)
WHERE biz_key='sys_dict_data_id';
