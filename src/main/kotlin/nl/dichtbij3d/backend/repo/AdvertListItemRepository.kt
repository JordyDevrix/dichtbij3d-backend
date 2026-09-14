package nl.dichtbij3d.backend.repo

import nl.dichtbij3d.backend.domain.AdvertListItem
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AdvertListItemRepository : JpaRepository<AdvertListItem, UUID> {
    fun existsByListIdAndAdvertId(listId: UUID, advertId: UUID): Boolean
    fun deleteByListIdAndAdvertId(listId: UUID, advertId: UUID)
}
