package github.sarthakdev143.media_factory.persistence;

import github.sarthakdev143.media_factory.model.PrivacyStatus;
import github.sarthakdev143.media_factory.model.VideoJobStage;
import github.sarthakdev143.media_factory.model.VideoJobState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "video_jobs")
public class VideoJob {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private VideoJobState state;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Lob
    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "privacy_status", nullable = false, length = 32)
    private PrivacyStatus privacyStatus;

    @Lob
    @Column(name = "tags", columnDefinition = "TEXT")
    private String tags;

    @Column(name = "category_id", length = 8)
    private String categoryId;

    @Column(name = "publish_at")
    private Instant publishAt;

    @Column(name = "input_image_path", nullable = false, columnDefinition = "TEXT")
    private String inputImagePath;

    @Column(name = "input_audio_path", nullable = false, columnDefinition = "TEXT")
    private String inputAudioPath;

    @Column(name = "input_thumbnail_path", columnDefinition = "TEXT")
    private String inputThumbnailPath;

    @Column(name = "output_path", columnDefinition = "TEXT")
    private String outputPath;

    @Column(name = "youtube_video_id", length = 64)
    private String youtubeVideoId;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_stage", length = 64)
    private VideoJobStage jobStage;

    @Lob
    @Column(name = "stage_detail", columnDefinition = "TEXT")
    private String stageDetail;

    @Column(name = "progress_percent", nullable = false)
    private int progressPercent;

    @Column(name = "generation_progress_percent")
    private Integer generationProgressPercent;

    @Column(name = "upload_progress_percent")
    private Integer uploadProgressPercent;

    @Column(name = "upload_state", length = 64)
    private String uploadState;

    @Lob
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Lob
    @Column(name = "warning_message", columnDefinition = "TEXT")
    private String warningMessage;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "vignette_strength_percent")
    private Integer vignetteStrengthPercent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public VideoJob() {
        // JPA
    }

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public VideoJobState getState() {
        return state;
    }

    public void setState(VideoJobState state) {
        this.state = state;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public PrivacyStatus getPrivacyStatus() {
        return privacyStatus;
    }

    public void setPrivacyStatus(PrivacyStatus privacyStatus) {
        this.privacyStatus = privacyStatus;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    public Instant getPublishAt() {
        return publishAt;
    }

    public void setPublishAt(Instant publishAt) {
        this.publishAt = publishAt;
    }

    public String getInputImagePath() {
        return inputImagePath;
    }

    public void setInputImagePath(String inputImagePath) {
        this.inputImagePath = inputImagePath;
    }

    public String getInputAudioPath() {
        return inputAudioPath;
    }

    public void setInputAudioPath(String inputAudioPath) {
        this.inputAudioPath = inputAudioPath;
    }

    public String getInputThumbnailPath() {
        return inputThumbnailPath;
    }

    public void setInputThumbnailPath(String inputThumbnailPath) {
        this.inputThumbnailPath = inputThumbnailPath;
    }

    public String getOutputPath() {
        return outputPath;
    }

    public void setOutputPath(String outputPath) {
        this.outputPath = outputPath;
    }

    public String getYoutubeVideoId() {
        return youtubeVideoId;
    }

    public void setYoutubeVideoId(String youtubeVideoId) {
        this.youtubeVideoId = youtubeVideoId;
    }

    public VideoJobStage getJobStage() {
        return jobStage;
    }

    public void setJobStage(VideoJobStage jobStage) {
        this.jobStage = jobStage;
    }

    public String getStageDetail() {
        return stageDetail;
    }

    public void setStageDetail(String stageDetail) {
        this.stageDetail = stageDetail;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(int progressPercent) {
        this.progressPercent = progressPercent;
    }

    public Integer getGenerationProgressPercent() {
        return generationProgressPercent;
    }

    public void setGenerationProgressPercent(Integer generationProgressPercent) {
        this.generationProgressPercent = generationProgressPercent;
    }

    public Integer getUploadProgressPercent() {
        return uploadProgressPercent;
    }

    public void setUploadProgressPercent(Integer uploadProgressPercent) {
        this.uploadProgressPercent = uploadProgressPercent;
    }

    public String getUploadState() {
        return uploadState;
    }

    public void setUploadState(String uploadState) {
        this.uploadState = uploadState;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getWarningMessage() {
        return warningMessage;
    }

    public void setWarningMessage(String warningMessage) {
        this.warningMessage = warningMessage;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(int durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public Integer getVignetteStrengthPercent() {
        return vignetteStrengthPercent;
    }

    public void setVignetteStrengthPercent(Integer vignetteStrengthPercent) {
        this.vignetteStrengthPercent = vignetteStrengthPercent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VideoJob videoJob)) {
            return false;
        }
        return Objects.equals(id, videoJob.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
