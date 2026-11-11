package io.saasforge.lowcode.screen.controller;

import io.saasforge.lowcode.screen.service.ScreenImageService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.RequiresPermission;
import io.saasforge.sdk.storage.DownloadTicket;
import io.saasforge.sdk.storage.StoredObject;
import io.saasforge.sdk.storage.UploadTicket;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "大屏图片")
@RestController
@RequestMapping("/screens/{screenId}")
class ScreenImageController {
    private final ScreenImageService images;

    ScreenImageController(ScreenImageService images) {
        this.images = images;
    }

    @Operation(summary = "申请直传大屏图片")
    @PostMapping("/images/uploads")
    @RequiresPermission("screen:edit")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<UploadTicket> prepareUpload(@PathVariable UUID screenId, @Valid @RequestBody UploadRequest request) {
        return ApiResponse.created(
                images.prepareUpload(screenId, request.filename(), request.contentType(), request.size()));
    }

    @Operation(summary = "确认图片已上传并核对用途")
    @PostMapping("/images/{objectId}/complete")
    @RequiresPermission("screen:edit")
    ApiResponse<StoredObject> complete(@PathVariable UUID screenId, @PathVariable UUID objectId) {
        return ApiResponse.ok(images.complete(screenId, objectId));
    }

    @Operation(summary = "设计者下载草稿或尚未保存的图片")
    @GetMapping("/draft/images/{objectId}/download")
    @RequiresPermission("screen:edit")
    ApiResponse<DownloadTicket> draftDownload(@PathVariable UUID screenId, @PathVariable UUID objectId) {
        return ApiResponse.ok(images.draftDownload(screenId, objectId));
    }

    @Operation(summary = "查看者下载可见已发布快照引用的图片")
    @GetMapping("/images/{objectId}/download")
    @RequiresPermission("screen:view")
    ApiResponse<DownloadTicket> publishedDownload(@PathVariable UUID screenId, @PathVariable UUID objectId) {
        return ApiResponse.ok(images.publishedDownload(screenId, objectId));
    }

    record UploadRequest(
            @NotBlank @Size(max = 255) String filename,
            @NotBlank @Size(max = 255) String contentType,
            @Positive long size) {}
}
