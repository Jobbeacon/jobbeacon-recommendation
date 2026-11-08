package com.jobbeacon.recommendation.backend.dto;

import java.util.Collection;
import java.util.UUID;

public record IdsRequest(Collection<UUID> ids) {
}
