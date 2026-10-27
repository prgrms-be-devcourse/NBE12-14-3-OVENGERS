package com.ovengers.slotkey.space.dto.response;

import com.ovengers.slotkey.space.entity.Space;
import com.ovengers.slotkey.space.entity.SpaceStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kotlin 전환 후에도 Java에서 기존 Space 생성자/builder/getter와
 * {@code @JvmStatic} 응답 팩토리를 그대로 호출할 수 있는지 확인한다.
 */
class SpaceResponseJavaCompatibilityTest {

    @Test
    @DisplayName("Java builder/getter와 @JvmStatic 팩토리가 완전한 공간을 그대로 매핑한다")
    void builderAndStaticFactories_remainJavaCompatible() {
        Space space = Space.builder()
                .id(11L)
                .name("판교 A룸")
                .location("판교")
                .description("설명")
                .capacity(6)
                .pricePerSlot(5000L)
                .imagePath(null)
                .openingTime(LocalTime.of(9, 0))
                .closingTime(LocalTime.of(18, 0))
                .status(SpaceStatus.ACTIVE)
                .version(3)
                .build();

        SpaceResponse listResponse = SpaceResponse.from(space);
        SpaceDetailResponse detailResponse = SpaceDetailResponse.from(space);

        assertThat(listResponse.id()).isEqualTo(11L);
        assertThat(listResponse.name()).isEqualTo("판교 A룸");
        assertThat(listResponse.imagePath()).isNull();
        assertThat(detailResponse.description()).isEqualTo("설명");
        assertThat(detailResponse.status()).isEqualTo(SpaceStatus.ACTIVE);
        assertThat(detailResponse.version()).isEqualTo(3);
    }

    @Test
    @DisplayName("Java no-arg/allargs 생성자는 유지되지만 응답 변환은 null 필드를 명시적으로 거절한다")
    void noArgAndAllArgsConstructors_stillWorkButConversionFailsExplicitly() {
        Space noArg = new Space();
        assertThat(noArg.getId()).isNull();
        assertThat(noArg.getVersion()).isEqualTo(0);

        Long boxedId = noArg.getId();
        Integer boxedCapacity = noArg.getCapacity();
        Long boxedPrice = noArg.getPricePerSlot();
        assertThat(boxedId).isNull();
        assertThat(boxedCapacity).isNull();
        assertThat(boxedPrice).isNull();

        assertThatThrownBy(() -> SpaceResponse.from(noArg))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Space.id");
        assertThatThrownBy(() -> SpaceDetailResponse.from(noArg))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Space.id");

        Space unsavedAllArgs = new Space(
                null,
                "미저장 회의실",
                "강남",
                null,
                4,
                3000L,
                null,
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                SpaceStatus.ACTIVE,
                0);
        assertThat(unsavedAllArgs.getId()).isNull();
        assertThat(unsavedAllArgs.getDescription()).isNull();
        assertThatThrownBy(() -> SpaceDetailResponse.from(unsavedAllArgs))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Space.id");
    }

    @Test
    @DisplayName("Java builder의 boxed nullable 필드와 불완전 생성 후 변환 실패 계약이 유지된다")
    void builderNullableFields_andIncompleteConversionContract() {
        Space incomplete = Space.builder()
                .id(11L)
                .name("이름만 있는 공간")
                .build();

        assertThat(incomplete.getName()).isEqualTo("이름만 있는 공간");
        assertThat(incomplete.getCapacity()).isNull();
        assertThat(incomplete.getPricePerSlot()).isNull();
        assertThat(incomplete.getStatus()).isNull();

        assertThatThrownBy(() -> SpaceResponse.from(incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Space.location");
        assertThatThrownBy(() -> SpaceDetailResponse.from(incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Space.location");
    }
}
