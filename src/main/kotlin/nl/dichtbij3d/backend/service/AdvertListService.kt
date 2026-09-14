package nl.dichtbij3d.backend.service

import nl.dichtbij3d.backend.domain.AdvertList
import nl.dichtbij3d.backend.domain.AdvertListItem
import nl.dichtbij3d.backend.dto.AdvertListDto
import nl.dichtbij3d.backend.repo.AdvertListItemRepository
import nl.dichtbij3d.backend.repo.AdvertListRepository
import nl.dichtbij3d.backend.repo.AdvertRepository
import nl.dichtbij3d.backend.repo.UserRepository
import nl.dichtbij3d.backend.web.ApiException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AdvertListService(
    private val listRepository: AdvertListRepository,
    private val itemRepository: AdvertListItemRepository,
    private val userRepository: UserRepository,
    private val advertRepository: AdvertRepository,
    private val mapper: DtoMapper,
) {

    @Transactional
    fun initializeDefaultList(userId: UUID): AdvertList {
        val user = userRepository.findById(userId).orElseThrow()
        var defaultList = listRepository.findByUserIdAndIsDefaultTrue(userId)
        if (defaultList == null) {
            defaultList = AdvertList(user = user, name = "Favorites", isDefault = true)
            listRepository.save(defaultList)
        }
        return defaultList
    }

    @Transactional(readOnly = true)
    fun getUserLists(userId: UUID): List<AdvertListDto> {
        val lists = listRepository.findByUserIdOrderByCreatedAtAsc(userId)
        if (lists.none { it.isDefault }) {
            // we could lazily initialize it here if we want, or just return as is
            // returning as is for readOnly, but actually let's initialize if missing
        }
        return lists.map(mapper::advertList)
    }
    
    // We need a wrapper to lazily initialize default list
    @Transactional
    fun getOrCreateUserLists(userId: UUID): List<AdvertListDto> {
        val lists = listRepository.findByUserIdOrderByCreatedAtAsc(userId).toMutableList()
        if (lists.none { it.isDefault }) {
            lists.add(0, initializeDefaultList(userId))
        }
        return lists.map(mapper::advertList)
    }

    @Transactional
    fun createList(userId: UUID, name: String): AdvertListDto {
        val user = userRepository.findById(userId).orElseThrow()
        val list = AdvertList(user = user, name = name, isDefault = false)
        return mapper.advertList(listRepository.save(list))
    }

    @Transactional
    fun deleteList(userId: UUID, listId: UUID) {
        val list = listRepository.findById(listId).orElseThrow { ApiException.notFound("List") }
        if (list.user.id != userId) throw ApiException.forbidden("Not your list")
        if (list.isDefault) throw ApiException.badRequest("Cannot delete default list")
        listRepository.delete(list)
    }

    @Transactional
    fun addToList(userId: UUID, listId: UUID, advertId: UUID) {
        val list = listRepository.findById(listId).orElseThrow { ApiException.notFound("List") }
        if (list.user.id != userId) throw ApiException.forbidden("Not your list")
        val advert = advertRepository.findById(advertId).orElseThrow { ApiException.notFound("Advert") }
        if (!itemRepository.existsByListIdAndAdvertId(listId, advertId)) {
            itemRepository.save(AdvertListItem(list = list, advert = advert))
        }
    }

    @Transactional
    fun removeFromList(userId: UUID, listId: UUID, advertId: UUID) {
        val list = listRepository.findById(listId).orElseThrow { ApiException.notFound("List") }
        if (list.user.id != userId) throw ApiException.forbidden("Not your list")
        itemRepository.deleteByListIdAndAdvertId(listId, advertId)
    }
}
