package nl.dichtbij3d.backend.repo

import nl.dichtbij3d.backend.domain.AdvertList
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AdvertListRepository : JpaRepository<AdvertList, UUID> {
    fun findByUserIdOrderByCreatedAtAsc(userId: UUID): List<AdvertList>
    fun findByUserIdAndIsDefaultTrue(userId: UUID): AdvertList?
}
