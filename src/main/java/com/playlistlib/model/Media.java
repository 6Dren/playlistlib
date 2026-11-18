
package com.playlistlib.model;

import java.time.Instant;
import java.util.Objects;

public class Media {

    private final String id;

    private String listId;
    private String title;
    private String sourceUrl;
    private String sourceId;
    private String extractor;
    private Double durationSeconds;
    private String metadataJson;

    private Instant createdAt;
    private Instant updatedAt;

    public Media(
            String id,
            String listId,
            String title,
            String sourceUrl,
            String sourceId,
            String extractor,
            Double durationSeconds,
            String metadataJson,
            Instant createdAt,
            Instant updatedAt) {

        this.id = Objects.requireNonNull(id, "Media ID cannot be null");
        this.listId = Objects.requireNonNull(listId, "List ID cannot be null");
        this.title = Objects.requireNonNull(title, "Title cannot be null");

        this.sourceUrl = sourceUrl;
        this.sourceId = sourceId;
        this.extractor = extractor;
        this.durationSeconds = durationSeconds;
        this.metadataJson = metadataJson;

        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // Getters

    public String getId() {
        return id;
    }

    public String getListId() {
        return listId;
    }

    public String getTitle() {
        return title;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getExtractor() {
        return extractor;
    }

    public Double getDurationSeconds() {
        return durationSeconds;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // Setters

    public void setListId(String listId) {
        this.listId = Objects.requireNonNull(listId);
    }

    public void setTitle(String title) {
        this.title = Objects.requireNonNull(title);
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public void setExtractor(String extractor) {
        this.extractor = extractor;
    }

    public void setDurationSeconds(Double durationSeconds) {

        if (durationSeconds != null && durationSeconds < 0) {
            throw new IllegalArgumentException(
                    "Duration cannot be negative");
        }

        this.durationSeconds = durationSeconds;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
