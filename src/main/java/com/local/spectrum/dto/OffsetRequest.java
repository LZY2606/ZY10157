package com.local.spectrum.dto;

public record OffsetRequest(
    String deviceId,
    String calibrationVersion,
    double offsetHz,
    String note) {
}
