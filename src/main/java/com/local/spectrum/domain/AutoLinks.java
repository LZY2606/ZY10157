package com.local.spectrum.domain;

import java.util.List;

public record AutoLinks(Integer version, long createdAt, int modelVersion, String note, List<String> strongEdgeKeys) {
}
