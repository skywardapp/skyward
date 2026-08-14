package dev.fritze.skyward.ui.locations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.data.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock

class LocationsViewModel(private val container: AppContainer) : ViewModel() {
    val locations: StateFlow<List<SavedLocation>> = container.locationRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(id: String) {
        viewModelScope.launch {
            container.locationRepo.delete(id)
            container.replanAndSync()
        }
    }

    fun setPrimary(location: SavedLocation) {
        viewModelScope.launch {
            container.locationRepo.upsert(location.copy(isPrimary = true, modifiedAt = Clock.System.now()))
        }
    }
}

/** Backs [dev.fritze.skyward.ui.locations.LocationEditorScreen] for both "add" and "edit" (existing == null means add). */
class LocationEditorViewModel(private val container: AppContainer, private val locationId: String?) : ViewModel() {

    suspend fun load(): SavedLocation? = locationId?.let { container.locationRepo.getById(it) }

    fun save(name: String, latDeg: Double, lonDeg: Double, isPrimary: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            val now = Clock.System.now()
            val existing = locationId?.let { container.locationRepo.getById(it) }
            val location = SavedLocation(
                id = locationId ?: newLocationId(),
                name = name,
                point = GeoPoint(latDeg, lonDeg),
                isPrimary = isPrimary || (existing == null && container.locationRepo.getAll().isEmpty()),
                createdAt = existing?.createdAt ?: now,
                modifiedAt = now,
            )
            container.locationRepo.upsert(location)
            container.replanAndSync()
            onDone()
        }
    }

    private fun newLocationId(): String = java.util.UUID.randomUUID().toString() // §5: SavedLocation.id is UUID v4
}
