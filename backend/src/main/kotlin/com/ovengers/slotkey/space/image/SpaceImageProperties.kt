package com.ovengers.slotkey.space.image

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 공간 대표 이미지 파일 저장소 설정.
 *
 * - storageRoot 는 영구 저장 디렉터리를 가리키며,
 *   Spring MVC 의 spring.servlet.multipart.location(임시 파싱 디렉터리)과 구별됩니다.
 * - 운영 환경에서는 서버 재시작 및 재배포 후에도 파일이 보존되는 지속 디스크 경로여야 합니다.
 */
@Component
@ConfigurationProperties(prefix = "app.space-image")
class SpaceImageProperties {
    var storageRoot: String = "./data/space-images"
}
