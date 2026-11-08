package com.jobbeacon.recommendation.cdc;

import com.jobbeacon.recommendation.queue.GraphEntityType;

import java.util.UUID;

public record GraphOutboxChange(GraphEntityType type, UUID entityId) {
}
