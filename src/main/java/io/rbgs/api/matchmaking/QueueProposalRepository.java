package io.rbgs.api.matchmaking;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QueueProposalRepository extends JpaRepository<QueueProposal, UUID> { }
