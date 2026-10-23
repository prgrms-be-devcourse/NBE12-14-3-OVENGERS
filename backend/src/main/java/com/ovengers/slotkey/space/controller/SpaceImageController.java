package com.ovengers.slotkey.space.controller;

import com.ovengers.slotkey.space.image.ImageResource;
import com.ovengers.slotkey.space.image.SpaceImageStorage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

@Tag(name = "공간 이미지", description = "공간 대표 이미지 서빙 API")
@RestController
@RequestMapping("/api/v1/space-images")
public class SpaceImageController {

    private final SpaceImageStorage spaceImageStorage;

    @Operation(
            summary = "공간 이미지 조회 (공개)",
            description = """
                저장된 공간 대표 이미지를 조회합니다.
                인증이 필요하지 않은 공개(Public) API입니다.
                fileName은 관리자 공간 사진 업로드(PUT) 또는 공간 조회 응답의 imagePath(/api/v1/space-images/{fileName}) 끝에 포함된 파일명입니다.
                응답은 이미지 바이너리 데이터(JPEG 또는 PNG)이며, Swagger UI의 브라우저 환경에 따라 미리보기가 바로 표시되지 않을 수 있습니다.
                """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "이미지 조회 성공 (바이너리 데이터 반환)",
                    headers = {
                            @Header(name = "Content-Length", description = "이미지 바이너리 크기 (bytes)", schema = @Schema(type = "integer")),
                            @Header(name = "Cache-Control", description = "캐시 설정 (public, max-age=86400)", schema = @Schema(type = "string")),
                            @Header(name = "X-Content-Type-Options", description = "MIME 스니핑 방지 (nosniff)", schema = @Schema(type = "string"))
                    },
                    content = {
                            @Content(
                                    mediaType = "image/jpeg",
                                    schema = @Schema(type = "string", format = "binary")
                            ),
                            @Content(
                                    mediaType = "image/png",
                                    schema = @Schema(type = "string", format = "binary")
                            )
                    }
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "IMAGE_NOT_FOUND: 요청한 이미지 파일을 찾을 수 없거나 파일명이 올바르지 않음",
                    content = @Content(
                            mediaType = org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = com.ovengers.slotkey.global.common.response.ApiResponse.class)
                    )
            )
    })
    @GetMapping("/{fileName:.+}")
    public ResponseEntity<Resource> getImage(
            @Parameter(description = "조회할 이미지 파일명 (공간 대표 사진 업로드 PUT 응답의 data.imagePath 끝 {fileName} 값)")
            @PathVariable("fileName") String fileName) {
        ImageResource imageResource = spaceImageStorage.load(fileName);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(imageResource.contentType()))
                .contentLength(imageResource.contentLength())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                .body(imageResource.resource());
    }

    public SpaceImageController(final SpaceImageStorage spaceImageStorage) {
        this.spaceImageStorage = spaceImageStorage;
    }
}
