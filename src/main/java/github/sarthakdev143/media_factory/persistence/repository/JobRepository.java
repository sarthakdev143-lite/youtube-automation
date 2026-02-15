package github.sarthakdev143.media_factory.persistence.repository;

import github.sarthakdev143.media_factory.model.VideoJobState;
import github.sarthakdev143.media_factory.persistence.entity.JobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<JobEntity, UUID> {

    Optional<JobEntity> findFirstByStateOrderByCreatedAtAsc(VideoJobState state);

    Optional<JobEntity> findFirstByStateInOrderByCreatedAtAsc(Collection<VideoJobState> states);

    boolean existsByStateIn(Collection<VideoJobState> states);

    List<JobEntity> findAllByState(VideoJobState state);
}
