package github.sarthakdev143.media_factory.persistence;

import github.sarthakdev143.media_factory.model.VideoJobState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VideoJobRepository extends JpaRepository<VideoJob, UUID> {

    boolean existsByState(VideoJobState state);

    Optional<VideoJob> findFirstByStateOrderByCreatedAtAsc(VideoJobState state);

    List<VideoJob> findAllByState(VideoJobState state);

    long countByState(VideoJobState state);
}
