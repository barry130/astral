-- ============================================================
-- 内容管理（CMS）插件表结构（CmsSchemaInitializer 启动时自动执行，幂等）
-- 表：cms_post / cms_category / cms_tag / cms_post_tag / cms_post_category
--     / cms_page / cms_comment / cms_setting / cms_share
-- 主键由全局序列（业务键=表名_id，如 cms_post_id）自动填充，不使用数据库自增
-- 方言：PostgreSQL；slug 唯一约束用部分索引（软删后可复用别名）
-- ============================================================

CREATE TABLE IF NOT EXISTS cms_category (
    id          BIGINT PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    slug        VARCHAR(128),
    parent_id   BIGINT,
    sort        INT          NOT NULL DEFAULT 0,
    description VARCHAR(256),
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_cms_category_parent ON cms_category (parent_id);
CREATE INDEX IF NOT EXISTS idx_cms_category_slug ON cms_category (slug);

CREATE TABLE IF NOT EXISTS cms_tag (
    id          BIGINT PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    slug        VARCHAR(128),
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cms_tag_name ON cms_tag (name);
CREATE INDEX IF NOT EXISTS idx_cms_tag_slug ON cms_tag (slug);

CREATE TABLE IF NOT EXISTS cms_post (
    id           BIGINT PRIMARY KEY,
    title        VARCHAR(256) NOT NULL,
    slug         VARCHAR(128),
    summary      VARCHAR(512),
    content      TEXT,
    cover        VARCHAR(512),
    category_id  BIGINT,
    status       VARCHAR(16)  NOT NULL DEFAULT 'draft',
    is_top       BOOLEAN      NOT NULL DEFAULT FALSE,
    views        BIGINT       NOT NULL DEFAULT 0,
    author_id    BIGINT,
    published_at TIMESTAMP,
    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_time  TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_cms_post_status ON cms_post (status);
CREATE INDEX IF NOT EXISTS idx_cms_post_category ON cms_post (category_id);
CREATE INDEX IF NOT EXISTS idx_cms_post_published ON cms_post (status, published_at);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cms_post_slug ON cms_post (slug) WHERE slug IS NOT NULL AND delete_time IS NULL;

CREATE TABLE IF NOT EXISTS cms_post_tag (
    id          BIGINT PRIMARY KEY,
    post_id     BIGINT    NOT NULL,
    tag_id      BIGINT    NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cms_post_tag ON cms_post_tag (post_id, tag_id);
CREATE INDEX IF NOT EXISTS idx_cms_post_tag_tag ON cms_post_tag (tag_id);

-- 文章×分类关联（一篇文章可属于多个分类；cms_post.category_id 保留为主分类冗余列）
CREATE TABLE IF NOT EXISTS cms_post_category (
    id          BIGINT PRIMARY KEY,
    post_id     BIGINT    NOT NULL,
    category_id BIGINT    NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cms_post_category ON cms_post_category (post_id, category_id);
CREATE INDEX IF NOT EXISTS idx_cms_post_category_category ON cms_post_category (category_id);

CREATE TABLE IF NOT EXISTS cms_page (
    id           BIGINT PRIMARY KEY,
    title        VARCHAR(256) NOT NULL,
    slug         VARCHAR(128),
    content      TEXT,
    parent_id    BIGINT,
    sort         INT          NOT NULL DEFAULT 0,
    status       VARCHAR(16)  NOT NULL DEFAULT 'draft',
    views        BIGINT       NOT NULL DEFAULT 0,
    author_id    BIGINT,
    published_at TIMESTAMP,
    create_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_time  TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_cms_page_parent ON cms_page (parent_id);
CREATE INDEX IF NOT EXISTS idx_cms_page_status ON cms_page (status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cms_page_slug ON cms_page (slug) WHERE slug IS NOT NULL AND delete_time IS NULL;

CREATE TABLE IF NOT EXISTS cms_comment (
    id          BIGINT PRIMARY KEY,
    target_type VARCHAR(16)   NOT NULL,
    target_id   BIGINT        NOT NULL,
    nickname    VARCHAR(64)   NOT NULL,
    email       VARCHAR(128),
    content     VARCHAR(2000) NOT NULL,
    status      VARCHAR(16)   NOT NULL DEFAULT 'pending',
    ip          VARCHAR(64),
    user_agent  VARCHAR(256),
    create_time TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_cms_comment_target ON cms_comment (target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_cms_comment_status ON cms_comment (status);
CREATE INDEX IF NOT EXISTS idx_cms_comment_create_time ON cms_comment (create_time);

CREATE TABLE IF NOT EXISTS cms_setting (
    id            BIGINT PRIMARY KEY,
    setting_key   VARCHAR(64)   NOT NULL,
    setting_value VARCHAR(2000),
    remark        VARCHAR(256),
    create_time   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cms_setting_key ON cms_setting (setting_key);

-- 分享链接（为单篇文章/页面生成随机 token 的只读访问入口，可撤销、可设过期）
CREATE TABLE IF NOT EXISTS cms_share (
    id          BIGINT PRIMARY KEY,
    target_type VARCHAR(16) NOT NULL,
    target_id   BIGINT      NOT NULL,
    token       VARCHAR(64) NOT NULL,
    expire_at   TIMESTAMP,
    remark      VARCHAR(256),
    create_by   BIGINT,
    create_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cms_share_token ON cms_share (token);
CREATE INDEX IF NOT EXISTS idx_cms_share_target ON cms_share (target_type, target_id);

-- ============================================================
-- 列注释（PostgreSQL 方言）
-- ============================================================
COMMENT ON TABLE cms_category IS 'CMS 分类（树形）';
COMMENT ON COLUMN cms_category.id IS '主键ID';
COMMENT ON COLUMN cms_category.name IS '分类名称';
COMMENT ON COLUMN cms_category.slug IS 'URL别名(可空)';
COMMENT ON COLUMN cms_category.parent_id IS '父分类ID(NULL=根)';
COMMENT ON COLUMN cms_category.sort IS '排序(越小越靠前)';
COMMENT ON COLUMN cms_category.description IS '分类描述';
COMMENT ON COLUMN cms_category.create_time IS '创建时间';
COMMENT ON COLUMN cms_category.update_time IS '更新时间';

COMMENT ON TABLE cms_tag IS 'CMS 标签';
COMMENT ON COLUMN cms_tag.id IS '主键ID';
COMMENT ON COLUMN cms_tag.name IS '标签名(唯一)';
COMMENT ON COLUMN cms_tag.slug IS 'URL别名(可空)';
COMMENT ON COLUMN cms_tag.create_time IS '创建时间';

COMMENT ON TABLE cms_post IS 'CMS 文章';
COMMENT ON COLUMN cms_post.id IS '主键ID';
COMMENT ON COLUMN cms_post.title IS '标题';
COMMENT ON COLUMN cms_post.slug IS 'URL别名(可空,空则用id访问)';
COMMENT ON COLUMN cms_post.summary IS '摘要(空则前台截取正文)';
COMMENT ON COLUMN cms_post.content IS 'Markdown正文';
COMMENT ON COLUMN cms_post.cover IS '封面图URL';
COMMENT ON COLUMN cms_post.category_id IS '分类ID(cms_category.id)';
COMMENT ON COLUMN cms_post.status IS '状态(draft草稿/published已发布/private私密/hidden隐藏)';
COMMENT ON COLUMN cms_post.is_top IS '是否置顶';
COMMENT ON COLUMN cms_post.views IS '浏览量';
COMMENT ON COLUMN cms_post.author_id IS '作者ID(sys_user.id)';
COMMENT ON COLUMN cms_post.published_at IS '发布时间(首次发布自动写入)';
COMMENT ON COLUMN cms_post.create_time IS '创建时间';
COMMENT ON COLUMN cms_post.update_time IS '更新时间';
COMMENT ON COLUMN cms_post.delete_time IS '软删除时间';

COMMENT ON TABLE cms_post_tag IS 'CMS 文章×标签关联';
COMMENT ON COLUMN cms_post_tag.id IS '主键ID';
COMMENT ON COLUMN cms_post_tag.post_id IS '文章ID(cms_post.id)';
COMMENT ON COLUMN cms_post_tag.tag_id IS '标签ID(cms_tag.id)';
COMMENT ON COLUMN cms_post_tag.create_time IS '创建时间';

COMMENT ON TABLE cms_post_category IS 'CMS 文章×分类关联(多分类)';
COMMENT ON COLUMN cms_post_category.id IS '主键ID';
COMMENT ON COLUMN cms_post_category.post_id IS '文章ID(cms_post.id)';
COMMENT ON COLUMN cms_post_category.category_id IS '分类ID(cms_category.id)';
COMMENT ON COLUMN cms_post_category.create_time IS '创建时间';

COMMENT ON TABLE cms_page IS 'CMS 页面(Wiki层级页面)';
COMMENT ON COLUMN cms_page.id IS '主键ID';
COMMENT ON COLUMN cms_page.title IS '页面标题';
COMMENT ON COLUMN cms_page.slug IS 'URL别名(可空,空则用id访问)';
COMMENT ON COLUMN cms_page.content IS 'Markdown正文';
COMMENT ON COLUMN cms_page.parent_id IS '父页面ID(NULL=根)';
COMMENT ON COLUMN cms_page.sort IS '排序(越小越靠前)';
COMMENT ON COLUMN cms_page.status IS '状态(draft草稿/published已发布/private私密/hidden隐藏)';
COMMENT ON COLUMN cms_page.views IS '浏览量';
COMMENT ON COLUMN cms_page.author_id IS '作者ID(sys_user.id)';
COMMENT ON COLUMN cms_page.published_at IS '发布时间(首次发布自动写入)';
COMMENT ON COLUMN cms_page.create_time IS '创建时间';
COMMENT ON COLUMN cms_page.update_time IS '更新时间';
COMMENT ON COLUMN cms_page.delete_time IS '软删除时间';

COMMENT ON TABLE cms_comment IS 'CMS 评论(匿名提交+审核制)';
COMMENT ON COLUMN cms_comment.id IS '主键ID';
COMMENT ON COLUMN cms_comment.target_type IS '目标类型(post文章/page页面)';
COMMENT ON COLUMN cms_comment.target_id IS '目标ID(cms_post.id或cms_page.id)';
COMMENT ON COLUMN cms_comment.nickname IS '昵称(游客填写)';
COMMENT ON COLUMN cms_comment.email IS '邮箱(可选,仅管理端可见)';
COMMENT ON COLUMN cms_comment.content IS '评论内容(纯文本)';
COMMENT ON COLUMN cms_comment.status IS '状态(pending待审核/approved已通过/rejected已拒绝)';
COMMENT ON COLUMN cms_comment.ip IS '提交IP(服务端取,仅管理端可见)';
COMMENT ON COLUMN cms_comment.user_agent IS 'User-Agent(仅管理端可见)';
COMMENT ON COLUMN cms_comment.create_time IS '创建时间';
COMMENT ON COLUMN cms_comment.update_time IS '更新时间';

COMMENT ON TABLE cms_setting IS 'CMS 站点设置(KV)';
COMMENT ON COLUMN cms_setting.id IS '主键ID';
COMMENT ON COLUMN cms_setting.setting_key IS '配置键(site_title/site_description/footer_text/comment_enabled/comment_need_review)';
COMMENT ON COLUMN cms_setting.setting_value IS '配置值';
COMMENT ON COLUMN cms_setting.remark IS '说明';
COMMENT ON COLUMN cms_setting.create_time IS '创建时间';
COMMENT ON COLUMN cms_setting.update_time IS '更新时间';

COMMENT ON TABLE cms_share IS 'CMS 分享链接(单篇只读分享,可撤销/可设过期)';
COMMENT ON COLUMN cms_share.id IS '主键ID';
COMMENT ON COLUMN cms_share.target_type IS '目标类型(post文章/page页面)';
COMMENT ON COLUMN cms_share.target_id IS '目标ID(cms_post.id或cms_page.id)';
COMMENT ON COLUMN cms_share.token IS '分享令牌(URL安全随机串,唯一)';
COMMENT ON COLUMN cms_share.expire_at IS '过期时间(NULL=永久有效)';
COMMENT ON COLUMN cms_share.remark IS '备注(用途说明)';
COMMENT ON COLUMN cms_share.create_by IS '创建人(sys_user.id)';
COMMENT ON COLUMN cms_share.create_time IS '创建时间';
