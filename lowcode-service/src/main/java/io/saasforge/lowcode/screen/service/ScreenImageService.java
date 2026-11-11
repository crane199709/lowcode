package io.saasforge.lowcode.screen.service;

import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.storage.DownloadTicket;
import io.saasforge.sdk.storage.ObjectStorage;
import io.saasforge.sdk.storage.StoragePurpose;
import io.saasforge.sdk.storage.StoredObject;
import io.saasforge.sdk.storage.UploadTicket;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ScreenImageService {
    // 排除 SVG 等主动内容；草稿与快照只持久化对象 ID，URL 由客户端在到期前刷新。
    public static final StoragePurpose PURPOSE = new StoragePurpose(
            "screen-image", Set.of("png", "jpg", "jpeg", "webp", "gif"), 10L * 1024 * 1024, Duration.ofHours(1));
    private final ScreenService screens;
    private final ObjectStorage storage;

    public ScreenImageService(ScreenService screens, ObjectStorage storage) {
        this.screens = screens;
        this.storage = storage;
    }

    public UploadTicket prepareUpload(UUID screenId, String filename, String contentType, long size) {
        screens.get(screenId);
        validateMediaType(filename, contentType);
        return storage.prepareUpload(PURPOSE, filename, contentType, size);
    }

    public StoredObject complete(UUID screenId, UUID objectId) {
        screens.get(screenId);
        storage.completeUpload(objectId);
        StoredObject image = storage.get(PURPOSE, objectId);
        validateMediaType(image.filename(), image.contentType());
        return image;
    }

    public DownloadTicket draftDownload(UUID screenId, UUID objectId) {
        // 设计者可预览刚上传、尚未保存到草稿的图片；查看者走另一个冻结快照入口。
        screens.get(screenId);
        StoredObject image = storage.get(PURPOSE, objectId);
        validateMediaType(image.filename(), image.contentType());
        return storage.prepareDownload(PURPOSE, objectId);
    }

    public DownloadTicket publishedDownload(UUID screenId, UUID objectId) {
        // 必须先检查可见的冻结快照，再触碰对象存储；草稿中的替换不能成为查看者下载旁路。
        if (!screens.published(screenId).definition().imageObjectIds().contains(objectId)) {
            throw new ForgeException(ScreenErrorCode.NOT_FOUND);
        }
        StoredObject image = storage.get(PURPOSE, objectId);
        validateMediaType(image.filename(), image.contentType());
        return storage.prepareDownload(PURPOSE, objectId);
    }

    static void validateMediaType(String filename, String contentType) {
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String expected = switch (extension) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            default -> null;
        };
        if (expected != null && !expected.equalsIgnoreCase(contentType)) {
            throw new ForgeException(ScreenErrorCode.INVALID_IMAGE);
        }
    }
}
