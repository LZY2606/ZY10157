package com.local.spectrum.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GeometryTest {
  @Test
  void touchingIntervalsHaveZeroOverlapAndNoDoubleCount() {
    assertThat(Geometry.overlap(100.0d, 200.0d, 200.0d, 300.0d)).isZero();
    assertThat(Geometry.overlap(100L, 200L, 200L, 300L)).isZero();
    assertThat(Geometry.gap(100L, 200L, 200L, 300L)).isZero();
  }

  @Test
  void overlapUsesActualRangeRatherThanCenters() {
    assertThat(Geometry.overlap(90.0d, 110.0d, 105.0d, 125.0d)).isEqualTo(5.0d);
    assertThat(Geometry.overlap(90.0d, 100.0d, 100.0d, 110.0d)).isZero();
  }
}
