-- V11 的真实结构没有保留期字段；夹具在新增迁移执行前准备已有文件与发布快照。
SELECT set_config('app.tenant_id', '019a0110-0000-7000-8000-000000000001', true);
INSERT INTO screens (id, tenant_id, name, owner_id, created_by, updated_by, draft, published, published_name, published_at)
VALUES ('019a0110-0000-7000-8000-000000000101', '019a0110-0000-7000-8000-000000000001', '升级前保留大屏',
        '019a0110-0000-7000-8000-000000000003', '019a0110-0000-7000-8000-000000000002', '019a0110-0000-7000-8000-000000000002',
        '{"designWidth":1920,"designHeight":1080,"backgroundColor":"#101827","backgroundImageObjectId":"019a0110-0000-7000-8000-000000000102","backgroundImageFill":"cover","widgets":[]}',
        '{"designWidth":1920,"designHeight":1080,"backgroundColor":"#101827","backgroundImageObjectId":"019a0110-0000-7000-8000-000000000102","backgroundImageFill":"cover","widgets":[]}',
        '升级前保留大屏', CURRENT_TIMESTAMP);
INSERT INTO forge_storage_object (id, tenant_id, object_key, purpose, filename, content_type, size, status, upload_expires_at, stored_at)
VALUES ('019a0110-0000-7000-8000-000000000102', '019a0110-0000-7000-8000-000000000001', '019a0110-0000-7000-8000-000000000001/lowcode-service/019a0110-0000-7000-8000-000000000102',
        'screen-image', '升级前背景.png', 'image/png', 68, 'STORED', CURRENT_TIMESTAMP - INTERVAL '1' DAY, CURRENT_TIMESTAMP - INTERVAL '1' DAY);
